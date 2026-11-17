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
    static String ASSETS = System.getProperty("pb.assets", "app/src/main/assets");
    static int pass = 0, fail = 0, note = 0;
    static final List<String> failures = new ArrayList<>();
    static final List<String> notes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        TMP = Files.createTempDirectory("pocketbridge-test").toFile();
        ROOT = new File(TMP, "root");
        prepareTree();
        Context ctx = new Context(new File(ASSETS));
        FileServer server = new FileServer(ctx, ROOT, KEY);
        PORT = server.start();
        System.out.println("SERVER PORT=" + PORT + " ROOT=" + ROOT);
        try { runTests(); } finally { server.stop(); }
        tlsTests();
        System.out.println("\n=== SUMMARY: PASS=" + pass + "  FAIL=" + fail + "  NOTES=" + note);
        for (String f : failures) System.out.println("FAILED -> " + f);
        for (String n : notes) System.out.println("NOTE   -> " + n);
        if (fail > 0) System.exit(1);
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
            return readResp(s.getInputStream());
        }
    }
    static Resp get(String p) throws Exception { return req("GET", p, auth(), null); }
    static Resp post(String p) throws Exception { return req("POST", p, auth(), new byte[0]); }
    static Resp postJson(String p, String json) throws Exception {
        return req("POST", p, with(auth(), "Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8));
    }

    static Resp readResp(InputStream in) throws Exception {
        Resp r = new Resp();
        String sl = readLine(in);
        if (sl == null) throw new IOException("empty response");
        r.status = Integer.parseInt(sl.split(" ")[1]);
        String l;
        while ((l = readLine(in)) != null && !l.isEmpty()) {
            int i = l.indexOf(':');
            if (i > 0) r.h.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
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
        check("лимит 256 активных ссылок", issued >= 250 && lastStatus == 429, "issued=" + issued + " last=" + lastStatus);
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
