package com.local.wififiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Самоподписанный сертификат X.509 v3 для HTTPS: генерируется при первом запуске
 * в приватном каталоге приложения. Обновляется при изменении адресов или истечении срока.
 * Кодирование DER реализовано без внешних библиотек по RFC 5280.
 */
final class SelfSignedCertificate {
    private static final char[] PASSWORD = "wififiles".toCharArray();
    private static final String ALIAS = "server";
    private static final String[] DNS_NAMES = {"pocketbridge.local", "localhost"};
    private static final long RENEW_BEFORE_MS = 7L * 24 * 3600 * 1000;

    private SelfSignedCertificate() {}

    static SSLContext createContext(File dir, List<InetAddress> advertised) throws Exception {
        Set<InetAddress> ipNames = new LinkedHashSet<>();
        ipNames.add(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        for (InetAddress ip : advertised) {
            if (ip instanceof Inet4Address && LanAddresses.allowed(ip)) ipNames.add(ip);
        }
        KeyStore store = loadOrCreate(dir, ipNames);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private static synchronized KeyStore loadOrCreate(File dir, Set<InetAddress> ipNames) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Нет доступа к каталогу сертификата");
        File file = new File(dir, "server-identity.p12");
        KeyStore store = KeyStore.getInstance("PKCS12");
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                store.load(in, PASSWORD);
                Certificate existing = store.getCertificate(ALIAS);
                if (store.isKeyEntry(ALIAS) && existing instanceof X509Certificate) {
                    X509Certificate x509 = (X509Certificate) existing;
                    x509.checkValidity();
                    x509.checkValidity(new Date(System.currentTimeMillis() + RENEW_BEFORE_MS));
                    if (matchesNames(x509, ipNames)) return store;
                }
            } catch (Exception ignored) {
                // повреждённый или истёкший сертификат — пересоздаём
            }
            store = KeyStore.getInstance("PKCS12");
        }
        store.load(null, PASSWORD);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = build(pair, ipNames);
        store.setKeyEntry(ALIAS, pair.getPrivate(), PASSWORD, new Certificate[]{certificate});
        Path tmp = Files.createTempFile(dir.toPath(), "server-identity-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                store.store(out, PASSWORD);
                out.getFD().sync();
            }
            Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return store;
    }

    private static boolean matchesNames(X509Certificate certificate, Set<InetAddress> ipNames) throws Exception {
        Set<List<?>> expected = new LinkedHashSet<>();
        for (String name : DNS_NAMES) expected.add(Arrays.asList(2, name));
        for (InetAddress ip : ipNames) expected.add(Arrays.asList(7, ip.getHostAddress()));
        Collection<List<?>> actual = certificate.getSubjectAlternativeNames();
        return actual != null && expected.equals(new LinkedHashSet<>(actual));
    }

    /* ---------- X.509 v3, DER (RFC 5280) ---------- */

    private static X509Certificate build(KeyPair pair, Set<InetAddress> ipNames) throws Exception {
        byte[] sha256Rsa = oid("1.2.840.113549.1.1.11");
        byte[] algId = seq(sha256Rsa, der(0x05, new byte[0])); // NULL

        byte[] subject = seq(
                set(seq(oid("2.5.4.6"), printable("RU"))),           // C (PrintableString по RFC 5280)
                set(seq(oid("2.5.4.10"), utf8("PocketBridge"))),     // O
                set(seq(oid("2.5.4.3"), utf8("PocketBridge"))));     // CN

        long now = System.currentTimeMillis();
        byte[] validity = seq(
                x509Time(new Date(now - 24L * 3600 * 1000)),
                x509Time(new Date(now + 730L * 24 * 3600 * 1000)));
        byte[] serial = integer(new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE));
        byte[] publicKey = pair.getPublic().getEncoded();

        // X.509 v3 extensions: BasicConstraints(CA:FALSE), KeyUsage, ExtendedKeyUsage(serverAuth), SAN
        byte[] extBasicConstraints = seq(oid("2.5.29.19"), boolTrue(), octet(seq()));
        byte[] extKeyUsage = seq(oid("2.5.29.15"), boolTrue(), octet(der(0x03, new byte[]{0x05, (byte) 0xA0})));
        byte[] extEku = seq(oid("2.5.29.37"), octet(seq(oid("1.3.6.1.5.5.7.3.1"))));
        List<byte[]> names = new ArrayList<>();
        for (String name : DNS_NAMES) names.add(der(0x82, name.getBytes(StandardCharsets.US_ASCII)));
        for (InetAddress ip : ipNames) names.add(der(0x87, ip.getAddress()));
        byte[] extSan = seq(oid("2.5.29.17"), octet(seq(names.toArray(new byte[0][]))));
        byte[] extensions = der(0xA3, seq(extBasicConstraints, extKeyUsage, extEku, extSan));

        byte[] tbs = seq(
                der(0xA0, integer(BigInteger.valueOf(2))),           // [0] version = v3
                serial,
                algId,
                subject,                                             // issuer = subject (самоподписанный)
                validity,
                subject,
                publicKey,
                extensions);
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
    private static byte[] boolTrue() { return der(0x01, new byte[]{(byte) 0xFF}); }
    private static byte[] octet(byte[] value) { return der(0x04, value); }
    private static byte[] printable(String text) { return der(0x13, text.getBytes(StandardCharsets.US_ASCII)); }
    private static byte[] utf8(String text) { return der(0x0C, text.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] x509Time(Date date) {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Calendar cal = Calendar.getInstance(utc, Locale.US);
        cal.setTime(date);
        int year = cal.get(Calendar.YEAR);
        boolean generalized = year >= 2050 || year < 1950;
        SimpleDateFormat format = new SimpleDateFormat(generalized ? "yyyyMMddHHmmss'Z'" : "yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(utc);
        return der(generalized ? 0x18 : 0x17, format.format(date).getBytes(StandardCharsets.US_ASCII));
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
