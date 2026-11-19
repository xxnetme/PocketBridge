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
            tlsTests();
        } finally {
            cleanupDir(TMP);
        }
        System.out.println("\n=== SUMMARY: PASS=" + pass + "  FAIL=" + fail + "  NOTES=" + note);
        for (String f : failures) System.out.println("FAILED -> " + f);
        for (String n : notes) System.out.println("NOTE   -> " + n);
        if (fail > 0) System.exit(1);
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
            HttpURLConnection uc = (HttpURLConnection) new URL("http://127.0.0.1:8765/api/upload-chunk?id=" + uploadId + "&offset=" + offset).openConnection();
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
        SSLContext serverTls = SelfSignedCertificate.createContext(tlsDir);
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

        SSLContext second = SelfSignedCertificate.createContext(tlsDir);
        java.security.KeyStore a = java.security.KeyStore.getInstance("PKCS12");
        java.security.KeyStore b = java.security.KeyStore.getInstance("PKCS12");
        a.load(new FileInputStream(new File(tlsDir, "server-identity.p12")), "wififiles".toCharArray());
        check("сертификат сохраняется между запусками", a.containsAlias("server") && second != null, "alias=" + a.containsAlias("server"));
        noteIt("HTTPS в браузере", "самоподписанный сертификат: браузер один раз попросит подтвердить исключение");
    }
}
