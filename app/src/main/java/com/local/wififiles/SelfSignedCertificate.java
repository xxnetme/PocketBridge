package com.local.wififiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Самоподписанный сертификат для HTTPS: генерируется один раз при первом запуске
 * и хранится в приватном каталоге приложения. Кодирование X.509 (DER) реализовано
 * вручную, чтобы не зависеть от BouncyCastle, которого нет в публичном SDK Android.
 */
final class SelfSignedCertificate {
    private static final char[] PASSWORD = "wififiles".toCharArray();
    private static final String ALIAS = "server";

    private SelfSignedCertificate() {}

    static SSLContext createContext(File dir) throws Exception {
        KeyStore store = loadOrCreate(dir);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private static KeyStore loadOrCreate(File dir) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Нет доступа к каталогу сертификата");
        File file = new File(dir, "server-identity.p12");
        KeyStore store = KeyStore.getInstance("PKCS12");
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                store.load(in, PASSWORD);
                if (store.containsAlias(ALIAS)) return store;
            } catch (Exception ignored) {
                // повреждённый файл — пересоздаём
            }
            store = KeyStore.getInstance("PKCS12");
        }
        store.load(null, PASSWORD);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = build(pair);
        store.setKeyEntry(ALIAS, pair.getPrivate(), PASSWORD, new Certificate[]{certificate});
        try (OutputStream out = new FileOutputStream(file)) {
            store.store(out, PASSWORD);
        }
        return store;
    }

    /* ---------- X.509 v3, DER ---------- */

    private static X509Certificate build(KeyPair pair) throws Exception {
        byte[] sha256Rsa = oid("1.2.840.113549.1.1.11");
        byte[] algId = seq(sha256Rsa, der(0x05, new byte[0])); // NULL

        byte[] subject = seq(set(
                seq(oid("2.5.4.10"), utf8("PocketBridge")),                            // O
                seq(oid("2.5.4.3"), utf8("PocketBridge")),
                seq(oid("2.5.4.6"), utf8("RU"))));                                     // C

        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 24L * 3600 * 1000)), utcTime(new Date(now + 730L * 24 * 3600 * 1000)));
        byte[] serial = integer(new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE));
        byte[] publicKey = pair.getPublic().getEncoded();

        byte[] tbs = seq(
                der(0xA0, integer(BigInteger.valueOf(2))),   // [0] version = v3
                serial,
                algId,
                subject,                                     // issuer = subject (самоподписанный)
                validity,
                subject,
                publicKey);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] certificateDer = seq(tbs, algId, der(0x03, concat(new byte[]{0}, signature)));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificateDer));
    }

    private static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long value = Long.parseLong(parts[i]);
            byte[] tmp = new byte[9];
            int count = 0;
            do { tmp[count++] = (byte) (value & 0x7f); value >>= 7; } while (value > 0);
            for (int j = count - 1; j >= 0; j--) body.write(j == 0 ? tmp[j] : tmp[j] | 0x80);
        }
        return der(0x06, body.toByteArray());
    }

    private static byte[] integer(BigInteger value) { return der(0x02, value.toByteArray()); }
    private static byte[] utf8(String text) {
        try { return der(0x0C, text.getBytes("UTF-8")); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static byte[] utcTime(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try { return der(0x17, format.format(date).getBytes("US-ASCII")); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] seq(byte[]... parts) { return der(0x30, concat(parts)); }
    private static byte[] set(byte[]... parts) { return der(0x31, concat(parts)); }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) { out.write(length); return; }
        int bytes = length <= 0xFF ? 1 : length <= 0xFFFF ? 2 : length <= 0xFFFFFF ? 3 : 4;
        out.write(0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) out.write((length >> (8 * i)) & 0xFF);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
