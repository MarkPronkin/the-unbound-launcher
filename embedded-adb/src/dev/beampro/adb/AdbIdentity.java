package dev.beampro.adb;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;

/** A fresh RSA identity per installation, kept in private, excluded-from-backup storage. */
final class AdbIdentity {
    final PrivateKey key;
    final X509Certificate certificate;
    private AdbIdentity(PrivateKey key, X509Certificate certificate) {
        this.key = key; this.certificate = certificate;
    }
    static AdbIdentity load(Context context) throws Exception {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "adb-identity.bin"));
        if (file.getBaseFile().exists()) {
            try (DataInputStream in = new DataInputStream(file.openRead())) {
                if (in.readInt() != 1) throw new IOException("Unknown ADB identity format");
                PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(block(in)));
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(block(in)));
                return new AdbIdentity(key, cert);
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        java.security.KeyPair pair = generator.generateKeyPair();
        AlgorithmIdentifier algorithm = new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.2.840.113549.1.1.11"), DERNull.INSTANCE);
        V3TBSCertificateGenerator tbs = new V3TBSCertificateGenerator();
        X500Name name = new X500Name("CN=Unbound Launcher ADB");
        tbs.setSerialNumber(new ASN1Integer(new BigInteger(128, new SecureRandom()).setBit(127)));
        tbs.setSignature(algorithm); tbs.setIssuer(name); tbs.setSubject(name);
        tbs.setStartDate(new Time(new Date(System.currentTimeMillis() - 86400000L)));
        tbs.setEndDate(new Time(new Date(System.currentTimeMillis() + 30L * 365 * 86400000L)));
        tbs.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
        TBSCertificate body = tbs.generateTBSCertificate();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate()); signer.update(body.getEncoded("DER"));
        byte[] encoded = new DERSequence(new ASN1Encodable[]{body, algorithm, new DERBitString(signer.sign())}).getEncoded("DER");
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(encoded));
        cert.verify(pair.getPublic());
        FileOutputStream stream = file.startWrite();
        try {
            DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(1); write(out, pair.getPrivate().getEncoded()); write(out, encoded); out.flush();
            file.finishWrite(stream);
        } catch (Exception error) { file.failWrite(stream); throw error; }
        return new AdbIdentity(pair.getPrivate(), cert);
    }
    private static byte[] block(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > 16384) throw new IOException("Invalid ADB identity");
        byte[] bytes = new byte[length]; in.readFully(bytes); return bytes;
    }
    private static void write(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length); out.write(bytes);
    }
}
