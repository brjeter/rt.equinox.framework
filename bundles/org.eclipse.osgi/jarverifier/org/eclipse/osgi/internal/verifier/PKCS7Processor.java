/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.internal.verifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.util.*;
import javax.security.auth.x500.X500Principal;
import org.eclipse.osgi.internal.provisional.verifier.CertificateChain;

/**
 * This class processes a PKCS7 file. See RFC 2315 for specifics.
 */
public class PKCS7Processor implements CertificateChain {
	private static final int SIGNEDDATA_OID[] = {1, 2, 840, 113549, 1, 7, 2};
	private static final int MD5_OID[] = {1, 2, 840, 113549, 2, 5};
	private static final int MD2_OID[] = {1, 2, 840, 113549, 2, 2};
	private static final int SHA1_OID[] = {1, 3, 14, 3, 2, 26};
	private static final int DSA_OID[] = {1, 2, 840, 10040, 4, 1};
	private static final int RSA_OID[] = {1, 2, 840, 113549, 1, 1, 1};

	private static CertificateFactory certFact;
	private static KeyStores keyStores = new KeyStores();
	static {
		try {
			certFact = CertificateFactory.getInstance("X.509"); //$NON-NLS-1$
		} catch (CertificateException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private String certChain;
	private Certificate[] certificates;
	private boolean trusted;

	String oid2String(int oid[]) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < oid.length; i++) {
			if (i > 0)
				sb.append('.');
			sb.append(oid[i]);
		}
		return sb.toString();
	}

	String findEncryption(int encOid[]) throws NoSuchAlgorithmException {
		if (Arrays.equals(DSA_OID, encOid)) {
			return "DSA"; //$NON-NLS-1$
		}
		if (Arrays.equals(RSA_OID, encOid)) {
			return "RSA"; //$NON-NLS-1$
		}
		throw new NoSuchAlgorithmException("No algorithm found for " + oid2String(encOid)); //$NON-NLS-1$
	}

	String findDigest(int digestOid[]) throws NoSuchAlgorithmException {
		if (Arrays.equals(SHA1_OID, digestOid)) {
			return "SHA1"; //$NON-NLS-1$
		}
		if (Arrays.equals(MD5_OID, digestOid)) {
			return "MD5"; //$NON-NLS-1$
		}
		if (Arrays.equals(MD2_OID, digestOid)) {
			return "MD2"; //$NON-NLS-1$
		}
		throw new NoSuchAlgorithmException("No algorithm found for " + oid2String(digestOid)); //$NON-NLS-1$
	}

	/*
	 * static void printBP(BERProcessor bp, int depth) {
	 * System.out.print(depth); for(int i = 0; i < depth; i++)
	 * System.out.print(" "); System.out.println(bp); }
	 * 
	 * static void dumpSeq(BERProcessor bp, int depth) {
	 * while(!bp.endOfSequence()) { printBP(bp, depth); if (bp.constructed) {
	 * dumpSeq(bp.stepInto(), depth+1); } bp.stepOver(); } }
	 * 
	 * void hexDump(byte buffer[], int off, int len) { for(int i = 0; i < len;
	 * i++) { System.out.print(Integer.toString(buffer[i]&0xff, 16) + " "); if
	 * (i % 16 == 15) System.out.println(); } System.out.println(); }
	 */

	public PKCS7Processor(String certChain, boolean trusted, byte[][] certificates) throws CertificateException {
		this.certChain = certChain;
		this.trusted = trusted;
		this.certificates = new Certificate[certificates.length];
		for (int i = 0; i < certificates.length; i++)
			this.certificates[i] = certFact.generateCertificate(new ByteArrayInputStream(certificates[i]));
	}

	public PKCS7Processor(byte pkcs7[], int pkcs7Offset, int pkcs7Length, byte data[], int dataOffset, int dataLength) throws IOException, InvalidKeyException, CertificateException, NoSuchAlgorithmException, NoSuchProviderException, SignatureException {
		// First grab the certificates
		Collection certs = certFact.generateCertificates(new ByteArrayInputStream(pkcs7, pkcs7Offset, pkcs7Length));
		BERProcessor bp = new BERProcessor(pkcs7, pkcs7Offset, pkcs7Length);
		// Just do a sanity check and make sure we are actually doing a PKCS7
		// stream
		bp = bp.stepInto();
		if (!Arrays.equals(bp.getObjId(), SIGNEDDATA_OID)) {
			throw new IOException("Not a valid PKCS#7 file"); //$NON-NLS-1$
		}
		bp.stepOver(); // skip over the oid
		bp = bp.stepInto(); // go into the Signed data
		bp = bp.stepInto(); // It is a structure;
		bp.stepOver(); // Yeah, yeah version = 1
		bp.stepOver(); // We'll see the digest stuff again;
		bp.stepOver(); // We'll see the contentInfo in signerinfo
		// Okay, here are our certificates.
		bp.stepOver();
		if (bp.classOfTag == BERProcessor.UNIVERSAL_TAGCLASS && bp.tag == 1) {
			bp.stepOver(); // Don't use the CRLs if present
		}
		bp = bp.stepInto(); // Step into the set of signerinfos
		bp = bp.stepInto(); // Step into the signerinfo sequence
		bp.stepOver(); // Skip the version
		BERProcessor issuerAndSN = bp.stepInto();
		X500Principal signerIssuer = new X500Principal(new ByteArrayInputStream(issuerAndSN.buffer, issuerAndSN.offset, issuerAndSN.endOffset - issuerAndSN.offset));
		issuerAndSN.stepOver();
		BigInteger sn = issuerAndSN.getIntValue();
		Certificate newSignerCert = null;
		Iterator itr = certs.iterator();
		while (itr.hasNext()) {
			X509Certificate cert = (X509Certificate) itr.next();
			if (cert.getIssuerX500Principal().equals(signerIssuer) && cert.getSerialNumber().equals(sn)) {
				newSignerCert = cert;
				break;
			}
		}
		if (newSignerCert == null)
			throw new CertificateException("Signer certificate not in pkcs7block"); //$NON-NLS-1$
		bp.stepOver(); // skip the issuer name and serial number
		BERProcessor digestAlg = bp.stepInto();
		String digest = findDigest(digestAlg.getObjId());
		bp.stepOver(); // skip the digest alg
		if (bp.classOfTag == BERProcessor.CONTEXTSPECIFIC_TAGCLASS) {
			bp.stepOver(); // This would be the authenticated attributes
		}
		BERProcessor encryptionAlg = bp.stepInto();
		String enc = findEncryption(encryptionAlg.getObjId());
		bp.stepOver(); // skip the encryption alg
		byte signature[] = bp.getBytes();
		Signature sig = Signature.getInstance(digest + "with" + enc); //$NON-NLS-1$
		sig.initVerify(newSignerCert.getPublicKey());
		sig.update(data, dataOffset, dataLength);
		if (!sig.verify(signature)) {
			throw new SignatureException("Signature doesn't verify"); //$NON-NLS-1$
		}
		ArrayList certList = new ArrayList(1);
		certList.add(newSignerCert);
		StringBuffer sb = new StringBuffer();
		X509Certificate xcert = (X509Certificate) newSignerCert;
		// We save off the previous certificate so that we can
		// verify with the next certificate in the chain
		Certificate prevCert = null;
		boolean valid = true;
		while (true) {
			// TODO The CertificateFactory may do this check, but better safe than sorry
			try {
				xcert.checkValidity();
			} catch (CertificateException e) {
				valid = false;
			}
			if (prevCert != null) {
				prevCert.verify(xcert.getPublicKey());
				certList.add(xcert);
			}
			prevCert = xcert;

			X500Principal subject = xcert.getSubjectX500Principal();
			X500Principal issuer = xcert.getIssuerX500Principal();
			if (sb.length() > 0)
				sb.append("; "); //$NON-NLS-1$
			sb.append(subject);
			if (subject.equals(issuer))
				break;
			xcert = null;
			itr = certs.iterator();
			while (itr.hasNext()) {
				X509Certificate cert = (X509Certificate) itr.next();
				if (cert.getSubjectX500Principal().equals(issuer)) {
					xcert = cert;
				}
			}
			if (xcert == null)
				throw new CertificateException(subject + " missing from chain"); //$NON-NLS-1$
		}
		certificates = (Certificate[]) certList.toArray(new Certificate[certList.size()]);
		// Now we have to make sure that the CA certificate (xcert) is in the KeyStore
		trusted = valid && keyStores.isTrusted(xcert);
		certChain = sb.toString();
	}

	/**
	 * Returns the Certificate of the signer of this PKCS7Block
	 */
	public Certificate getSigner() {
		if (certificates == null || certificates.length == 0)
			return null;
		return certificates[0];
	}

	public Certificate getRoot() {
		if (certificates == null || certificates.length == 0)
			return null;
		return certificates[certificates.length - 1];
	}

	public Certificate[] getCertificates() {
		return certificates;
	}

	/**
	 * Returns the list of X500 distinguished names that make up the signature chain. Each
	 * distinguished name is separated by a ';'.
	 */
	public String getChain() {
		return certChain;
	}

	/**
	 * Returns true if the signer certificate is trusted
	 * @return true if the signer certificate is trusted
	 */
	public boolean isTrusted() {
		return trusted;
	}

	public boolean equals(Object obj) {
		if (!(obj instanceof CertificateChain))
			return false;
		if (certificates == null)
			return false;
		CertificateChain chain = (CertificateChain) obj;
		if (trusted != chain.isTrusted() || (certChain == null ? chain.getChain() != null : !certChain.equals(chain.getChain())))
			return false;
		Certificate[] otherCerts = chain.getCertificates();
		if (otherCerts == null || certificates.length != otherCerts.length)
			return false;
		for (int i = 0; i < certificates.length; i++)
			if (!certificates[i].equals(otherCerts[i]))
				return false;
		return true;
	}
	/*
	 public static void main(String[] args) throws InvalidKeyException, CertificateException, NoSuchAlgorithmException, SignatureException, KeyStoreException, IOException {
	 byte buffer[] = new byte[65536];
	 int len = System.in.read(buffer);
	 byte manifestBuff[] = new byte[65536];
	 int rc = new FileInputStream("man").read(manifestBuff);
	 PKCS7Processor p7 = new PKCS7Processor(buffer, 0, len, manifestBuff, 0, rc);
	 System.out.println(p7.getSignerCertificate());
	 System.out.println(p7.getCertificateChain());
	 }
	 */
}