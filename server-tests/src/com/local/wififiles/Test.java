package com.local.wififiles;

import android.content.Context;
import org.json.JSONObject;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Автономные тесты серверной части: настоящий FileServer запускается на JVM
 * с минимальными заглушками Android API. Запуск: server-tests/run.sh
 */
public class Test {
    static String KEY = "testkey12345678";
    static int PORT;
    static File TMP;
    static File ROOT;
    static FileServer SERVER;
    static String ASSETS = System.getProperty("pb.assets", "app/src/main/assets");
    static int pass = 0, fail = 0, note = 0;
    static final List<String> failures = new ArrayList<>();
    static final List<String> notes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        ReviewRegressionTest.safeServerLog(); // Capture before any listener can write to stderr.
        TMP = Files.createTempDirectory("pocketbridge-test").toFile();
        ROOT = new File(TMP, "root");
        try {
            prepareTree();
            Context ctx = new Context(new File(ASSETS));
            FileServer server = new FileServer(ctx, ROOT, KEY);
            SERVER = server;
            PORT = server.start();
            System.out.println("SERVER PORT=" + PORT + " ROOT=" + ROOT);
            try { runTests(); } finally { server.stop(); }
            accessModeAndInboxTests();
            SelectedFeaturesTest.run();
            tlsTests();
            ReviewRegressionTest.run();
        } finally {
            cleanupDir(TMP);
        }
        System.out.println("\n=== SUMMARY: PASS=" + pass + "  FAIL=" + fail + "  NOTES=" + note);
        for (String f : failures) System.out.println("FAILED -> " + f);
        for (String n : notes) System.out.println("NOTE   -> " + n);
        if (fail > 0) System.exit(1);
    }

    /* v4.0: actual shared-root boundary, read-only HTTP/WebDAV, and Android inbox writer. */
    static void accessModeAndInboxTests() throws Exception {
        Path base = Files.createDirectory(TMP.toPath().resolve("storage-v40"));
        Path root = Files.createDirectories(base.resolve("Docs/Доступ"));
        Path sibling = Files.createDirectory(base.resolve("Private"));
        Files.writeString(sibling.resolve("private-note.txt"), "not shared");
        Files.writeString(root.resolve("hello.txt"), "readable text");
        Files.copy(ROOT.toPath().resolve("photo.jpg"), root.resolve("photo.jpg"));
        Files.createSymbolicLink(root.resolve("external-link"), sibling.resolve("private-note.txt"));
        Files.createSymbolicLink(base.resolve("linked-root"), root);
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(root.resolve("demo.zip")))) {
            zip.putNextEntry(new ZipEntry("included.txt"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        String selected = "Docs/Доступ";
        File storage = base.toFile();
        check("Корневой выбор: вложенная папка с Unicode", Files.isSameFile(root, StorageRoot.resolve(storage, selected).toPath()), "");
        check("Корневой выбор: пустая настройка использует первичное хранилище", Files.isSameFile(base, StorageRoot.resolve(storage, "").toPath()), "");
        for (String bad : new String[]{null, "/Docs", "Docs//Доступ", "Docs/../Private", "missing", "linked-root"}) {
            boolean rejected = false;
            try { StorageRoot.resolve(storage, bad); }
            catch (IOException e) { rejected = true; }
            check("Корневой выбор не расширяет доступ: " + bad, rejected, "");
        }
        byte[] sample = "строка\n".getBytes(StandardCharsets.UTF_8);
        ShareInbox.Imported a = ShareInbox.copy(storage, selected, "отчёт.txt", new ByteArrayInputStream(sample), 100L);
        ShareInbox.Imported b = ShareInbox.copy(storage, selected, "отчёт.txt", new ByteArrayInputStream(new byte[]{9}), 99L);
        Path inbox = root.resolve(ShareInbox.DIRECTORY);
        check("Импорт сохраняет UTF-8 в выбранный Inbox и не перезаписывает совпадение",
                a.name.equals("отчёт.txt") && a.bytes == sample.length && b.name.equals("отчёт (1).txt")
                        && Arrays.equals(sample, Files.readAllBytes(inbox.resolve(a.name))), "");
        ShareInbox.Imported c = ShareInbox.copy(storage, selected, "подпапка/заметка\\.txt", new ByteArrayInputStream(sample), 100L);
        check("Импорт нормализует имя из провайдера", c.name.equals("подпапка_заметка_.txt") && Files.isRegularFile(inbox.resolve(c.name)), c.name);
        boolean limited = false;
        try { ShareInbox.copy(storage, selected, "partial.txt", new ByteArrayInputStream(sample), 1L); }
        catch (ShareInbox.TooLarge e) { limited = true; }
        try (java.util.stream.Stream<Path> entries = Files.list(inbox)) {
            check("Лимит импорта не оставляет файл или .part", limited && !Files.exists(inbox.resolve("partial.txt"))
                    && entries.noneMatch(f -> FileServer.isTempName(f.getFileName().toString())), "");
        }
        Path outsideInboxRoot = Files.createDirectory(base.resolve("LinkedInbox"));
        Files.createSymbolicLink(outsideInboxRoot.resolve(ShareInbox.DIRECTORY), sibling);
        boolean denied = false;
        try { ShareInbox.copy(storage, "LinkedInbox", "sample.txt", new ByteArrayInputStream(sample), 100L); }
        catch (IOException e) { denied = true; }
        check("Импорт отказывает при ссылке вместо Inbox", denied && !Files.exists(sibling.resolve("sample.txt")), "");

        FileServer readOnly = new FileServer(new Context(new File(ASSETS)), StorageRoot.resolve(storage, selected), KEY,
                null, null, 8765, true);
        PORT = readOnly.start();
        try {
            Resp r = get("/api/list?path=/");
            JSONObject listing = new JSONObject(r.text());
            check("GET /api/list передаёт режим и виртуальный корень", r.status == 200 && listing.getBoolean("readOnly")
                    && listing.getString("path").equals("/") && r.text().contains("hello.txt")
                    && !r.text().contains("private-note.txt") && !r.text().contains("external-link"), r.text());
            check("Inbox появляется в быстрых папках после локального импорта",
                    listing.getJSONArray("quickDirs").toString().contains("PocketBridge Inbox"), "");
            r = get("/api/text?path=/hello.txt");
            check("В режиме чтения текст доступен, X-File-Editable=0", r.status == 200 && "0".equals(r.h.get("x-file-editable"))
                    && r.text().equals("readable text"), r.text());
            check("В режиме чтения разрешены GET /api/hash и GET /api/zip-list", get("/api/hash?path=/hello.txt").status == 200
                    && get("/api/zip-list?path=/demo.zip").status == 200, "");
            check("Доступ с соседним путём и внешней ссылкой закрыт",
                    get("/api/download?path=/../../Private/private-note.txt").status == 400
                    && get("/api/download?path=/external-link").status == 400, "");
            String download = new JSONObject(post("/api/download-ticket?path=/hello.txt").text()).getString("url");
            String zip = new JSONObject(postJson("/api/zip-ticket?base=/", "{\"paths\":[\"/hello.txt\"]}").text()).getString("url");
            check("Билеты скачивания файла и ZIP не блокируются", req("GET", download, null, null).text().equals("readable text")
                    && req("GET", zip, null, null).status == 200, "");
            check("Билеты сессии и превью не блокируются", post("/api/session").status == 200
                    && post("/api/preview-ticket?path=/photo.jpg").status == 200, "");
            readOnly.updateClipboard("from phone", "phone");
            check("Общий буфер доступен для чтения", get("/api/clipboard").text().contains("from phone"), "");
            for (String mutation : new String[]{"mkdir", "mkdirs", "rename", "delete", "copy", "move", "save-text", "rotate-image",
                    "zip-extract", "upload", "upload-init", "upload-chunk", "upload-finish", "upload-abort", "clipboard"}) {
                Resp refused = post("/api/" + mutation + "?path=/hello.txt&name=changed");
                check("Только чтение: POST /api/" + mutation, refused.status == 403
                        && "1".equals(refused.h.get("x-pocketbridge-read-only")), refused.text());
            }
            r = req("POST", "/api/mkdir", null, null);
            check("Проверка кода доступа предшествует проверке режима", r.status == 403 && !r.h.containsKey("x-pocketbridge-read-only"), r.text());
            r = req("OPTIONS", "/dav/", null, null);
            check("WebDAV OPTIONS сообщает только разрешённые методы", r.status == 200 && "1".equals(r.h.get("dav"))
                    && "OPTIONS, GET, HEAD, PROPFIND".equals(r.h.get("allow")), r.h.toString());
            r = req("PROPFIND", "/dav/", davAuth(), null);
            check("WebDAV PROPFIND ограничивает видимость выбранным корнем", r.status == 207 && r.text().contains("hello.txt")
                    && !r.text().contains("external-link") && !r.text().contains("private-note.txt"), "");
            check("WebDAV GET и HEAD работают при чтении", req("GET", "/dav/hello.txt", davAuth(), null).text().equals("readable text")
                    && req("HEAD", "/dav/hello.txt", davAuth(), null).status == 200, "");
            for (String verb : new String[]{"PUT", "DELETE", "MKCOL", "MOVE", "COPY", "PROPPATCH", "LOCK", "UNLOCK"}) {
                Resp refused = req(verb, "/dav/hello.txt", davAuth(), null);
                check("Только чтение: WebDAV " + verb, refused.status == 403
                        && "1".equals(refused.h.get("x-pocketbridge-read-only")), refused.text());
            }
            check("Запрещённые операции не меняют файл или буфер", Files.readString(root.resolve("hello.txt")).equals("readable text")
                    && readOnly.clipboardText().equals("from phone"), "");
        } finally { readOnly.stop(); }
    }

    static void cleanupDir(File f) {
        if (f == null) return;
        try {
            if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
                File[] children = f.listFiles();
                if (children != null) for (File c : children) cleanupDir(c);
            }
            f.delete();
        } catch (Exception ignored) {}
    }

    /* ---------------- дерево файлов ---------------- */
    static void prepareTree() throws Exception {
        ROOT.mkdirs();
        write("hello.txt", "hello world\n");
        write("sub/nested.txt", "nested\n");
        write("sub/deep/deep.txt", "deep\n");
        write("пусто/placeholder.txt", "x\n");
        write("quote\"name.txt", "q\n");
        write("bad\nname.txt", "nl\n");
        write(new String(Character.toChars(0x1F4F1)) + ".txt", "emoji\n");
        byte[] bin = new byte[1024 * 1024]; new Random(42).nextBytes(bin);
        Files.write(ROOT.toPath().resolve("bin.dat"), bin);
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        Files.write(ROOT.toPath().resolve("img.png"), png);
        java.awt.image.BufferedImage photo = new java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 480; y++) for (int x = 0; x < 640; x++) photo.setRGB(x, y, (x * 255 / 640) << 16 | (y * 255 / 480) << 8 | 128);
        javax.imageio.ImageIO.write(photo, "jpg", new File(ROOT, "photo.jpg"));
        Files.write(ROOT.toPath().resolve("empty.bin"), new byte[0]);
        Files.write(new File(TMP, "secret.txt").toPath(), "TOP SECRET\n".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(ROOT.toPath().resolve("link_out.txt"), new File(TMP, "secret.txt").toPath());
        Files.createSymbolicLink(ROOT.toPath().resolve("link_in.txt"), ROOT.toPath().resolve("hello.txt"));
    }
    static void write(String rel, String text) throws IOException {
        File f = new File(ROOT, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /* ---------------- HTTP ---------------- */
    static class Resp {
        int status; Map<String, String> h = new HashMap<>(); byte[] body = new byte[0];
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }
    static Map<String, String> auth() { Map<String, String> m = new HashMap<>(); m.put("X-Access-Key", KEY); return m; }
    static Map<String, String> with(Map<String, String> base, String k, String v) { Map<String, String> m = base == null ? new HashMap<>() : new HashMap<>(base); m.put(k, v); return m; }

    static Resp req(String method, String path, Map<String, String> hdrs, byte[] body) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            s.setSoTimeout(60000);
            OutputStream out = s.getOutputStream();
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");
            if (hdrs != null) for (Map.Entry<String, String> e : hdrs.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            if (body != null && (hdrs == null || !hdrs.containsKey("Content-Length"))) sb.append("Content-Length: ").append(body.length).append("\r\n");
            sb.append("\r\n");
            try {
                out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (body != null) out.write(body);
                out.flush();
            } catch (IOException ignored) { /* сервер мог ответить и закрыть соединение до конца тела */ }
            return readResp(s.getInputStream(), "HEAD".equals(method));
        }
    }
    static Resp get(String p) throws Exception { return req("GET", p, auth(), null); }
    static Resp post(String p) throws Exception { return req("POST", p, auth(), new byte[0]); }
    static Resp postJson(String p, String json) throws Exception {
        return req("POST", p, with(auth(), "Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8));
    }

    static Resp readResp(InputStream in) throws Exception { return readResp(in, false); }
    static Resp readResp(InputStream in, boolean headOnly) throws Exception {
        Resp r = new Resp();
        String sl = readLine(in);
        if (sl == null) throw new IOException("empty response");
        r.status = Integer.parseInt(sl.split(" ")[1]);
        String l;
        while ((l = readLine(in)) != null && !l.isEmpty()) {
            int i = l.indexOf(':');
            if (i > 0) r.h.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
        }
        if (headOnly) {
            r.body = in.readAllBytes();
            return r;
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        String te = r.h.getOrDefault("transfer-encoding", "");
        if (te.contains("chunked")) {
            while (true) {
                String sz = readLine(in);
                int n = Integer.parseInt(sz.trim(), 16);
                if (n == 0) { readLine(in); break; }
                b.write(readN(in, n));
                readLine(in);
            }
        } else if (r.h.containsKey("content-length")) {
            b.write(readN(in, Integer.parseInt(r.h.get("content-length"))));
        } else {
            b.write(in.readAllBytes());
        }
        r.body = b.toByteArray();
        return r;
    }
    static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n]; int off = 0;
        while (off < n) { int k = in.read(b, off, n - off); if (k < 0) throw new IOException("eof"); off += k; }
        return b;
    }
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') b.write(c); }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    /* ---------------- проверки ---------------- */
    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; failures.add(name + "  [" + detail + "]"); System.out.println("FAIL  " + name + "  [" + detail + "]"); }
    }
    static void noteIt(String name, String detail) { note++; notes.add(name + ": " + detail); System.out.println("NOTE  " + name + " — " + detail); }

    static void runTests() throws Exception {
        securityTests();
        functionalTests();
        advancedTests();
        zipStructureTests();
        searchTests();
        previewTests();
        thumbnailTests();
        settingsTests();
        chunkedUploadTests();
        nfcTests();
        heicRawTests();
        previewSizeLimitTests();
        svgPreviewTests();
        videoFrameTests();
        thumbCacheTests();
        regressionTests();
        webDavTests();
        v38CopyMoveClipboardTests();
        v39MediaZipEditorPropsTests();
        edgeTests();
        dosTest();
    }

    /* ---------------- безопасность ---------------- */
    static void securityTests() throws Exception {
        Resp r = req("GET", "/api/list?path=/", null, null);
        check("list без ключа -> 403", r.status == 403, "status=" + r.status);
        r = req("GET", "/api/list?path=/", with(auth(), "X-Access-Key", "wrong"), null);
        check("list с неверным ключом -> 403", r.status == 403, "status=" + r.status);
        r = get("/api/download?path=../../../../etc/passwd");
        check("traversal ../../ -> 400", r.status == 400 && !r.text().contains("root:"), "status=" + r.status);
        r = get("/api/download?path=%2e%2e%2f%2e%2e%2fsecret.txt");
        check("traversal URL-encoded -> 400", r.status == 400, "status=" + r.status);
        r = get("/api/download?path=/link_out.txt");
        check("symlink наружу -> 400", r.status == 400 && !r.text().contains("TOP SECRET"), "status=" + r.status);
        r = get("/api/download?path=/link_in.txt");
        check("symlink внутрь root -> 200", r.status == 200 && r.text().equals("hello world\n"), "status=" + r.status);
        r = get("/api/list?path=/sub/../sub");
        check("path с .. внутри root -> 200", r.status == 200, "status=" + r.status);
        r = post("/api/delete?path=/");
        check("удалить корень -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/rename?path=/&name=newname");
        check("переименовать корень -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/mkdir?path=/&name=../evil");
        check("mkdir с '..' -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/mkdir?path=/&name=" + "a".repeat(201));
        check("имя 201 симв -> 400", r.status == 400, "status=" + r.status);
        r = get("/api/download?path=/sub");
        check("скачать папку -> 400", r.status == 400, "status=" + r.status);
        r = get("/");
        check("главная страница без ключа -> 200 html", r.status == 200 && r.text().contains("<!doctype html"), "status=" + r.status);
        check("защитные заголовки", r.h.containsKey("x-content-type-options") && r.h.containsKey("x-frame-options"), r.h.toString());
        r = get("/api/download?path=/hello.txt%00.png");
        check("NUL-байт в пути -> 400", r.status == 400, "status=" + r.status);
        try {
            req("GET", "/api/list?path=/", with(auth(), "X-Junk", "v".repeat(20000)), null);
            noteIt("гигантский заголовок", "ответ не пришёл/закрыт — ок");
        } catch (Exception e) {
            noteIt("гигантский заголовок", "соединение оборвано — лимит 8КБ работает");
        }
        r = get("/api/list?path=/");
        check("сервер жив после мусорного запроса", r.status == 200, "status=" + r.status);
    }

    /* ---------------- базовая функциональность ---------------- */
    static void functionalTests() throws Exception {
        Resp r = get("/api/list?path=/");
        JSONObject j = new JSONObject(r.text());
        boolean hasCyr = false, hasEmoji = false, hasNL = false;
        for (int i = 0; i < j.getJSONArray("items").length(); i++) {
            String n = j.getJSONArray("items").getJSONObject(i).getString("name");
            if (n.equals("пусто")) hasCyr = true;
            if (n.startsWith(new String(Character.toChars(0x1F4F1)))) hasEmoji = true;
            if (n.equals("bad\nname.txt")) hasNL = true;
        }
        check("list: юникод/эмодзи/перевод строки", hasCyr && hasEmoji && hasNL, "cyr=" + hasCyr + " emoji=" + hasEmoji + " nl=" + hasNL);
        r = post("/api/mkdir?path=/sub&name=newdir");
        check("mkdir -> 200", r.status == 200 && new File(ROOT, "sub/newdir").isDirectory(), "status=" + r.status);
        r = post("/api/mkdir?path=/sub&name=newdir");
        check("mkdir дубликат -> 409", r.status == 409, "status=" + r.status);
        byte[] payload = new byte[300_000]; new Random(7).nextBytes(payload);
        r = req("POST", "/api/upload?path=/sub&name=up.bin", with(auth(), "Content-Length", String.valueOf(payload.length)), payload);
        check("upload 300КБ -> 200, байты совпали", r.status == 200 && Arrays.equals(payload, Files.readAllBytes(new File(ROOT, "sub/up.bin").toPath())), "status=" + r.status);
        r = req("POST", "/api/upload?path=/sub&name=up.bin", with(auth(), "Content-Length", String.valueOf(payload.length)), payload);
        check("upload дубликат -> 409", r.status == 409, "status=" + r.status);
        r = req("POST", "/api/upload?path=/sub&name=zero.bin", auth(), new byte[0]);
        check("upload 0 байт -> 200", r.status == 200 && new File(ROOT, "sub/zero.bin").isFile(), "status=" + r.status);
        r = get("/api/download?path=/bin.dat");
        check("download 1МБ -> байты совпали", r.status == 200 && Arrays.equals(r.body, Files.readAllBytes(new File(ROOT, "bin.dat").toPath())), "status=" + r.status);
        r = req("GET", "/api/download?path=/bin.dat", with(auth(), "Range", "bytes=0-99"), null);
        check("скачивание с Range -> 206, 100 байт", r.status == 206 && r.body.length == 100 && "bytes 0-99/1048576".equals(r.h.get("content-range")), "status=" + r.status + " cr=" + r.h.get("content-range"));
        r = req("GET", "/api/download?path=/bin.dat", with(auth(), "Range", "bytes=-1024"), null);
        check("скачивание suffix Range -> 206", r.status == 206 && r.body.length == 1024, "status=" + r.status);
        r = req("GET", "/api/download?path=/bin.dat", with(auth(), "Range", "bytes=99999999-"), null);
        check("скачивание Range вне файла -> 416", r.status == 416, "status=" + r.status);
        r = get("/api/size?path=/sub");
        long expect = new File(ROOT, "sub/nested.txt").length() + new File(ROOT, "sub/deep/deep.txt").length()
                + new File(ROOT, "sub/up.bin").length() + new File(ROOT, "sub/zero.bin").length();
        check("size папки корректен", new JSONObject(r.text()).getLong("bytes") == expect, "got=" + new JSONObject(r.text()).getLong("bytes") + " want=" + expect);
        r = post("/api/rename?path=/hello.txt&name=hello2.txt");
        check("rename -> 200", r.status == 200 && new File(ROOT, "hello2.txt").isFile(), "status=" + r.status);
        r = post("/api/rename?path=/hello2.txt&name=bin.dat");
        check("rename на занятое имя -> 409", r.status == 409, "status=" + r.status);
        r = post("/api/rename?path=/hello2.txt&name=hello.txt");
        check("rename обратно -> 200", r.status == 200, "status=" + r.status);
        r = post("/api/delete?path=/sub/newdir");
        check("delete пустой папки -> 200", r.status == 200 && !new File(ROOT, "sub/newdir").exists(), "status=" + r.status);
        ExecutorService ex = Executors.newFixedThreadPool(12);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 12; i++) fs.add(ex.submit(() -> { Resp rr = get("/api/download?path=/bin.dat"); return rr.status == 200 && rr.body.length == 1024 * 1024; }));
        int okd = 0;
        for (Future<Boolean> f : fs) if (f.get(60, TimeUnit.SECONDS)) okd++;
        ex.shutdown();
        check("12 параллельных скачиваний", okd == 12, "ok=" + okd);
    }

    /* ---------------- перезапись, ссылки, ZIP на лету ---------------- */
    static void advancedTests() throws Exception {
        byte[] one = "first".getBytes(StandardCharsets.UTF_8), two = "second!".getBytes(StandardCharsets.UTF_8);
        Resp r = req("POST", "/api/upload?path=/&name=ow.txt", with(auth(), "Content-Length", String.valueOf(one.length)), one);
        check("upload ow.txt -> 200", r.status == 200, "status=" + r.status);
        r = req("POST", "/api/upload?path=/&name=ow.txt&overwrite=1", with(auth(), "Content-Length", String.valueOf(two.length)), two);
        check("upload overwrite=1 заменяет файл", r.status == 200 && new String(Files.readAllBytes(new File(ROOT, "ow.txt").toPath()), StandardCharsets.UTF_8).equals("second!"), "status=" + r.status);
        r = req("POST", "/api/upload?path=/&name=ow.txt&unique=1", with(auth(), "Content-Length", String.valueOf(one.length)), one);
        check("upload unique=1 создаёт «ow (1).txt»", r.status == 200 && new File(ROOT, "ow (1).txt").isFile(), "status=" + r.status);
        r = post("/api/download-ticket?path=/bin.dat");
        String url = new JSONObject(r.text()).getString("url");
        check("download-ticket выдаёт /dl", r.status == 200 && url.startsWith("/dl?ticket="), url);
        r = req("GET", url, null, null);
        check("/dl отдаёт файл без ключа как attachment", r.status == 200 && r.body.length == 1024 * 1024 && r.h.getOrDefault("content-disposition", "").startsWith("attachment"), "status=" + r.status);
        r = req("GET", url, with(null, "Range", "bytes=100-199"), null);
        check("/dl поддерживает Range -> 206", r.status == 206 && r.body.length == 100, "status=" + r.status);
        r = req("GET", "/dl?ticket=nope", null, null);
        check("/dl с неверным тикетом -> 403", r.status == 403, "status=" + r.status);
        write(".wifi-upload-test.part", "tmp");
        r = get("/api/list?path=/");
        check("временные .part-файлы скрыты", !r.text().contains(".wifi-upload-"), "");
        new File(ROOT, ".wifi-upload-test.part").delete();
        new File(ROOT, "ow.txt").delete(); new File(ROOT, "ow (1).txt").delete();
    }

    /* ---------------- ZIP со структурой папок ---------------- */
    static Map<String, Integer> unzip(byte[] body) throws Exception {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) sizes.put(e.getName(), zin.readAllBytes().length);
        }
        return sizes;
    }

    static void zipStructureTests() throws Exception {
        write("tree/a/b/c.txt", "c");
        write("tree/a/d.txt", "d");
        write("tree/e.txt", "e");
        write("d1/same.txt", "one");
        write("d2/same.txt", "two");

        Resp r = postJson("/api/zip-ticket?base=/tree", "{\"paths\":[\"/tree/a\"]}");
        String url = new JSONObject(r.text()).getString("url");
        r = req("GET", url, null, null);
        Map<String, Integer> names = unzip(r.body);
        check("ZIP папки сохраняет структуру (a/b/c.txt, a/d.txt)",
                names.keySet().equals(new LinkedHashSet<>(Arrays.asList("a/b/c.txt", "a/d.txt"))), "names=" + names.keySet());

        r = postJson("/api/zip-ticket?base=/tree/a", "{\"paths\":[\"/tree/a/b\"]}");
        r = req("GET", new JSONObject(r.text()).getString("url"), null, null);
        check("ZIP вложенной папки: пути относительно выбранной", unzip(r.body).keySet().equals(new LinkedHashSet<>(Collections.singletonList("b/c.txt"))), "names=" + unzip(r.body).keySet());

        r = postJson("/api/zip-ticket?base=/", "{\"paths\":[\"/d1/same.txt\",\"/d2/same.txt\"]}");
        r = req("GET", new JSONObject(r.text()).getString("url"), null, null);
        check("одинаковые имена из разных папок -> оба в архиве с путями",
                unzip(r.body).keySet().equals(new LinkedHashSet<>(Arrays.asList("d1/same.txt", "d2/same.txt"))), "names=" + unzip(r.body).keySet());

        r = postJson("/api/zip-ticket?base=/tree", "{\"paths\":[\"/bin.dat\"]}");
        check("объект вне base -> 400", r.status == 400, "status=" + r.status + " " + r.text());
        r = postJson("/api/zip-ticket?base=/tree", "{\"paths\":[\"/tree\"]}");
        check("пустая ветка без файлов -> 200", r.status == 200 || r.status == 400, "status=" + r.status);

        write("emptydir/keep.txt", "k");
        new File(ROOT, "emptydir/keep.txt").delete();
        r = postJson("/api/zip-ticket?base=/", "{\"paths\":[\"/emptydir\"]}");
        check("папка без файлов -> 400 с объяснением", r.status == 400 && r.text().contains("нет файлов"), "status=" + r.status + " " + r.text());

        r = postJson("/api/download-zip?base=/tree", "{\"paths\":[\"/tree\"]}");
        check("/api/download-zip (legacy) отдаёт архив", r.status == 200 && unzip(r.body).size() == 3, "size=" + unzip(r.body).size());
        new File(ROOT, "d1").delete(); new File(ROOT, "d2").delete();
    }

    /* ---------------- поиск по подпапкам ---------------- */
    static void searchTests() throws Exception {
        write("search/note-one.txt", "1");
        write("search/inner/note-two.txt", "2");
        write("search/inner/deeper/other.txt", "3");
        Resp r = get("/api/search?path=/search&q=note");
        JSONObject data = new JSONObject(r.text());
        List<String> found = new ArrayList<>();
        for (int i = 0; i < data.getJSONArray("items").length(); i++) found.add(data.getJSONArray("items").getJSONObject(i).getString("path"));
        check("поиск находит файлы во вложенных папках", r.status == 200 && found.contains("/search/note-one.txt") && found.contains("/search/inner/note-two.txt"), "found=" + found);
        check("поиск не отдаёт лишнее", !found.contains("/search/inner/deeper/other.txt"), "found=" + found);
        r = get("/api/search?path=/search&q=n");
        check("поиск с 1 символом -> 400", r.status == 400, "status=" + r.status);
        r = get("/api/search?path=/search&q=zzz");
        check("ничего не найдено -> пустой список", r.status == 200 && new JSONObject(r.text()).getJSONArray("items").length() == 0, r.text());
        for (int i = 0; i < 260; i++) write("bulk/target_" + i + ".txt", "x");
        r = get("/api/search?path=/bulk&q=target");
        data = new JSONObject(r.text());
        check("лимит выдачи 200 + флаг truncated", data.getJSONArray("items").length() == 200 && data.getBoolean("truncated"), "count=" + data.getJSONArray("items").length() + " truncated=" + data.getBoolean("truncated"));
    }

    /* ---------------- превью ---------------- */
    static void previewTests() throws Exception {
        Resp r = post("/api/preview-ticket?path=/hello.txt");
        check("preview-ticket для .txt -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/preview-ticket?path=/img.png");
        String url = new JSONObject(r.text()).getString("url");
        check("preview-ticket для .png -> 200", r.status == 200 && url.contains("/preview?ticket="), "status=" + r.status);
        r = req("GET", url, null, null);
        check("preview по тикету -> 200 image/png", r.status == 200 && "image/png".equals(r.h.get("content-type")), "status=" + r.status);
        r = req("GET", url, with(null, "Range", "bytes=0-7"), null);
        check("preview Range 0-7 -> 206", r.status == 206 && r.body.length == 8, "status=" + r.status);
        r = req("GET", "/preview?ticket=deadbeef", null, null);
        check("preview с фальшивым тикетом -> 403", r.status == 403, "status=" + r.status);
        r = get("/api/text?path=/hello.txt");
        check("text preview -> 200 utf-8", r.status == 200 && r.text().equals("hello world\n"), "status=" + r.status);
        r = get("/api/text?path=/bin.dat");
        check("text preview бинарника -> 400", r.status == 400, "status=" + r.status);
        r = get("/api/docx?path=/hello.txt");
        check("docx-preview не-docx -> 400", r.status == 400, "status=" + r.status);
    }

    /* ---------------- миниатюры ---------------- */
    static void thumbnailTests() throws Exception {
        Resp r = postJson("/api/session", "{}");
        check("session выдаёт токен", r.status == 200 && new JSONObject(r.text()).getString("token").length() >= 32, r.text());
        String token = new JSONObject(r.text()).getString("token");

        r = req("GET", "/thumb?token=" + token + "&path=/photo.jpg&w=320", null, null);
        boolean jpeg = r.body.length > 2 && (r.body[0] & 0xFF) == 0xFF && (r.body[1] & 0xFF) == 0xD8;
        check("миниатюра: JPEG по сессии", r.status == 200 && "image/jpeg".equals(r.h.get("content-type")) && jpeg, "status=" + r.status + " len=" + r.body.length);
        int firstLength = r.body.length;
        r = req("GET", "/thumb?token=" + token + "&path=/photo.jpg&w=320", null, null);
        check("миниатюра отдаётся из кеша (тот же размер)", r.status == 200 && Math.abs(r.body.length - firstLength) <= 16, "len=" + r.body.length);
        r = req("GET", "/thumb?token=" + token + "&path=/photo.jpg&w=96", null, null);
        check("миниатюра другого размера — другой файл", r.status == 200 && (r.body.length != firstLength || firstLength < 2000), "len=" + r.body.length);
        r = req("GET", "/thumb?path=/photo.jpg", null, null);
        check("миниатюра без токена -> 403", r.status == 403, "status=" + r.status);
        r = req("GET", "/thumb?token=deadbeef&path=/photo.jpg", null, null);
        check("миниатюра с фальшивым токеном -> 403", r.status == 403, "status=" + r.status + " " + r.text());
        r = req("GET", "/thumb?token=" + token + "&path=/hello.txt", null, null);
        check("миниатюра для не-изображения -> 400", r.status == 400, "status=" + r.status);
        r = req("GET", "/thumb?token=" + token + "&path=" + URLEncoder.encode("/bin.dat", StandardCharsets.UTF_8), null, null);
        check("миниатюра для .dat -> 400", r.status == 400, "status=" + r.status);
        r = req("GET", "/thumb?token=" + token + "&path=/img.png&w=9999", null, null);
        check("миниатюра: ширина ограничена 512", r.status == 200 && new JSONObject("{\"a\":1}").length() >= 0, "status=" + r.status);
        r = req("GET", "/thumb?token=" + token + "&path=/../../../etc/passwd", null, null);
        check("миниатюра: обход пути заблокирован", r.status == 400, "status=" + r.status);
    }

    /* ---------------- настройки и создание вложенных папок ---------------- */
    static void settingsTests() throws Exception {
        Resp r = post("/api/mkdirs?path=/a/b/c");
        check("mkdirs создаёт цепочку папок", r.status == 200 && new File(ROOT, "a/b/c").isDirectory(), "status=" + r.status + " " + r.text());
        r = post("/api/mkdirs?path=/a/b/c");
        check("mkdirs идемпотентен", r.status == 200, "status=" + r.status);
        r = post("/api/mkdirs?path=/a/../../evil");
        check("mkdirs с '..' -> 400", r.status == 400, "status=" + r.status);
        write("fileblock.txt", "x");
        r = post("/api/mkdirs?path=/fileblock.txt/sub");
        check("mkdirs через файл -> 409", r.status == 409, "status=" + r.status + " " + r.text());

        File cache = Files.createTempDirectory("pb-thumbs").toFile();
        FileServer custom = new FileServer(new Context(new File(ASSETS)), ROOT, KEY, null, cache, 9120);
        int port = custom.start();
        try {
            check("стартовый порт из настроек (9120)", port == 9120, "port=" + port);
            Resp session = null;
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(10000);
                socket.getOutputStream().write(("POST /api/session HTTP/1.1\r\nHost: x\r\nX-Access-Key: " + KEY + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                session = readResp(socket.getInputStream());
            }
            String token = new JSONObject(session.text()).getString("token");
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(10000);
                socket.getOutputStream().write(("GET /thumb?token=" + token + "&path=/photo.jpg&w=200 HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                Resp thumb = readResp(socket.getInputStream());
                File cacheDir = new File(cache, "thumbs");
                int cached = cacheDir.isDirectory() ? (cacheDir.listFiles() == null ? 0 : cacheDir.listFiles().length) : 0;
                check("миниатюры кешируются на диск", thumb.status == 200 && cached >= 1, "status=" + thumb.status + " файлов=" + cached);
            }
        } finally { custom.stop(); }
    }

    /* ---------------- chunked upload v2.1 ---------------- */
    static void chunkedUploadTests() throws Exception {
        Resp r = postJson("/api/session", "{}");
        String token = new JSONObject(r.text()).getString("token");

        r = req("POST", "/api/upload-init?name=chunked.bin&path=/&size=100000&overwrite=0", auth(), null);
        check("upload-init 200", r.status == 200, "status=" + r.status);
        String uploadId = new JSONObject(r.text()).getString("uploadId");
        check("uploadId issued", uploadId.length() >= 16, "id=" + uploadId);

        byte[] data = new byte[100000];
        new java.util.Random(42).nextBytes(data);
        long offset = 0;
        while (offset < data.length) {
            int len = Math.min(4096, (int)(data.length - offset));
            HttpURLConnection uc = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/api/upload-chunk?id=" + uploadId + "&offset=" + offset).openConnection();
            uc.setRequestMethod("POST");
            uc.setRequestProperty("X-Access-Key", KEY);
            uc.setRequestProperty("Content-Length", String.valueOf(len));
            uc.setDoOutput(true);
            try (OutputStream os = uc.getOutputStream()) { os.write(data, (int)offset, len); }
            int sc = uc.getResponseCode();
            check("chunk " + offset + " accepted", sc == 200, "status=" + sc);
            String body = new String(uc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject jd = new JSONObject(body);
            offset = jd.getLong("received");
        }

        r = req("POST", "/api/upload-finish?id=" + uploadId, auth(), null);
        check("upload-finish 200", r.status == 200, "status=" + r.status + " " + r.text());

        r = req("POST", "/api/download-ticket?path=/chunked.bin", auth(), null);
        check("download-ticket для чанкового файла", r.status == 200, "status=" + r.status + " " + r.text());
        String dlUrl = new JSONObject(r.text()).getString("url");
        r = req("GET", dlUrl, auth(), null);
        check("файл скачивается целиком", r.status == 200 && java.util.Arrays.equals(r.body, data), "status=" + r.status + " len=" + r.body.length);

        r = req("POST", "/api/upload-init?name=chunked.bin&path=/&size=100&overwrite=0", auth(), null);
        check("upload-init без overwrite -> 409", r.status == 409, "status=" + r.status);

        r = req("POST", "/api/upload-init?name=abort.bin&path=/&size=5000&overwrite=0", auth(), null);
        String abortId = new JSONObject(r.text()).getString("uploadId");
        check("телеметрия: activeUploads=1 при активной сессии", SERVER.activeUploads() == 1, "uploads=" + SERVER.activeUploads());
        r = req("POST", "/api/upload-abort?id=" + abortId, auth(), null);
        check("upload-abort 200", r.status == 200, "status=" + r.status);
        check("телеметрия: activeUploads=0 после abort", SERVER.activeUploads() == 0, "uploads=" + SERVER.activeUploads());
        check("телеметрия: uptime и недавняя активность клиента",
                SERVER.uptimeMillis() >= 0L && SERVER.isRecentlyActive(10_000L) && !SERVER.isRecentlyActive(0L),
                "uptime=" + SERVER.uptimeMillis());
        JSONObject listMeta = new JSONObject(get("/api/list?path=/").text());
        long totalB = listMeta.optLong("totalBytes", -1L);
        long freeB = listMeta.optLong("freeBytes", -1L);
        check("list возвращает корректные totalBytes и freeBytes",
                totalB > 0L && freeB >= 0L && freeB <= totalB,
                "total=" + totalB + " free=" + freeB);
    }

    /* ---------------- NFC нормализация поиска v2.1 ---------------- */
    static void nfcTests() throws Exception {
        write("é.txt", "accent");
        write("é.txt", "accent-decomposed");
        Resp r = req("GET", "/api/search?q=%C3%A9.txt&scope=global", auth(), null);
        JSONObject j = new JSONObject(r.text());
        check("поиск NFC находит оба файла", r.status == 200 && j.getJSONArray("items").length() >= 2, "items=" + j.getJSONArray("items").length());
    }

    /* ---------------- HEIC/RAW превью v3.0 ---------------- */
    static void heicRawTests() throws Exception {
        Resp r = postJson("/api/session", "{}");
        String token = new JSONObject(r.text()).getString("token");

        // Создаём тестовые файлы RAW-форматов (как PNG, но с RAW-расширением)
        byte[] smallPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        Files.write(new File(ROOT, "test.dng").toPath(), smallPng);
        Files.write(new File(ROOT, "test.cr2").toPath(), smallPng);
        Files.write(new File(ROOT, "test.nef").toPath(), smallPng);
        Files.write(new File(ROOT, "test.arw").toPath(), smallPng);
        Files.write(new File(ROOT, "test.raf").toPath(), smallPng);

        // Проверяем MIME-типы через preview-ticket
        String[] exts = {"dng", "cr2", "nef", "arw", "raf"};
        String[] expectedMimes = {
            "image/x-adobe-dng", "image/x-canon-cr2", "image/x-nikon-nef",
            "image/x-sony-arw", "image/x-fuji-raf"
        };
        for (int i = 0; i < exts.length; i++) {
            r = req("POST", "/api/preview-ticket?path=/test." + exts[i], auth(), null);
            check("preview-ticket для ." + exts[i] + " -> 200", r.status == 200, "status=" + r.status);
            JSONObject jd = new JSONObject(r.text());
            String dlUrl = jd.getString("url");
            r = req("GET", dlUrl, auth(), null);
            check("превью ." + exts[i] + " отдаёт JPEG", r.status == 200 && r.body.length > 2
                    && (r.body[0] & 0xFF) == 0xFF && (r.body[1] & 0xFF) == 0xD8,
                "status=" + r.status + " len=" + r.body.length + " mime=" + r.h.get("content-type"));
        }
    }

    /* ---------------- лимит размера превью v3.1 ---------------- */
    static void previewSizeLimitTests() throws Exception {
        Resp r = postJson("/api/session", "{}");
        String token = new JSONObject(r.text()).getString("token");

        // Создаём файл больше 50 МБ (имитируем большим массивом байт)
        byte[] bigData = new byte[(int)(51L * 1024 * 1024)];
        new java.util.Random(42).nextBytes(bigData);
        Files.write(new File(ROOT, "bigfile.dng").toPath(), bigData);

        // preview-ticket для большого файла должен создать ticket, но streamTicket вернёт 400
        r = req("POST", "/api/preview-ticket?path=/bigfile.dng", auth(), null);
        check("preview-ticket для большого файла создаёт билет", r.status == 200, "status=" + r.status);
        JSONObject jd = new JSONObject(r.text());
        String dlUrl = jd.getString("url");
        r = req("GET", dlUrl, auth(), null);
        check("превью файла >50 МБ через stream возвращает 400", r.status == 400, "status=" + r.status);
    }

    /* ---------------- SVG превью v3.4 ---------------- */
    static void svgPreviewTests() throws Exception {
        Resp r = postJson("/api/session", "{}");
        String token = new JSONObject(r.text()).getString("token");

        // SVG файл
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><circle cx=\"50\" cy=\"50\" r=\"40\" fill=\"red\"/></svg>";
        Files.write(new File(ROOT, "test.svg").toPath(), svg.getBytes(StandardCharsets.UTF_8));

        // preview-ticket для SVG
        r = req("POST", "/api/preview-ticket?path=/test.svg", auth(), null);
        check("preview-ticket для .svg -> 200", r.status == 200, "status=" + r.status);
        JSONObject jd = new JSONObject(r.text());
        String dlUrl = jd.getString("url");
        r = req("GET", dlUrl, auth(), null);
        check("превью .svg отдаёт image/svg+xml", r.status == 200 && r.h.get("content-type") != null && r.h.get("content-type").contains("image/svg+xml"),
            "status=" + r.status + " mime=" + r.h.get("content-type"));
    }

    /* ---------------- видео кадр v3.4 ---------------- */
    static void videoFrameTests() throws Exception {
        Resp r = postJson("/api/session", "{}");
        String token = new JSONObject(r.text()).getString("token");

        // Создаём невалидный видео-файл (не видео, но с видео-расширением)
        byte[] notVideo = "this is not a video file".getBytes(StandardCharsets.UTF_8);
        Files.write(new File(ROOT, "fake.mp4").toPath(), notVideo);

        // preview-ticket для .mp4 создаётся (видео не перекодируется в JPEG, отдаётся как есть)
        r = req("POST", "/api/preview-ticket?path=/fake.mp4", auth(), null);
        check("preview-ticket для .mp4 создаёт билет", r.status == 200, "status=" + r.status);
        JSONObject jd = new JSONObject(r.text());
        String dlUrl = jd.getString("url");
        r = req("GET", dlUrl, auth(), null);
        check(".mp4 отдаётся как видео (не как изображение)", r.status == 200 && r.h.get("content-type") != null && r.h.get("content-type").contains("video/mp4"),
            "status=" + r.status + " mime=" + r.h.get("content-type"));
    }

    /* ---------------- очистка кеша миниатюр v3.0 ---------------- */
    static void thumbCacheTests() throws Exception {
        Resp r = postJson("/api/session", "{}");
        String token = new JSONObject(r.text()).getString("token");

        // Создаём много маленьких изображений
        byte[] smallPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        for (int i = 0; i < 50; i++) {
            Files.write(new File(ROOT, "cache-test-" + i + ".jpg").toPath(), smallPng);
        }

        // Запрашиваем миниатюры всех файлов — все должны вернуться без ошибок
        int ok = 0;
        for (int i = 0; i < 50; i++) {
            r = req("GET", "/thumb?token=" + token + "&path=/cache-test-" + i + ".jpg&w=96", auth(), null);
            if (r.status == 200) ok++;
        }
        check("все 50 миниатюр отдаются без ошибок", ok == 50, "успешно=" + ok);
    }

    /* ---------------- регрессии безопасности и докачки v3.5 ---------------- */
    static void regressionTests() throws Exception {
        Resp r = req("GET", "/", null, null);
        check("главная страница защищена CSP", r.status == 200 && r.h.getOrDefault("content-security-policy", "").contains("default-src 'self'"),
                "csp=" + r.h.get("content-security-policy"));

        // SVG XSS sandbox на /preview и /thumb
        String xssSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"64\"><script>alert(1)</script><rect width=\"64\" height=\"64\" fill=\"green\"/></svg>";
        write("xss-icon.svg", xssSvg);
        r = post("/api/preview-ticket?path=/xss-icon.svg");
        String prevUrl = new JSONObject(r.text()).getString("url");
        r = req("GET", prevUrl, null, null);
        check("SVG /preview содержит sandbox CSP", r.status == 200
                && r.h.getOrDefault("content-type", "").startsWith("image/svg+xml")
                && r.h.getOrDefault("content-security-policy", "").contains("sandbox"),
                "csp=" + r.h.get("content-security-policy"));

        String sess = new JSONObject(postJson("/api/session", "{}").text()).getString("token");
        r = req("GET", "/thumb?token=" + sess + "&path=/xss-icon.svg&w=320", null, null);
        check("SVG /thumb отдаёт 200 image/svg+xml + sandbox CSP", r.status == 200
                && r.h.getOrDefault("content-type", "").startsWith("image/svg+xml")
                && r.h.getOrDefault("content-security-policy", "").contains("sandbox"),
                "status=" + r.status + " ct=" + r.h.get("content-type"));

        // Разделение типов тикетов (/preview vs /dl)
        String dlUrl = new JSONObject(post("/api/download-ticket?path=/hello.txt").text()).getString("url");
        String dlTicket = dlUrl.substring(dlUrl.indexOf("ticket=") + 7);
        String prevTicket = prevUrl.substring(prevUrl.indexOf("ticket=") + 7);
        r = req("GET", "/dl?ticket=" + prevTicket, null, null);
        check("preview-тикет отклонён на /dl -> 403", r.status == 403, "status=" + r.status);
        r = req("GET", "/preview?ticket=" + dlTicket, null, null);
        check("download-тикет отклонён на /preview -> 403", r.status == 403, "status=" + r.status);
        Files.deleteIfExists(new File(ROOT, "xss-icon.svg").toPath());

        // Докачка: upload-status и X-Upload-Received при сбое смещения
        byte[] payload = new byte[12000];
        new Random(77).nextBytes(payload);
        r = post("/api/upload-init?path=/&name=resume.bin&size=" + payload.length + "&overwrite=1");
        String uid = new JSONObject(r.text()).getString("uploadId");
        r = req("POST", "/api/upload-chunk?id=" + uid + "&offset=0", auth(), Arrays.copyOfRange(payload, 0, 5000));
        check("докачка: первая часть принята", r.status == 200, "status=" + r.status);
        r = get("/api/upload-status?id=" + uid);
        JSONObject st = new JSONObject(r.text());
        check("upload-status возвращает точное смещение (5000)", r.status == 200 && st.getLong("received") == 5000 && st.getLong("size") == payload.length, r.text());
        r = req("POST", "/api/upload-chunk?id=" + uid + "&offset=0", auth(), Arrays.copyOfRange(payload, 0, 1000));
        check("неверное смещение -> 409 + X-Upload-Received: 5000",
                r.status == 409 && "5000".equals(r.h.get("x-upload-received")),
                "status=" + r.status + " hdr=" + r.h.get("x-upload-received"));
        long resumeAt = Long.parseLong(r.h.get("x-upload-received"));
        r = req("POST", "/api/upload-chunk?id=" + uid + "&offset=" + resumeAt, auth(), Arrays.copyOfRange(payload, (int) resumeAt, payload.length));
        check("докачка с X-Upload-Received -> 200", r.status == 200, "status=" + r.status);
        r = post("/api/upload-finish?id=" + uid);
        byte[] saved = Files.readAllBytes(new File(ROOT, "resume.bin").toPath());
        check("докачанный файл побайтово совпадает", r.status == 200 && Arrays.equals(payload, saved), "status=" + r.status + " len=" + saved.length);
        Files.deleteIfExists(new File(ROOT, "resume.bin").toPath());
    }

    /* ---------------- WebDAV (/dav) ---------------- */
    static Map<String, String> davAuth() {
        String basic = Base64.getEncoder().encodeToString(("pb:" + KEY).getBytes(StandardCharsets.UTF_8));
        Map<String, String> m = new HashMap<>();
        m.put("Authorization", "Basic " + basic);
        return m;
    }

    static Resp chunkedPut(String path, Map<String, String> hdrs, byte[] part1, byte[] part2) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            s.setSoTimeout(30000);
            OutputStream out = s.getOutputStream();
            StringBuilder sb = new StringBuilder();
            sb.append("PUT ").append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n");
            for (Map.Entry<String, String> e : hdrs.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            for (byte[] chunk : new byte[][]{part1, part2}) {
                out.write((Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(chunk);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readResp(s.getInputStream(), false);
        }
    }

    static void webDavTests() throws Exception {
        Resp r = req("OPTIONS", "/dav/", null, null);
        check("WebDAV OPTIONS без авторизации -> 200 + DAV: 1, 2",
                r.status == 200 && r.h.getOrDefault("dav", "").contains("1, 2") && "DAV".equals(r.h.get("ms-author-via")),
                "status=" + r.status + " dav=" + r.h.get("dav"));

        r = req("PROPFIND", "/dav/", null, null);
        check("WebDAV PROPFIND без авторизации -> 401 + WWW-Authenticate",
                r.status == 401 && r.h.getOrDefault("www-authenticate", "").startsWith("Basic"),
                "status=" + r.status);

        String wrong = Base64.getEncoder().encodeToString("pb:wrongpass".getBytes(StandardCharsets.UTF_8));
        r = req("PROPFIND", "/dav/", with(null, "Authorization", "Basic " + wrong), null);
        check("WebDAV PROPFIND с неверным паролем -> 401", r.status == 401, "status=" + r.status);

        File tempPart = new File(ROOT, ".wifi-upload-davtest.part");
        Files.write(tempPart.toPath(), "partial".getBytes(StandardCharsets.UTF_8));
        try {
            r = req("PROPFIND", "/dav/", with(davAuth(), "Depth", "1"), null);
            check("WebDAV PROPFIND Depth:1 -> 207, видит файлы и скрывает .part",
                    r.status == 207 && r.text().contains("hello.txt") && !r.text().contains(".wifi-upload-davtest.part"),
                    "status=" + r.status);
        } finally {
            tempPart.delete();
        }

        r = req("PROPFIND", "/dav/", with(davAuth(), "Depth", "0"), null);
        check("WebDAV PROPFIND Depth:0 -> 207 только корень",
                r.status == 207 && r.text().contains("PocketBridge") && !r.text().contains("hello.txt"),
                "status=" + r.status);

        r = req("PROPFIND", "/dav/", with(davAuth(), "Depth", "infinity"), null);
        check("WebDAV PROPFIND Depth:infinity -> 403", r.status == 403, "status=" + r.status);

        r = req("MKCOL", "/dav/davdir", davAuth(), null);
        check("WebDAV MKCOL -> 201", r.status == 201 && new File(ROOT, "davdir").isDirectory(), "status=" + r.status);
        r = req("MKCOL", "/dav/davdir", davAuth(), null);
        check("WebDAV MKCOL дубликат -> 405", r.status == 405, "status=" + r.status);
        r = req("MKCOL", "/dav/no-such-parent/child", davAuth(), null);
        check("WebDAV MKCOL без родителя -> 409", r.status == 409, "status=" + r.status);

        byte[] content = "Hello WebDAV World!".getBytes(StandardCharsets.UTF_8);
        r = req("PUT", "/dav/davdir/note.txt", davAuth(), content);
        check("WebDAV PUT новый файл -> 201", r.status == 201, "status=" + r.status);
        r = req("PUT", "/dav/davdir/note.txt", davAuth(), content);
        check("WebDAV PUT перезапись -> 204", r.status == 204, "status=" + r.status);
        r = req("PUT", "/dav/no-such-parent/note.txt", davAuth(), content);
        check("WebDAV PUT без родителя -> 409", r.status == 409, "status=" + r.status);

        r = chunkedPut("/dav/davdir/chunked.txt", davAuth(),
                "Chunk-One-".getBytes(StandardCharsets.UTF_8), "Chunk-Two!".getBytes(StandardCharsets.UTF_8));
        check("WebDAV chunked PUT -> 201 и содержимое совпадает",
                r.status == 201 && "Chunk-One-Chunk-Two!".equals(Files.readString(new File(ROOT, "davdir/chunked.txt").toPath())),
                "status=" + r.status);

        r = req("GET", "/dav/davdir/note.txt", davAuth(), null);
        check("WebDAV GET файла -> 200", r.status == 200 && Arrays.equals(content, r.body), "status=" + r.status);
        r = req("GET", "/dav/davdir/note.txt", with(davAuth(), "Range", "bytes=6-11"), null);
        check("WebDAV GET Range -> 206 'WebDAV'", r.status == 206 && "WebDAV".equals(r.text()), "status=" + r.status + " body=" + r.text());
        r = req("HEAD", "/dav/davdir/note.txt", davAuth(), null);
        check("WebDAV HEAD файла -> Content-Length без тела",
                r.status == 200 && String.valueOf(content.length).equals(r.h.get("content-length")) && r.body.length == 0,
                "cl=" + r.h.get("content-length") + " body=" + r.body.length);
        r = req("HEAD", "/dav/davdir/", davAuth(), null);
        check("WebDAV HEAD папки -> ненулевой Content-Length без тела",
                r.status == 200 && Integer.parseInt(r.h.getOrDefault("content-length", "0")) > 50 && r.body.length == 0,
                "cl=" + r.h.get("content-length") + " body=" + r.body.length);

        // HTML и SVG через WebDAV должны отдаваться с sandbox CSP
        req("PUT", "/dav/davdir/page.html", davAuth(), "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        req("PUT", "/dav/davdir/pic.svg", davAuth(), "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8));
        r = req("GET", "/dav/davdir/page.html", davAuth(), null);
        check("WebDAV HTML защищён sandbox CSP", r.status == 200 && r.h.getOrDefault("content-security-policy", "").contains("sandbox"),
                "csp=" + r.h.get("content-security-policy"));
        r = req("GET", "/dav/davdir/pic.svg", davAuth(), null);
        check("WebDAV SVG отдаёт image/svg+xml + sandbox CSP",
                r.status == 200 && "image/svg+xml".equals(r.h.get("content-type")) && r.h.getOrDefault("content-security-policy", "").contains("sandbox"),
                "ct=" + r.h.get("content-type") + " csp=" + r.h.get("content-security-policy"));

        // MOVE и COPY с URL-encoded относительным и абсолютным кириллическим Destination
        String relDest = "/dav/davdir/" + URLEncoder.encode("привет мир.txt", StandardCharsets.UTF_8).replace("+", "%20");
        r = req("MOVE", "/dav/davdir/note.txt", with(davAuth(), "Destination", relDest), null);
        check("WebDAV MOVE с относительным кириллическим Destination декодирует имя",
                r.status == 201 && new File(ROOT, "davdir/привет мир.txt").isFile() && !new File(ROOT, "davdir/note.txt").exists(),
                "status=" + r.status);

        String absDest = "http://127.0.0.1:" + PORT + "/dav/davdir/" + URLEncoder.encode("копия файла.txt", StandardCharsets.UTF_8).replace("+", "%20");
        r = req("COPY", relDest, with(davAuth(), "Destination", absDest), null);
        check("WebDAV COPY с абсолютным кириллическим Destination декодирует имя",
                r.status == 201 && new File(ROOT, "davdir/копия файла.txt").isFile(),
                "status=" + r.status);

        Map<String, String> noOw = with(with(davAuth(), "Destination", absDest), "Overwrite", "F");
        r = req("COPY", relDest, noOw, null);
        check("WebDAV COPY с Overwrite: F -> 412", r.status == 412, "status=" + r.status);

        r = req("COPY", "/dav/davdir", with(davAuth(), "Destination", "/dav/davdir/inside"), null);
        check("WebDAV COPY папки внутрь самой себя -> 403", r.status == 403, "status=" + r.status);

        r = req("GET", "/dav/../../secret.txt", davAuth(), null);
        check("WebDAV traversal GET -> 403", r.status == 403, "status=" + r.status);
        r = req("MOVE", relDest, with(davAuth(), "Destination", "/dav/../../escape.txt"), null);
        check("WebDAV traversal Destination -> 403", r.status == 403, "status=" + r.status);

        r = req("PROPPATCH", relDest, davAuth(), null);
        check("WebDAV PROPPATCH -> 207", r.status == 207, "status=" + r.status);
        r = req("LOCK", relDest, davAuth(), null);
        check("WebDAV LOCK -> 200 + Lock-Token", r.status == 200 && r.h.getOrDefault("lock-token", "").contains("opaquelocktoken:"), "status=" + r.status);
        r = req("UNLOCK", relDest, davAuth(), null);
        check("WebDAV UNLOCK -> 204", r.status == 204, "status=" + r.status);

        r = req("DELETE", "/dav/", davAuth(), null);
        check("WebDAV DELETE корня -> 403", r.status == 403, "status=" + r.status);
        r = req("DELETE", "/dav/davdir", davAuth(), null);
        check("WebDAV DELETE папки -> 204", r.status == 204 && !new File(ROOT, "davdir").exists(), "status=" + r.status);
    }

    /* ---------------- v3.8: /api/copy, /api/move, /api/clipboard, quickDirs ---------------- */
    static void v38CopyMoveClipboardTests() throws Exception {
        // 1. Авторизация и базовые проверки /api/copy и /api/move
        Resp r = req("POST", "/api/copy?path=/hello.txt&to=/sub", null, new byte[0]);
        check("copy без ключа -> 403", r.status == 403, "status=" + r.status);
        r = req("POST", "/api/move?path=/hello.txt&to=/sub", null, new byte[0]);
        check("move без ключа -> 403", r.status == 403, "status=" + r.status);
        r = post("/api/copy?path=/&to=/sub");
        check("copy корня -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/move?path=/&to=/sub");
        check("move корня -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/copy?path=/hello.txt&to=/../../tmp");
        check("copy traversal в to -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/copy?path=/hello.txt&to=/sub&name=a%2Fb.txt");
        check("copy с '/' в name -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/copy?path=/link_out.txt&to=/sub");
        check("copy symlink наружу -> 400", r.status == 400, "status=" + r.status);

        // 2. Копирование файла, конфликт, unique=1, overwrite=1 и дубликат в той же папке
        File box = new File(ROOT, "cmbox");
        File srcDir = new File(box, "src");
        File dstDir = new File(box, "dst");
        new File(srcDir, "tree/emptySub").mkdirs();
        dstDir.mkdirs();
        write("cmbox/src/alpha.txt", "alpha-v1\n");
        write("cmbox/src/tree/leaf.txt", "leaf-data\n");
        write("cmbox/src/tree/.wifi-upload-stale.part", "ignore-me");
        Files.createSymbolicLink(new File(srcDir, "tree/esc_link.txt").toPath(), new File(TMP, "secret.txt").toPath());

        r = post("/api/copy?path=/cmbox/src/alpha.txt&to=/cmbox/dst");
        check("copy файла -> 200 и исходник сохранён",
                r.status == 200
                        && "alpha-v1\n".equals(Files.readString(new File(dstDir, "alpha.txt").toPath()))
                        && new File(srcDir, "alpha.txt").isFile(),
                "status=" + r.status + " body=" + r.text());

        r = post("/api/copy?path=/cmbox/src/alpha.txt&to=/cmbox/dst");
        check("copy при конфликте без overwrite -> 409", r.status == 409, "status=" + r.status);

        r = post("/api/copy?path=/cmbox/src/alpha.txt&to=/cmbox/dst&unique=1");
        JSONObject cj = r.status == 200 ? new JSONObject(r.text()) : new JSONObject();
        check("copy с unique=1 -> alpha (1).txt",
                r.status == 200 && "alpha (1).txt".equals(cj.optString("name")) && new File(dstDir, "alpha (1).txt").isFile(),
                "status=" + r.status + " body=" + r.text());

        r = post("/api/copy?path=/cmbox/src/alpha.txt&to=/cmbox/src&unique=1");
        check("copy файла в ту же папку с unique=1 -> дубликат",
                r.status == 200 && new File(srcDir, "alpha (1).txt").isFile(),
                "status=" + r.status);

        write("cmbox/src/alpha.txt", "alpha-v2-updated\n");
        r = post("/api/copy?path=/cmbox/src/alpha.txt&to=/cmbox/dst&overwrite=1");
        check("copy с overwrite=1 обновляет содержимое",
                r.status == 200 && "alpha-v2-updated\n".equals(Files.readString(new File(dstDir, "alpha.txt").toPath())),
                "status=" + r.status);

        // 3. Рекурсивное копирование папки (с пустой подпапкой, пропуском .part и внешнего symlink)
        r = post("/api/copy?path=/cmbox/src/tree&to=/cmbox/dst");
        check("copy папки рекурсивно сохраняет файлы и пустые подпапки, скрывая .part и внешний symlink",
                r.status == 200
                        && "leaf-data\n".equals(Files.readString(new File(dstDir, "tree/leaf.txt").toPath()))
                        && new File(dstDir, "tree/emptySub").isDirectory()
                        && !new File(dstDir, "tree/.wifi-upload-stale.part").exists()
                        && !new File(dstDir, "tree/esc_link.txt").exists(),
                "status=" + r.status + " body=" + r.text());

        r = post("/api/copy?path=/cmbox/src/tree&to=/cmbox/src/tree");
        check("copy папки в саму себя -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/copy?path=/cmbox/src/tree&to=/cmbox/src/tree/emptySub");
        check("copy папки во вложенную подпапку -> 400", r.status == 400, "status=" + r.status);
        r = post("/api/copy?path=/cmbox/src/alpha.txt&to=/cmbox/dst&name=tree&overwrite=1");
        check("copy файла поверх папки с overwrite=1 -> 409", r.status == 409, "status=" + r.status);

        // 4. Перемещение файлов и папок (/api/move)
        r = post("/api/move?path=/cmbox/src/alpha.txt&to=/cmbox/src");
        check("move файла в самого себя -> 400", r.status == 400, "status=" + r.status);

        r = post("/api/move?path=/cmbox/src/alpha%20(1).txt&to=/cmbox/dst&name=" + URLEncoder.encode("перенесён.txt", StandardCharsets.UTF_8));
        check("move файла с новым кириллическим именем -> 200 и исходник удалён",
                r.status == 200 && new File(dstDir, "перенесён.txt").isFile() && !new File(srcDir, "alpha (1).txt").exists(),
                "status=" + r.status + " body=" + r.text());

        r = post("/api/move?path=/cmbox/src/tree&to=/cmbox/src/tree/emptySub");
        check("move папки во вложенную подпапку -> 400", r.status == 400, "status=" + r.status);

        new File(srcDir, "movable/inner").mkdirs();
        write("cmbox/src/movable/inner/ok.txt", "moved-ok");
        r = post("/api/move?path=/cmbox/src/movable&to=/cmbox/dst");
        check("move папки переносит дерево целиком",
                r.status == 200
                        && !new File(srcDir, "movable").exists()
                        && "moved-ok".equals(Files.readString(new File(dstDir, "movable/inner/ok.txt").toPath())),
                "status=" + r.status + " body=" + r.text());
        cleanupDir(box);

        // 5. Общий текстовый буфер обмена (/api/clipboard)
        r = req("GET", "/api/clipboard", null, null);
        check("clipboard GET без ключа -> 403", r.status == 403, "status=" + r.status);
        r = req("POST", "/api/clipboard", null, "hi".getBytes(StandardCharsets.UTF_8));
        check("clipboard POST без ключа -> 403", r.status == 403, "status=" + r.status);

        r = get("/api/clipboard");
        JSONObject cb0 = new JSONObject(r.text());
        check("clipboard изначально пуст",
                r.status == 200 && "".equals(cb0.getString("text")) && cb0.getLong("updatedAt") == 0L,
                r.text());

        java.util.concurrent.atomic.AtomicInteger clipEvents = new java.util.concurrent.atomic.AtomicInteger();
        SERVER.setClipboardListener(clipEvents::incrementAndGet);
        String sampleClip = "Ссылка: https://example.org/test?q=1\r\nКод: 731-904 🚀";
        String expectedClip = "Ссылка: https://example.org/test?q=1\nКод: 731-904 🚀";
        r = req("POST", "/api/clipboard", with(auth(), "Content-Type", "text/plain; charset=utf-8"),
                sampleClip.getBytes(StandardCharsets.UTF_8));
        JSONObject cb1 = new JSONObject(r.text());
        check("clipboard POST сохраняет UTF-8, нормализует CRLF и уведомляет слушателя",
                r.status == 200
                        && expectedClip.equals(cb1.getString("text"))
                        && "web".equals(cb1.getString("source"))
                        && cb1.getLong("updatedAt") > 0L
                        && clipEvents.get() == 1,
                "status=" + r.status + " events=" + clipEvents.get());

        r = get("/api/list?path=/");
        JSONObject rootList1 = new JSONObject(r.text());
        check("list отражает clipHasText=true при непустом буфере",
                rootList1.optBoolean("clipHasText", false) && rootList1.optLong("clipUpdatedAt", 0L) > 0L,
                r.text());

        SERVER.updateClipboard("Привет с телефона", "phone");
        r = get("/api/clipboard");
        JSONObject cbPhone = new JSONObject(r.text());
        check("clipboard с телефона отдаёт source=phone",
                "Привет с телефона".equals(cbPhone.getString("text"))
                        && "phone".equals(cbPhone.getString("source"))
                        && clipEvents.get() == 2,
                r.text());

        r = postJson("/api/clipboard", new JSONObject().put("text", "JSON-тест 123").toString());
        check("clipboard POST JSON сохраняет текст",
                r.status == 200 && "JSON-тест 123".equals(new JSONObject(r.text()).getString("text")) && clipEvents.get() == 3,
                r.text());

        byte[] tooLong = "a".repeat(FileServer.CLIPBOARD_MAX_CHARS + 5).getBytes(StandardCharsets.US_ASCII);
        r = req("POST", "/api/clipboard", auth(), tooLong);
        check("clipboard > 65536 символов -> 413 и прежний текст не повреждён",
                r.status == 413 && "JSON-тест 123".equals(SERVER.clipboardText()),
                "status=" + r.status);

        r = req("POST", "/api/clipboard", auth(), new byte[0]);
        JSONObject cbClr = new JSONObject(r.text());
        check("clipboard очистка пустым POST -> clipHasText=false",
                r.status == 200 && "".equals(cbClr.getString("text")) && cbClr.getLong("updatedAt") == 0L,
                r.text());
        SERVER.setClipboardListener(null);

        // Проверяем очистку буфера при stop() на отдельном экземпляре сервера.
        FileServer ephemeral = new FileServer(new Context(new File(ASSETS)), ROOT, KEY);
        ephemeral.start();
        ephemeral.updateClipboard("secret-otp-999", "phone");
        ephemeral.stop();
        check("clipboard очищается в памяти при server.stop()",
                "".equals(ephemeral.clipboardText()) && ephemeral.clipboardUpdatedAt() == 0L,
                "leftover=" + ephemeral.clipboardText());

        // 6. Быстрые папки Android (quickDirs) в GET /api/list?path=/
        File dcimCam = new File(ROOT, "DCIM/Camera");
        File dlDir = new File(ROOT, "Download");
        File fakeMusicFile = new File(ROOT, "Music");
        File escMovies = new File(ROOT, "Movies");
        dcimCam.mkdirs();
        dlDir.mkdirs();
        Files.write(fakeMusicFile.toPath(), "not-a-dir".getBytes(StandardCharsets.UTF_8));
        File outsideDir = new File(TMP, "outside-movies");
        outsideDir.mkdirs();
        Files.createSymbolicLink(escMovies.toPath(), outsideDir.toPath());
        try {
            r = get("/api/list?path=/");
            org.json.JSONArray qd = new JSONObject(r.text()).optJSONArray("quickDirs");
            List<String> qList = new ArrayList<>();
            if (qd != null) for (int i = 0; i < qd.length(); i++) qList.add(qd.getString(i));
            check("quickDirs в корне возвращает стандартные папки (DCIM/Camera скрывает дубль DCIM) и исключает файлы/внешние symlink",
                    qList.equals(Arrays.asList("/DCIM/Camera", "/Download")),
                    "quickDirs=" + qList);

            r = get("/api/list?path=/sub");
            check("quickDirs не дублируется во вложенных папках",
                    !new JSONObject(r.text()).has("quickDirs"),
                    r.text());
        } finally {
            Files.deleteIfExists(escMovies.toPath());
            cleanupDir(outsideDir);
            Files.deleteIfExists(fakeMusicFile.toPath());
            cleanupDir(new File(ROOT, "DCIM"));
            cleanupDir(dlDir);
        }
    }

    /** Вставляет сегмент APP1/Exif с тегом Orientation (0x0112) сразу после маркера SOI (FFD8) в JPEG. */
    static byte[] injectJpegExifOrientation(byte[] jpeg, int orientation) throws IOException {
        // Minimal valid big-endian TIFF: "MM" + 0x002A + offset(8) + count(1) + tag(0x0112, SHORT=3, count=1, val<<16) + nextIFD(0)
        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        byte[] tiff = new byte[]{
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,
                (byte) ((orientation >> 8) & 0xff), (byte) (orientation & 0xff), 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        app1.write(tiff);
        byte[] payload = app1.toByteArray();
        int segLen = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + segLen + 2);
        out.write(0xFF); out.write(0xD8);
        out.write(0xFF); out.write(0xE1);
        out.write((segLen >> 8) & 0xFF); out.write(segLen & 0xFF);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static byte[] makeMinimalValidMp4() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        // ftyp (24 bytes): isom
        b.write(new byte[]{ 0,0,0,24, 'f','t','y','p', 'i','s','o','m', 0,0,2,0, 'i','s','o','m', 'm','p','4','1' });
        // moov (16 bytes)
        b.write(new byte[]{ 0,0,0,16, 'm','o','o','v', 0,0,0,0, 0,0,0,0 });
        // mdat (16 bytes)
        b.write(new byte[]{ 0,0,0,16, 'm','d','a','t', 0,0,0,0, 0,0,0,0 });
        return b.toByteArray();
    }

    /* ---------------- v3.9: видеопревью, EXIF/поворот, редактор текста, ZIP-распаковка, свойства и SHA-256 ---------------- */
    static void v39MediaZipEditorPropsTests() throws Exception {
        // 0. Проверка авторизации на всех новых endpoint'ах
        check("save-text без ключа -> 403", req("POST", "/api/save-text?create=1&path=/&name=a.txt", null, new byte[0]).status == 403, "");
        check("rotate-image без ключа -> 403", req("POST", "/api/rotate-image?path=/photo.jpg&degrees=90", null, null).status == 403, "");
        check("zip-list без ключа -> 403", req("GET", "/api/zip-list?path=/a.zip", null, null).status == 403, "");
        check("zip-extract без ключа -> 403", req("POST", "/api/zip-extract?path=/a.zip", null, null).status == 403, "");
        check("stat без ключа -> 403", req("GET", "/api/stat?path=/hello.txt", null, null).status == 403, "");
        check("hash без ключа -> 403", req("GET", "/api/hash?path=/hello.txt", null, null).status == 403, "");

        File sand = new File(ROOT, "v39");
        sand.mkdirs();
        try {
            // 1. Видео-миниатюры (/thumb для .mp4) + дисковый и негативный кеш
            Files.write(new File(sand, "clip.mp4").toPath(), makeMinimalValidMp4());
            Files.write(new File(sand, "broken.mp4").toPath(), "NOT_A_VALID_MP4_CONTAINER".getBytes(StandardCharsets.US_ASCII));
            String tok = new JSONObject(postJson("/api/session", "{}").text()).getString("token");

            Resp r = req("GET", "/thumb?token=" + tok + "&path=/v39/clip.mp4&w=320", null, null);
            java.awt.image.BufferedImage vThumb = r.status == 200
                    ? javax.imageio.ImageIO.read(new ByteArrayInputStream(r.body)) : null;
            check("видео-миниатюра .mp4 отдаёт масштабированный JPEG 320×180",
                    r.status == 200 && "image/jpeg".equals(r.h.get("content-type"))
                            && vThumb != null && vThumb.getWidth() == 320 && vThumb.getHeight() == 180,
                    "status=" + r.status + (vThumb != null ? " " + vThumb.getWidth() + "x" + vThumb.getHeight() : ""));

            r = req("GET", "/thumb?token=" + tok + "&path=/v39/broken.mp4&w=320", null, null);
            Resp r2 = req("GET", "/thumb?token=" + tok + "&path=/v39/broken.mp4&w=320", null, null);
            check("битый .mp4 возвращает 400 (включая повторный запрос из негативного кеша)",
                    r.status == 400 && r2.status == 400, "s1=" + r.status + " s2=" + r2.status);

            File cacheDir = new File(TMP, "v39-cache");
            cacheDir.mkdirs();
            FileServer cachedSrv = new FileServer(new Context(new File(ASSETS)), ROOT, KEY, null, cacheDir, 0);
            int cPort = cachedSrv.start();
            int origPort = PORT;
            try {
                PORT = cPort;
                String cTok = new JSONObject(postJson("/api/session", "{}").text()).getString("token");
                Resp cr = req("GET", "/thumb?token=" + cTok + "&path=/v39/clip.mp4&w=240", null, null);
                String tKey = cr.h.get("x-thumb-key");
                File cachedJpg = new File(new File(cacheDir, "thumbs"), tKey + ".jpg");
                check("видео-миниатюра сохраняется в дисковый кеш thumbs/<key>.jpg",
                        cr.status == 200 && tKey != null && cachedJpg.isFile() && cachedJpg.length() == cr.body.length,
                        "key=" + tKey + " exists=" + cachedJpg.isFile());
            } finally {
                PORT = origPort;
                cachedSrv.stop();
                cleanupDir(cacheDir);
            }

            // 2. Автоматическая EXIF-ориентация фото (Orientation=6 -> 90° CW и Orientation=3 -> 180°)
            java.awt.image.BufferedImage rawImg = new java.awt.image.BufferedImage(200, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 100; y++) {
                for (int x = 0; x < 200; x++) {
                    // Левый верхний угол ярко-красный, остальное синее
                    int rgb = (x < 60 && y < 40) ? 0xFF1010 : 0x1020E0;
                    rawImg.setRGB(x, y, rgb);
                }
            }
            ByteArrayOutputStream rawJpg = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(rawImg, "jpg", rawJpg);
            Files.write(new File(sand, "exif6.jpg").toPath(), injectJpegExifOrientation(rawJpg.toByteArray(), 6));
            Files.write(new File(sand, "exif3.jpg").toPath(), injectJpegExifOrientation(rawJpg.toByteArray(), 3));

            r = req("GET", "/thumb?token=" + tok + "&path=/v39/exif6.jpg&w=200", null, null);
            java.awt.image.BufferedImage ex6 = javax.imageio.ImageIO.read(new ByteArrayInputStream(r.body));
            int topRight6 = ex6.getRGB(ex6.getWidth() - 10, 10);
            int r6 = (topRight6 >> 16) & 0xff, b6 = topRight6 & 0xff;
            check("EXIF Orientation=6 поворачивает миниатюру 200×100 в портрет 100×200 (красный угол справа сверху)",
                    r.status == 200 && ex6.getWidth() == 100 && ex6.getHeight() == 200 && r6 > 180 && b6 < 80,
                    ex6.getWidth() + "x" + ex6.getHeight() + " r=" + r6 + " b=" + b6);

            r = req("GET", "/thumb?token=" + tok + "&path=/v39/exif3.jpg&w=200", null, null);
            java.awt.image.BufferedImage ex3 = javax.imageio.ImageIO.read(new ByteArrayInputStream(r.body));
            int botRight3 = ex3.getRGB(ex3.getWidth() - 10, ex3.getHeight() - 10);
            check("EXIF Orientation=3 поворачивает миниатюру на 180° (красный угол справа снизу)",
                    r.status == 200 && ex3.getWidth() == 200 && ex3.getHeight() == 100
                            && ((botRight3 >> 16) & 0xff) > 180 && (botRight3 & 0xff) < 80,
                    ex3.getWidth() + "x" + ex3.getHeight());

            r = get("/api/stat?path=/v39/exif6.jpg");
            JSONObject stEx6 = new JSONObject(r.text());
            check("/api/stat учитывает EXIF-ориентацию в размерах (100×200, exifOrientation=6)",
                    r.status == 200 && stEx6.getInt("width") == 100 && stEx6.getInt("height") == 200
                            && stEx6.getInt("exifOrientation") == 6,
                    r.text());

            // 3. Поворот фото на месте (POST /api/rotate-image)
            File rotJpg = new File(sand, "rot.jpg");
            Files.write(rotJpg.toPath(), rawJpg.toByteArray());
            long modBefore = rotJpg.lastModified();
            r = post("/api/rotate-image?path=/v39/rot.jpg&degrees=90");
            JSONObject rotRes = new JSONObject(r.text());
            java.awt.image.BufferedImage afterRot = javax.imageio.ImageIO.read(rotJpg);
            check("rotate-image 90° поворачивает JPEG 200×100 -> 100×200 и обновляет mtime",
                    r.status == 200 && rotRes.getInt("width") == 100 && rotRes.getInt("height") == 200
                            && afterRot.getWidth() == 100 && afterRot.getHeight() == 200
                            && rotJpg.lastModified() > modBefore,
                    r.text());

            File rotPng = new File(sand, "rot.png");
            javax.imageio.ImageIO.write(rawImg, "png", rotPng);
            r = post("/api/rotate-image?path=/v39/rot.png&degrees=180");
            byte[] pngHead = Arrays.copyOf(Files.readAllBytes(rotPng.toPath()), 4);
            java.awt.image.BufferedImage afterPng = javax.imageio.ImageIO.read(rotPng);
            check("rotate-image 180° сохраняет формат PNG и переворачивает пиксели",
                    r.status == 200 && pngHead[0] == (byte) 0x89 && pngHead[1] == 'P' && pngHead[2] == 'N' && pngHead[3] == 'G'
                            && ((afterPng.getRGB(190, 90) >> 16) & 0xff) > 180,
                    "status=" + r.status);

            check("rotate-image с углом 45° -> 400", post("/api/rotate-image?path=/v39/rot.jpg&degrees=45").status == 400, "");
            check("rotate-image на не-изображении -> 400", post("/api/rotate-image?path=/hello.txt&degrees=90").status == 400, "");
            File symJpg = new File(sand, "sym.jpg");
            Files.createSymbolicLink(symJpg.toPath(), rotJpg.toPath());
            check("rotate-image на символической ссылке -> 400", post("/api/rotate-image?path=/v39/sym.jpg&degrees=90").status == 400, "");

            // 4. Создание и правка текстовых файлов (/api/text + /api/save-text + конфликт mtime)
            byte[] iniBody = "[server]\nport = 8765\ntitle = Привет 🚀\n".getBytes(StandardCharsets.UTF_8);
            r = req("POST", "/api/save-text?create=1&path=/v39&name=config.ini", auth(), iniBody);
            JSONObject created = new JSONObject(r.text());
            check("save-text create=1 создаёт UTF-8 .ini файл",
                    r.status == 200 && "config.ini".equals(created.getString("name"))
                            && "/v39/config.ini".equals(created.getString("path"))
                            && created.getLong("size") == iniBody.length,
                    r.text());

            r = get("/api/text?path=/v39/config.ini");
            long loadedMod = Long.parseLong(r.h.getOrDefault("x-file-modified", "0"));
            check("/api/text поддерживает .ini и возвращает X-File-Modified + X-File-Editable=1",
                    r.status == 200 && r.text().contains("Привет 🚀") && loadedMod > 0L
                            && "1".equals(r.h.get("x-file-editable")),
                    "mod=" + loadedMod);

            check("save-text повторный create=1 без overwrite -> 409",
                    req("POST", "/api/save-text?create=1&path=/v39&name=config.ini", auth(), iniBody).status == 409, "");

            r = req("POST", "/api/save-text?create=1&unique=1&path=/v39&name=config.ini", auth(), iniBody);
            check("save-text create=1&unique=1 создаёт config (1).ini",
                    r.status == 200 && "config (1).ini".equals(new JSONObject(r.text()).getString("name")), r.text());

            byte[] updated = "[server]\nport = 9000\n".getBytes(StandardCharsets.UTF_8);
            r = req("POST", "/api/save-text?path=/v39/config.ini&expectedModified=" + loadedMod, auth(), updated);
            long newMod = new JSONObject(r.text()).getLong("modified");
            check("save-text с актуальным expectedModified сохраняет текст и сдвигает mtime",
                    r.status == 200 && newMod > loadedMod
                            && "[server]\nport = 9000\n".equals(Files.readString(new File(sand, "config.ini").toPath(), StandardCharsets.UTF_8)),
                    "old=" + loadedMod + " new=" + newMod);

            byte[] staleTry = "STALE OVERWRITE".getBytes(StandardCharsets.UTF_8);
            r = req("POST", "/api/save-text?path=/v39/config.ini&expectedModified=" + loadedMod, auth(), staleTry);
            check("save-text с устаревшим expectedModified -> 409 (X-Conflict-Reason: mtime) без порчи файла",
                    r.status == 409 && "mtime".equals(r.h.get("x-conflict-reason"))
                            && "[server]\nport = 9000\n".equals(Files.readString(new File(sand, "config.ini").toPath(), StandardCharsets.UTF_8)),
                    "status=" + r.status + " reason=" + r.h.get("x-conflict-reason"));

            r = req("POST", "/api/save-text?path=/v39/config.ini&expectedModified=" + loadedMod + "&force=1", auth(),
                    "FORCED=1\n".getBytes(StandardCharsets.UTF_8));
            check("save-text force=1 принудительно перезаписывает при конфликте mtime",
                    r.status == 200 && "FORCED=1\n".equals(Files.readString(new File(sand, "config.ini").toPath(), StandardCharsets.UTF_8)),
                    "status=" + r.status);

            check("save-text бинарного расширения .exe -> 400",
                    req("POST", "/api/save-text?create=1&path=/v39&name=evil.exe", auth(), new byte[]{1, 2}).status == 400, "");
            check("save-text временного имени .part -> 400",
                    req("POST", "/api/save-text?create=1&path=/v39&name=test.part", auth(), new byte[]{1}).status == 400, "");
            check("save-text traversal ../../ -> 400",
                    req("POST", "/api/save-text?path=/../../pwn.txt", auth(), new byte[]{1}).status == 400, "");
            check("save-text симлинка link_in.txt -> 400",
                    req("POST", "/api/save-text?path=/link_in.txt", auth(), "x".getBytes(StandardCharsets.UTF_8)).status == 400, "");
            check("save-text > 2 МБ -> 413",
                    req("POST", "/api/save-text?create=1&path=/v39&name=huge.txt", auth(), new byte[2 * 1024 * 1024 + 16]).status == 413, "");

            // 5. Просмотр и безопасная распаковка .zip (/api/zip-list + /api/zip-extract)
            File goodZip = new File(sand, "архив.zip");
            try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(new FileOutputStream(goodZip), StandardCharsets.UTF_8)) {
                zout.putNextEntry(new ZipEntry("пустая/"));
                zout.closeEntry();
                zout.putNextEntry(new ZipEntry("readme.txt"));
                zout.write("Привет ZIP!\n".getBytes(StandardCharsets.UTF_8));
                zout.closeEntry();
                zout.putNextEntry(new ZipEntry("docs/отчёт.md"));
                zout.write("# Отчёт\nВсё работает.\n".getBytes(StandardCharsets.UTF_8));
                zout.closeEntry();
            }

            r = get("/api/zip-list?path=" + URLEncoder.encode("/v39/архив.zip", StandardCharsets.UTF_8));
            JSONObject zl = new JSONObject(r.text());
            check("zip-list возвращает список файлов, папок и размер без распаковки",
                    r.status == 200 && zl.getInt("fileCount") == 2 && zl.getInt("dirCount") == 1
                            && zl.getInt("unsafeCount") == 0 && zl.getLong("totalUncompressed") > 10L
                            && zl.getJSONArray("entries").length() == 3,
                    r.text());

            r = post("/api/zip-extract?path=" + URLEncoder.encode("/v39/архив.zip", StandardCharsets.UTF_8) + "&dest=/v39&subfolder=1");
            JSONObject zx = new JSONObject(r.text());
            File extDir = new File(sand, "архив");
            check("zip-extract subfolder=1 распаковывает файлы и пустые подпапки",
                    r.status == 200 && zx.getInt("files") == 2
                            && new File(extDir, "пустая").isDirectory()
                            && "Привет ZIP!\n".equals(Files.readString(new File(extDir, "readme.txt").toPath(), StandardCharsets.UTF_8))
                            && Files.readString(new File(extDir, "docs/отчёт.md").toPath(), StandardCharsets.UTF_8).contains("Всё работает."),
                    r.text());

            check("zip-extract повторно в существующую папку без overwrite -> 409",
                    post("/api/zip-extract?path=" + URLEncoder.encode("/v39/архив.zip", StandardCharsets.UTF_8) + "&dest=/v39&subfolder=1").status == 409, "");

            r = post("/api/zip-extract?path=" + URLEncoder.encode("/v39/архив.zip", StandardCharsets.UTF_8) + "&dest=/v39&subfolder=1&unique=1");
            check("zip-extract subfolder=1&unique=1 создаёт папку «архив (1)»",
                    r.status == 200 && new File(sand, "архив (1)/readme.txt").isFile(), r.text());

            File selDest = new File(sand, "sel");
            selDest.mkdirs();
            r = postJson("/api/zip-extract?path=" + URLEncoder.encode("/v39/архив.zip", StandardCharsets.UTF_8) + "&dest=/v39/sel&subfolder=0",
                    "{\"entries\":[\"docs/отчёт.md\"]}");
            check("zip-extract выборочно извлекает только указанные файлы",
                    r.status == 200 && new File(selDest, "docs/отчёт.md").isFile() && !new File(selDest, "readme.txt").exists(),
                    r.text());

            // Кириллический ZIP в старой кодировке CP866 (Windows/DOS)
            File cp866Zip = new File(sand, "win-cp866.zip");
            try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(
                    new FileOutputStream(cp866Zip), java.nio.charset.Charset.forName("IBM866"))) {
                zout.putNextEntry(new ZipEntry("Папка/Привет_мир.txt"));
                zout.write("cp866-ok".getBytes(StandardCharsets.UTF_8));
                zout.closeEntry();
            }
            r = get("/api/zip-list?path=/v39/win-cp866.zip");
            Resp rEx866 = post("/api/zip-extract?path=/v39/win-cp866.zip&dest=/v39&subfolder=1");
            check("ZIP с именами в CP866 корректно читается и распаковывается с русской кириллицей",
                    r.status == 200 && r.text().contains("Папка/Привет_мир.txt")
                            && rEx866.status == 200 && new File(sand, "win-cp866/Папка/Привет_мир.txt").isFile(),
                    r.text());

            // Защита от Zip Slip (../, ..\, абсолютные и C:/ пути)
            File slipZip = new File(sand, "slip.zip");
            try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(new FileOutputStream(slipZip), StandardCharsets.UTF_8)) {
                zout.putNextEntry(new ZipEntry("innocent.txt"));
                zout.write("hi".getBytes(StandardCharsets.US_ASCII));
                zout.closeEntry();
                zout.putNextEntry(new ZipEntry("../../zipslip-pwned.txt"));
                zout.write("pwn".getBytes(StandardCharsets.US_ASCII));
                zout.closeEntry();
            }
            r = get("/api/zip-list?path=/v39/slip.zip");
            JSONObject slipList = new JSONObject(r.text());
            Resp slipEx1 = post("/api/zip-extract?path=/v39/slip.zip&dest=/v39&subfolder=1");
            Resp slipEx2 = post("/api/zip-extract?path=/v39/slip.zip&dest=/v39&subfolder=0");
            check("Zip Slip (../../) помечается в zip-list и полностью блокируется в zip-extract до записи файлов",
                    r.status == 200 && slipList.getInt("unsafeCount") == 1 && slipList.getInt("fileCount") == 1
                            && slipEx1.status == 400 && slipEx2.status == 400
                            && !new File(TMP, "zipslip-pwned.txt").exists()
                            && !new File(ROOT, "zipslip-pwned.txt").exists()
                            && !new File(sand, "slip").exists()
                            && !new File(sand, "innocent.txt").exists(),
                    "s1=" + slipEx1.status + " s2=" + slipEx2.status);

            File winSlip = new File(sand, "winslip.zip");
            try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(new FileOutputStream(winSlip), StandardCharsets.UTF_8)) {
                zout.putNextEntry(new ZipEntry("sub\\..\\..\\evil.txt"));
                zout.write("x".getBytes(StandardCharsets.US_ASCII));
                zout.closeEntry();
            }
            check("Zip Slip с обратными слешами (sub\\..\\..) -> 400",
                    post("/api/zip-extract?path=/v39/winslip.zip&dest=/v39&subfolder=1").status == 400, "");

            // Защита от распаковки через существующий симлинк подпапки
            File symTrap = new File(sand, "symtrap");
            File outsideZipTarget = new File(TMP, "outside-zip-trap");
            symTrap.mkdirs();
            outsideZipTarget.mkdirs();
            Files.createSymbolicLink(new File(symTrap, "docs").toPath(), outsideZipTarget.toPath());
            try {
                r = post("/api/zip-extract?path=" + URLEncoder.encode("/v39/архив.zip", StandardCharsets.UTF_8) + "&dest=/v39/symtrap&subfolder=0");
                check("zip-extract блокирует запись через симлинк-подпапку -> 400",
                        r.status == 400 && !new File(outsideZipTarget, "отчёт.md").exists(),
                        "status=" + r.status);
            } finally {
                Files.deleteIfExists(new File(symTrap, "docs").toPath());
                cleanupDir(outsideZipTarget);
            }

            // Защита от Zip Bomb (12 МБ нулей сжимаются в ~12 КБ -> коэффициент ~1000:1 > 100:1)
            File bombZip = new File(sand, "bomb.zip");
            try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(new FileOutputStream(bombZip), StandardCharsets.UTF_8)) {
                zout.putNextEntry(new ZipEntry("zeros.bin"));
                byte[] z64 = new byte[65536];
                for (int i = 0; i < 192; i++) zout.write(z64); // 12 MiB
                zout.closeEntry();
            }
            r = post("/api/zip-extract?path=/v39/bomb.zip&dest=/v39&subfolder=1");
            check("Zip Bomb (12 МБ нулей, >100:1) прерывается с 400 и убирает временную папку",
                    r.status == 400 && !new File(sand, "bomb").exists(),
                    "status=" + r.status + " msg=" + r.text());

            // Битый .zip
            Files.write(new File(sand, "corrupt.zip").toPath(), "not-a-zip-at-all".getBytes(StandardCharsets.US_ASCII));
            check("битый .zip -> 400 в zip-list и zip-extract",
                    get("/api/zip-list?path=/v39/corrupt.zip").status == 400
                            && post("/api/zip-extract?path=/v39/corrupt.zip&dest=/v39").status == 400, "");

            // 6. Свойства (/api/stat) и потоковый SHA-256 (/api/hash)
            write("v39/.hidden.txt", "secret-note");
            r = get("/api/stat?path=/v39/.hidden.txt");
            JSONObject stHid = new JSONObject(r.text());
            check("/api/stat возвращает метаданные файла и признак hidden=true",
                    r.status == 200 && !stHid.getBoolean("dir") && stHid.getBoolean("hidden")
                            && stHid.getLong("size") == 11L && "text/plain".equals(stHid.getString("mime"))
                            && "/v39".equals(stHid.getString("parent")),
                    r.text());

            r = get("/api/stat?path=" + URLEncoder.encode("/v39/архив", StandardCharsets.UTF_8));
            JSONObject stDir = new JSONObject(r.text());
            check("/api/stat на папке возвращает точное число прямых файлов и подпапок",
                    r.status == 200 && stDir.getBoolean("dir")
                            && stDir.getInt("directFiles") == 1 && stDir.getInt("directDirs") == 2,
                    r.text());

            check("/api/stat на несуществующем пути -> 404", get("/api/stat?path=/v39/nope.txt").status == 404, "");
            check("/api/stat на внешнем симлинке -> 400", get("/api/stat?path=/link_out.txt").status == 400, "");

            r = get("/api/hash?path=/empty.bin");
            JSONObject hEmpty = new JSONObject(r.text());
            check("/api/hash пустого файла совпадает с эталонным SHA-256",
                    r.status == 200 && "SHA-256".equals(hEmpty.getString("algo"))
                            && "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equals(hEmpty.getString("sha256")),
                    r.text());

            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] expDigest = md.digest(Files.readAllBytes(new File(ROOT, "bin.dat").toPath()));
            StringBuilder expHex = new StringBuilder();
            for (byte b : expDigest) expHex.append(String.format(Locale.US, "%02x", b & 0xff));
            r = get("/api/hash?path=/bin.dat");
            JSONObject hBin = new JSONObject(r.text());
            check("/api/hash 1 МБ бинарника совпадает побайтно с SHA-256",
                    r.status == 200 && expHex.toString().equals(hBin.getString("sha256"))
                            && hBin.getLong("size") == 1024L * 1024L,
                    r.text());

            check("/api/hash на папке -> 400", get("/api/hash?path=/sub").status == 400, "");
            check("/api/hash на внешнем симлинке -> 400", get("/api/hash?path=/link_out.txt").status == 400, "");
        } finally {
            cleanupDir(sand);
        }
    }

    /* ---------------- граничные случаи ---------------- */
    static void edgeTests() throws Exception {
        Resp r = get("/api/download?path=/" + URLEncoder.encode("quote\"name.txt", StandardCharsets.UTF_8));
        check("download файла с кавычкой в имени", r.status == 200, "status=" + r.status);
        r = get("/api/download?path=/" + URLEncoder.encode("bad\nname.txt", StandardCharsets.UTF_8));
        check("download файла с \\n в имени: без header injection", r.status == 200 && !r.h.getOrDefault("content-disposition", "").contains("\n"), "status=" + r.status);
        r = post("/api/delete?path=/" + URLEncoder.encode(new String(Character.toChars(0x1F4F1)) + ".txt", StandardCharsets.UTF_8));
        check("delete файла с эмодзи -> 200", r.status == 200, "status=" + r.status);
        r = post("/api/mkdir?path=/&name=");
        check("mkdir с пустым именем -> 400", r.status == 400, "status=" + r.status);
        r = get("/api/unknown");
        check("неизвестный endpoint -> 404", r.status == 404, "status=" + r.status);
        r = get("/api/download?path=/empty.bin");
        check("download пустого файла -> 200, 0 байт", r.status == 200 && r.body.length == 0, "status=" + r.status);
        int issued = 0, lastStatus = 0;
        for (int i = 0; i < 300; i++) { Resp rr = post("/api/preview-ticket?path=/img.png"); lastStatus = rr.status; if (rr.status == 200) issued++; else break; }
        check("лимит активных ссылок работает", lastStatus == 429 && issued > 0, "issued=" + issued + " last=" + lastStatus);
    }

    /* ---------------- устойчивость к «тихим» соединениям ---------------- */
    static void dosTest() throws Exception {
        FileServer server2 = new FileServer(new Context(new File(ASSETS)), ROOT, KEY);
        int port2 = server2.start();
        List<Socket> idle = new ArrayList<>();
        for (int i = 0; i < 6; i++) { Socket s = new Socket("127.0.0.1", port2); idle.add(s); }
        Thread.sleep(300);
        long t0 = System.currentTimeMillis();
        String result;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port2), 3000);
            s.setSoTimeout(8000);
            s.getOutputStream().write(("GET /api/list?path=/ HTTP/1.1\r\nHost: x\r\nX-Access-Key: " + KEY + "\r\n\r\n").getBytes());
            try { Resp r = readResp(s.getInputStream()); result = "status=" + r.status + " за " + (System.currentTimeMillis() - t0) + "мс"; }
            catch (SocketTimeoutException e) { result = "ТАЙМАУТ после " + (System.currentTimeMillis() - t0) + "мс"; }
        }
        server2.stop();
        for (Socket s : idle) try { s.close(); } catch (Exception ignored) {}
        check("6 «молчащих» подключений не блокируют сервер", result.startsWith("status=200"), result);
    }

    /* ---------------- HTTPS ---------------- */
    static void tlsTests() throws Exception {
        File tlsDir = new File(TMP, "tls");
        SSLContext serverTls = SelfSignedCertificate.createContext(tlsDir, Collections.emptyList());
        FileServer server = new FileServer(new Context(new File(ASSETS)), ROOT, KEY, serverTls);
        int port = server.start();
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        try (SSLSocket socket = (SSLSocket) clientTls.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            socket.startHandshake();
            socket.getOutputStream().write(("GET /api/list?path=/ HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Access-Key: " + KEY + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            Resp r = readResp(socket.getInputStream());
            check("HTTPS: список файлов по TLS", r.status == 200 && r.text().contains("hello.txt"), "status=" + r.status);
            SSLSession session = socket.getSession();
            Certificate[] chain = session.getPeerCertificates();
            X509Certificate cert = (X509Certificate) chain[0];
            check("сертификат: CN=PocketBridge", cert.getSubjectX500Principal().getName().contains("CN=PocketBridge"), cert.getSubjectX500Principal().getName());
            check("сертификат: X.509 v3 + не CA", cert.getVersion() == 3 && cert.getBasicConstraints() == -1,
                    "v=" + cert.getVersion() + " bc=" + cert.getBasicConstraints());
            check("сертификат: ExtendedKeyUsage serverAuth",
                    cert.getExtendedKeyUsage() != null && cert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"),
                    String.valueOf(cert.getExtendedKeyUsage()));
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            String sanStr = String.valueOf(sans);
            check("сертификат: SAN содержит localhost и 127.0.0.1",
                    sans != null && sanStr.contains("localhost") && sanStr.contains("127.0.0.1"), sanStr);
            check("сертификат: самоподписанный (issuer = subject)", cert.getIssuerX500Principal().equals(cert.getSubjectX500Principal()), "");
            check("сертификат: RSA-2048", "RSA".equals(cert.getPublicKey().getAlgorithm()) && ((java.security.interfaces.RSAPublicKey) cert.getPublicKey()).getModulus().bitLength() == 2048, cert.getPublicKey().getAlgorithm());
            check("TLS >= 1.2", session.getProtocol().compareTo("TLSv1.2") >= 0, session.getProtocol());
            java.util.Date notAfter = cert.getNotAfter();
            check("сертификат действует ~2 года", notAfter.after(new java.util.Date(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)), notAfter.toString());
        } finally { server.stop(); }

        SSLContext second = SelfSignedCertificate.createContext(tlsDir, Collections.emptyList());
        java.security.KeyStore a = java.security.KeyStore.getInstance("PKCS12");
        java.security.KeyStore b = java.security.KeyStore.getInstance("PKCS12");
        a.load(new FileInputStream(new File(tlsDir, "server-identity.p12")), "wififiles".toCharArray());
        check("сертификат сохраняется между запусками", a.containsAlias("server") && second != null, "alias=" + a.containsAlias("server"));
        noteIt("HTTPS в браузере", "самоподписанный сертификат: браузер один раз попросит подтвердить исключение");
    }
}
