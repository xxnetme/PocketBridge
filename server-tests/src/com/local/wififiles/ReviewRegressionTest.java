package com.local.wififiles;

import android.content.Context;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static com.local.wififiles.Test.*;

/** Audit regressions. All data is synthetic; HTTP and TLS connections use loopback only. */
final class ReviewRegressionTest {
    static void run() throws Exception {
        Path fixture = Files.createDirectory(TMP.toPath().resolve("review"));
        tls(fixture);
        dav(fixture);
        limits(fixture);
        expiry(fixture);
    }

    private static FileServer server(Path root, SSLContext ssl) throws IOException {
        return new FileServer(new Context(new java.io.File(ASSETS)), root.toFile(), KEY, ssl, null, 18772);
    }

    private static void tls(Path fixture) throws Exception {
        Path identity = fixture.resolve("identity");
        InetAddress wifi = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 50, 20});
        InetAddress wired = InetAddress.getByAddress(new byte[]{10, 80, 1, 30});
        InetAddress next = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 52, 21});
        List<InetAddress> oldNetwork = List.of(wifi, wired);
        SelfSignedCertificate.createContext(identity.toFile(), List.of()); // pre-fix SAN set
        X509Certificate old = storedCertificate(identity);
        SSLContext migrated = SelfSignedCertificate.createContext(identity.toFile(), oldNetwork);
        X509Certificate current = storedCertificate(identity);
        List<List<?>> expected = List.of(List.of(2, "pocketbridge.local"), List.of(2, "localhost"),
                List.of(7, "127.0.0.1"), List.of(7, wifi.getHostAddress()), List.of(7, wired.getHostAddress()));
        check("TLS: старый кеш заменяется; DNS и оба LAN IPv4 в SAN правильного типа",
                !old.equals(current) && new HashSet<>(current.getSubjectAlternativeNames()).equals(new HashSet<>(expected)),
                current.getSubjectAlternativeNames().toString());
        SelfSignedCertificate.createContext(identity.toFile(), List.of(wired, wifi));
        check("TLS: порядок адресов не меняет сохранённый сертификат", current.equals(storedCertificate(identity)), "");
        check("Android: QR и HTTPS-ссылки используют только текущие IPv4 из сертификата",
                LanAddresses.httpsUrls(List.of(next, wired), oldNetwork, 8765).equals(List.of("https://10.80.1.30:8765"))
                        && LanAddresses.httpsUrls(List.of(next), oldNetwork, 8765).isEmpty(), "");

        FileServer running = server(ROOT.toPath(), migrated);
        int port = running.start();
        try {
            SSLContext trust = trust(current);
            check("TLS: строгая проверка имени поверх LAN IP, localhost и pocketbridge.local",
                    strictList(trust, wifi.getHostAddress(), port) == 200 && strictList(trust, "localhost", port) == 200
                            && strictList(trust, "pocketbridge.local", port) == 200, "");
        } finally { running.stop(); }

        List<InetAddress> newNetwork = List.of(next);
        SSLContext renewed = SelfSignedCertificate.createContext(identity.toFile(), newNetwork);
        X509Certificate latest = storedCertificate(identity);
        check("TLS: после перезапуска добавлен новый IP, удалены старые",
                latest.getSubjectAlternativeNames().contains(List.of(7, next.getHostAddress()))
                        && latest.getSubjectAlternativeNames().size() == 4
                        && LanAddresses.httpsUrls(newNetwork, newNetwork, 8765).equals(List.of("https://192.168.52.21:8765")), "");
        running = server(ROOT.toPath(), renewed);
        port = running.start();
        try {
            SSLContext trust = trust(latest);
            boolean mismatch = false;
            try { strictList(trust, wifi.getHostAddress(), port); }
            catch (SSLHandshakeException e) { mismatch = e.getMessage().contains("subject alternative names matching IP address"); }
            check("TLS: доверенный сертификат проходит для нового IP и отклоняется для старого IP",
                    mismatch && strictList(trust, next.getHostAddress(), port) == 200, "");
        } finally { running.stop(); }
    }

    private static X509Certificate storedCertificate(Path dir) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(dir.resolve("server-identity.p12"))) {
            store.load(input, "wififiles".toCharArray());
        }
        return (X509Certificate) store.getCertificate("server");
    }

    private static SSLContext trust(X509Certificate expected) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("fixture", expected);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trust.getTrustManagers(), null);
        return ssl;
    }

    private static int strictList(SSLContext ssl, String name, int port) throws Exception {
        try (Socket tcp = new Socket()) {
            tcp.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            try (SSLSocket socket = (SSLSocket) ssl.getSocketFactory().createSocket(tcp, name, port, true)) {
                socket.setSoTimeout(10000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                socket.getOutputStream().write(("GET /api/list?path=/ HTTP/1.1\r\nHost: " + name + "\r\nX-Access-Key: "
                        + KEY + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                return readResp(socket.getInputStream()).status;
            }
        }
    }

    private static void dav(Path fixture) throws Exception {
        Path root = Files.createDirectory(fixture.resolve("dav"));
        Path tree = Files.createDirectories(root.resolve("real/nested"));
        Path text = Files.writeString(tree.resolve("keep.txt"), "original");
        Path outside = Files.writeString(fixture.resolve("beside.txt"), "beside");
        Path dest = Files.writeString(root.resolve("destination.txt"), "destination");
        Files.createSymbolicLink(root.resolve("alias-file"), text);
        Files.createSymbolicLink(root.resolve("alias-directory"), tree.getParent());
        Files.createSymbolicLink(root.resolve("alias-missing"), root.resolve("missing-referent"));
        Files.createSymbolicLink(root.resolve("alias-outside"), outside);
        Files.createSymbolicLink(tree.resolve("tree-link"), outside);

        FileServer running = server(root, null);
        PORT = running.start();
        try {
            List<String> rejected = new ArrayList<>();
            for (String leaf : List.of("alias-file", "alias-directory/nested/keep.txt", "alias-missing", "alias-outside")) {
                for (String verb : List.of("GET", "HEAD", "PROPFIND", "DELETE")) {
                    int status = req(verb, "/dav/" + leaf, davAuth(), null).status;
                    if (status != 403) rejected.add(verb + ":" + leaf + "=" + status);
                }
            }
            check("WebDAV: конечный, родительский, висячий и внешний симлинки отклоняются", rejected.isEmpty(), rejected.toString());
            boolean listing = true;
            for (String verb : List.of("GET", "PROPFIND")) {
                String entries = req(verb, "/dav/", davAuth(), null).text();
                listing &= entries.contains("real") && !entries.contains("alias-");
            }
            check("WebDAV: HTML и PROPFIND одинаково скрывают ссылки", listing, "");
            String viaDirectory = "/dav/alias-directory/new";
            check("WebDAV: PUT, MKCOL и LOCK не создают объекты под симлинком",
                    req("PUT", viaDirectory, davAuth(), new byte[]{1}).status == 403
                            && req("PUT", "/dav/alias-missing", davAuth(), new byte[]{2}).status == 403
                            && req("MKCOL", viaDirectory, davAuth(), null).status == 403
                            && req("LOCK", viaDirectory, davAuth(), null).status == 403 && !Files.exists(tree.getParent().resolve("new")), "");
            for (String verb : List.of("COPY", "MOVE")) {
                Map<String, String> toDest = with(davAuth(), "Destination", "/dav/destination.txt");
                boolean denied = req(verb, "/dav/alias-directory/nested/keep.txt", toDest, null).status == 403
                        && req(verb, "/dav/destination.txt", with(davAuth(), "Destination", "/dav/alias-file"), null).status == 403
                        && req(verb, "/dav/destination.txt", with(davAuth(), "Destination", "/dav/alias-directory/new"), null).status == 403;
                check("WebDAV " + verb + ": запрет ссылки в source и Destination", denied, "");
                check("WebDAV " + verb + ": дерево со ссылкой отклонено до перезаписи назначения",
                        req(verb, "/dav/real", toDest, null).status == 403 && Files.isSymbolicLink(tree.resolve("tree-link"))
                                && Files.readString(dest).equals("destination"), "");
            }
            check("WebDAV: не перезаписывает существующее дерево со ссылкой",
                    req("COPY", "/dav/destination.txt", with(davAuth(), "Destination", "/dav/real"), null).status == 403
                            && Files.isSymbolicLink(tree.resolve("tree-link")), "");
            check("WebDAV: отказ не изменил реальные файлы и симлинки", Files.readString(text).equals("original")
                    && Files.readString(outside).equals("beside") && Files.isSymbolicLink(root.resolve("alias-file"))
                    && !Files.exists(root.resolve("missing-referent")), "");
            check("WebDAV: реальная папка со скрытой ссылкой попадает в корзину целиком, без перехода по ссылке",
                    req("DELETE", "/dav/real", davAuth(), null).status == 204
                            && !Files.exists(tree) && Files.readString(outside).equals("beside"), "");
            String id = new JSONObject(get("/api/trash").text()).getJSONArray("items").getJSONObject(0).getString("id");
            check("WebDAV: та же корзина восстанавливает папку и обычные файлы",
                    post("/api/trash-restore?id=" + id).status == 200 && Files.readString(text).equals("original")
                            && Files.isSymbolicLink(tree.resolve("tree-link")), "");
        } finally { running.stop(); }

        WebDav dav = new WebDav(root.toFile(), KEY, false, new TrashStore(root.toFile()));
        Path late = root.resolve("late.txt");
        try (InputStream body = new FilterInputStream(new ByteArrayInputStream(new byte[]{42})) {
            @Override public int read(byte[] buffer, int start, int count) throws IOException {
                Files.createSymbolicLink(late, dest); // fixture changes between preparation and commit
                return super.read(buffer, start, count);
            }
        }) {
            Map<String, String> headers = Map.of("authorization", davAuth().get("Authorization"), "content-length", "1");
            check("WebDAV PUT: перед публикацией заново проверяет путь и убирает временный файл",
                    status(() -> dav.handle("PUT", "/dav/late.txt", headers, body, new ByteArrayOutputStream())) == 403
                            && Files.isSymbolicLink(late) && Files.readString(dest).equals("destination") && parts(root) == 0, "");
        }
    }

    private static void limits(Path fixture) throws Exception {
        Path root = Files.createDirectory(fixture.resolve("limits"));
        Files.writeString(root.resolve("preview.svg"), "<svg xmlns='http://www.w3.org/2000/svg'/>");
        FileServer running = server(root, null);
        PORT = running.start();
        try {
            Set<String> tokens = new HashSet<>();
            for (int i = 0; i < 15; i++) tokens.add(field(post("/api/session"), "token"));
            String earlier = tokens.iterator().next();
            List<Resp> results = parallel(20, i -> () -> post("/api/session"));
            for (Resp response : results) if (response.status == 200) tokens.add(field(response, "token"));
            check("/api/session: параллельная выдача соблюдает лимит; 429 с Retry-After",
                    tokens.size() == 16 && results.stream().filter(r -> r.status == 200).count() == 1
                            && results.stream().filter(r -> r.status == 429 && r.h.getOrDefault("retry-after", "").matches("[1-9][0-9]*")).count() == 19, "");
            Resp renew = req("POST", "/api/session", with(auth(), "X-PocketBridge-Session", earlier), null);
            check("/api/session: при заполнении можно обновить свой токен, не получая новый",
                    renew.status == 200 && field(renew, "token").equals(earlier)
                            && new JSONObject(renew.text()).getLong("ttl") == ThumbnailSessions.TTL_MS, "");
            boolean intact = true;
            for (String token : tokens) intact &= req("GET", "/thumb?path=/preview.svg&token=" + token, null, null).status == 200;
            check("/thumb: все ранее выданные токены продолжают работать; код доступа отделён",
                    intact && req("POST", "/api/session", Map.of("X-PocketBridge-Session", earlier), null).status == 403
                            && get("/api/list").status == 200, "");

            List<String> uploads = new ArrayList<>();
            for (int i = 0; i < 31; i++) uploads.add(field(init("prior-" + i), "uploadId"));
            results = parallel(24, i -> () -> init("parallel-" + i));
            for (Resp response : results) if (response.status == 200) uploads.add(field(response, "uploadId"));
            check("/api/upload-init: из 24 параллельных стартов на последний слот принят ровно один",
                    new HashSet<>(uploads).size() == 32 && results.stream().filter(r -> r.status == 429).count() == 23
                            && running.activeUploads() == 32 && parts(root) == 32, "");
            String aborted = uploads.get(0);
            Resp abort = post("/api/upload-abort?id=" + aborted);
            String replacement = field(init("after-abort"), "uploadId");
            check("/api/upload-abort: освобождает ровно один слот и удаляет part",
                    abort.status == 200 && get("/api/upload-status?id=" + aborted).status == 404 && parts(root) == 32
                            && init("still-full").status == 429, "");
            check("/api/upload-finish: публикация освобождает слот",
                    post("/api/upload-finish?id=" + replacement).status == 200 && Files.size(root.resolve("after-abort")) == 0
                            && running.activeUploads() == 31 && parts(root) == 31, "");
            String conflict = field(init("late-conflict"), "uploadId");
            Path collision = Files.writeString(root.resolve("late-conflict"), "keep this");
            check("/api/upload-finish: конфликт освобождает слот, не меняя существующий файл",
                    post("/api/upload-finish?id=" + conflict).status == 409 && Files.readString(collision).equals("keep this")
                            && get("/api/upload-status?id=" + conflict).status == 404 && parts(root) == 31
                            && init("after-failure").status == 200, "");
        } finally { running.stop(); }
        check("Остановка сервера очищает все эфемерные слоты и part", parts(root) == 0 && running.activeUploads() == 0, "");
    }

    private static void expiry(Path fixture) throws Exception {
        AtomicLong time = new AtomicLong(1000);
        ThumbnailSessions sessions = new ThumbnailSessions(time::get);
        String old = sessions.issue(null);
        time.addAndGet(1);
        String keep = sessions.issue(null);
        time.addAndGet(ThumbnailSessions.TTL_MS - 1);
        sessions.require(keep);
        check("/thumb: TTL освобождает слот, не продлевает просроченный токен",
                status(() -> { sessions.require(old); return null; }) == 403 && !old.equals(sessions.issue(old)), "");
        sessions.close();
        check("/thumb: остановка инвалидирует токены этого экземпляра",
                status(() -> { sessions.require(keep); return null; }) == 403 && status(() -> sessions.issue(null)) == 503, "");

        Path root = Files.createDirectory(fixture.resolve("legacy"));
        LegacyUploads drafts = new LegacyUploads(time::get);
        try {
            LegacyUploads.Upload oldUpload = drafts.create(root.toFile(), "old", 1, false);
            time.addAndGet(1);
            LegacyUploads.Upload keepUpload = drafts.create(root.toFile(), "keep", 2, false);
            time.addAndGet(LegacyUploads.TTL_MS - 1);
            check("Legacy TTL: убирает только просроченный part; поздний чанк не создаёт его снова",
                    status(() -> drafts.require(oldUpload.id)) == 404
                            && status(() -> drafts.append(oldUpload, 0, 1, new ByteArrayInputStream(new byte[]{1}))) == 404
                            && parts(root) == 1 && drafts.require(keepUpload.id) == keepUpload, "");
            check("Legacy: недоступная папка не расходует слот",
                    status(() -> drafts.create(root.resolve("missing").toFile(), "bad", 0, false)) >= 400 && drafts.size() == 1, "");
            int disconnected = status(() -> drafts.append(keepUpload, 0, 2, new ByteArrayInputStream(new byte[]{9})));
            check("Legacy: короткое тело оставляет подтверждённую часть доступной для докачки",
                    disconnected == 400 && keepUpload.received == 1 && parts(root) == 1
                            && drafts.append(keepUpload, 1, 1, new ByteArrayInputStream(new byte[]{10})) == 2, "");
            drafts.finish(keepUpload);
            check("Legacy: докачка после неполного запроса сохраняет байты",
                    java.util.Arrays.equals(Files.readAllBytes(root.resolve("keep")), new byte[]{9, 10}), "");
            LegacyUploads.Upload failedWrite = drafts.create(root.toFile(), "failed", 1, false);
            Files.delete(failedWrite.tmp.toPath());
            check("Legacy: ошибка записи освобождает слот",
                    status(() -> drafts.append(failedWrite, 0, 1, new ByteArrayInputStream(new byte[]{1}))) == 400
                            && drafts.size() == 0 && parts(root) == 0, "");
            drafts.create(root.toFile(), "pending", 1, false);
        } finally { drafts.close(); }
        check("Legacy: закрытый реестр не принимает новые слоты",
                status(() -> drafts.create(root.toFile(), "closed", 0, false)) == 503 && parts(root) == 0, "");
    }

    private static Resp init(String name) throws Exception {
        return post("/api/upload-init?path=/&size=0&name=" + name);
    }

    private static String field(Resp response, String name) {
        if (response.status != 200) throw new AssertionError("Unexpected HTTP " + response.status + ": " + response.text());
        return new JSONObject(response.text()).getString(name);
    }

    private static long parts(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().startsWith(".wifi-upload-")).count();
        }
    }

    private static int status(Callable<?> action) throws Exception {
        try { action.call(); return 200; }
        catch (FileServer.ApiException e) { return e.status; }
        catch (IOException e) { return 400; }
    }

    private static List<Resp> parallel(int count, IntFunction<Callable<Resp>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count), go = new CountDownLatch(1);
        try {
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<Resp> request = work.apply(i);
                futures.add(pool.submit(() -> { ready.countDown(); go.await(); return request.call(); }));
            }
            if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("Parallel requests did not become ready");
            go.countDown();
            List<Resp> results = new ArrayList<>();
            for (Future<Resp> f : futures) results.add(f.get(15, TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }
}
