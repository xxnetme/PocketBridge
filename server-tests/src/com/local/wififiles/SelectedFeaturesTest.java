package com.local.wififiles;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;

/** The API contracts exercised through real FileServer/WebDAV listeners, including restart with fresh auth. */
final class SelectedFeaturesTest {
    private final Context context = new Context(new File(Test.ASSETS));
    private final Path root, other;
    private final String originalKey = Test.KEY;
    private final int originalPort = Test.PORT;
    private FileServer server;
    private String scope, upload;

    private SelectedFeaturesTest() throws Exception {
        root = Files.createDirectory(Test.TMP.toPath().resolve("selected-v49"));
        other = Files.createDirectory(Test.TMP.toPath().resolve("selected-other"));
    }

    static void run() throws Exception {
        SelectedFeaturesTest tests = new SelectedFeaturesTest();
        try {
            tests.start(tests.root, "new-test-key-1", false);
            tests.durable();
            tests.trash();
            tests.rename();
            tests.search();
            tests.scans();
        } finally {
            if (tests.server != null) tests.server.stop();
            Test.KEY = tests.originalKey;
            Test.PORT = tests.originalPort;
        }
    }

    private void start(Path where, String key, boolean readOnly) throws Exception {
        if (server != null) server.stop();
        server = new FileServer(context, where.toFile(), key, null, null, 8765, readOnly);
        Test.PORT = server.start();
        Test.KEY = key;
        scope = json(Test.get("/api/list?path=/")).optString("uploadScope");
    }

    private void durable() throws Exception {
        final int c = RetainedUploads.CHUNK;
        byte[] content = new byte[5 * c + 99];
        new Random(411L).nextBytes(content);
        upload = id();
        String init = init(upload, "/", "video.bin", content.length, digest(content));
        JSONObject r = json(Test.post(init));
        check("init с идентичным ID повторяемый", upload.equals(r.getString("uploadId"))
                && upload.equals(json(Test.post(init)).getString("uploadId")));
        check("повтор init с другим содержимым не перепривязывает черновик", Test.post(init(upload, "/", "video.bin", content.length, digest(new byte[0]))).status == 409);
        // Commit a full chunk and discard the HTTP reply. A repeat must report its durable offset.
        dropReply(chunk(upload, 0), c, Arrays.copyOfRange(content, 0, c));
        long offset = waitReceived(upload, c);
        check("потеря ответа: сервер подтвердил ровно один чанк", offset == c);
        Test.Resp mismatch = Test.req("POST", chunk(upload, 0), Test.auth(), new byte[]{1});
        check("неверное смещение: 409 с подтверждённым байтом", mismatch.status == 409 && String.valueOf(c).equals(mismatch.h.get("x-upload-received")));
        try (Socket s = new Socket("127.0.0.1", Test.PORT)) {
            s.setSoTimeout(5000);
            sendPartial(s, chunk(upload, c), c, new byte[13_001]);
            s.shutdownOutput();
            check("обрыв посреди части не подтверждается", Test.readResp(s.getInputStream()).status >= 400);
        }
        check("незаконченная часть не увеличивает смещение", json(Test.get(status(upload))).getLong("received") == c);
        Path draft = root.resolve(ManagedFiles.DRAFTS).resolve(upload + ".part");
        Files.write(draft, new byte[999], StandardOpenOption.APPEND); // bytes flushed before a missing journal commit
        String initialScope = scope, oldKey = Test.KEY;
        start(root, "new-test-key-2", false);
        check("после перезапуска прежний код не действует", Test.req("GET", status(upload), Test.with(null, "X-Access-Key", oldKey), null).status == 403);
        check("подтверждённые байты и ID области пережили перезапуск", initialScope.equals(scope) && json(Test.get(status(upload))).getLong("received") == c);
        check("черновик не виден в списке, по прямому HTTP и DAV", !Test.get("/api/list").text().contains(ManagedFiles.DRAFTS)
                && Test.get("/api/download?path=/" + ManagedFiles.DRAFTS + "/" + upload + ".part").status == 400
                && Test.req("GET", "/dav/" + ManagedFiles.DRAFTS + "/" + upload + ".part", Test.davAuth(), null).status == 403);
        Files.createSymbolicLink(root.resolve("alias-draft"), draft);
        check("служебные файлы закрыты и через символическую ссылку", Test.get("/api/stat?path=/alias-draft").status == 400
                && Test.req("GET", "/dav/alias-draft", Test.davAuth(), null).status == 403);
        start(root, "new-test-read-only", true);
        for (String op : new String[]{"upload-chunk", "upload-finish", "upload-retarget", "upload-abort"}) {
            check("read-only блокирует " + op, Test.post("/api/" + op + "?id=" + upload).status == 403);
        }
        check("read-only не раскрывает черновик", Test.get(status(upload)).status == 403 && scope.isEmpty());
        start(other, "new-test-other-root", false);
        check("другой корень имеет другой scope и не читает ID", !initialScope.equals(scope) && Test.get(status(upload)).status == 404 && Test.post(init).status == 409);
        start(root, "new-test-key-3", false);
        for (int o = c; o < content.length; o += c) {
            int next = Math.min(o + c, content.length);
            check("сервер фиксирует полный чанк до " + next, json(Test.req("POST", chunk(upload, o), Test.auth(), Arrays.copyOfRange(content, o, next)))
                    .getLong("received") == next);
        }
        Files.writeString(root.resolve("video.bin"), "new data");
        Test.Resp conflict = Test.post("/api/upload-finish?id=" + upload);
        check("имя занято при finish: новый файл цел, черновик остаётся", conflict.status == 409 && "1".equals(conflict.h.get("x-pocketbridge-upload-conflict"))
                && Files.readString(root.resolve("video.bin")).equals("new data") && Files.size(draft) == content.length);
        check("смена имени не сбрасывает сохранённые байты", json(Test.post("/api/upload-retarget?id=" + upload + "&name=renamed.bin")).getLong("received") == content.length);
        dropReply("/api/upload-finish?id=" + upload, 0, new byte[0]);
        for (int i = 0; i < 100 && Files.exists(draft); i++) Thread.sleep(30);
        check("завершённый файл 20+ МиБ совпадает по SHA-256", !Files.exists(draft) && digest(content).equals(ManagedFiles.sha256(root.resolve("renamed.bin"), Long.MAX_VALUE)));
        start(root, "new-test-key-4", false);
        check("потерянный ответ finish: повтор не создаёт второй файл", json(Test.post("/api/upload-finish?id=" + upload)).getBoolean("done") && json(Test.get(status(upload))).getBoolean("done"));
        check("отмена после финала не удаляет готовый файл", Test.post("/api/upload-abort?id=" + upload).status == 200 && Files.isRegularFile(root.resolve("renamed.bin")));

        String corrupt = id(), temporary = id();
        json(Test.post(init(corrupt, "/", "corrupt.txt", 3, digest(new byte[]{1, 2, 3}))));
        json(Test.req("POST", chunk(corrupt, 0), Test.auth(), new byte[]{9, 9, 9}));
        check("несовпадение SHA-256 блокирует публикацию", Test.post("/api/upload-finish?id=" + corrupt).status == 409 && !Files.exists(root.resolve("corrupt.txt")));
        check("явная отмена удаляет и данные, и запись", Test.post("/api/upload-abort?id=" + corrupt).status == 200 && Test.get(status(corrupt)).status == 404
                && !Files.exists(root.resolve(ManagedFiles.DRAFTS).resolve(corrupt + ".part")));
        json(Test.post(init(temporary, "/", "expired.txt", 1, digest(new byte[]{1}))));
        json(Test.req("POST", chunk(temporary, 0), Test.auth(), new byte[]{1}));
        Path journal = context.getFilesDir().toPath().resolve("upload-journal-v1").resolve(ManagedFiles.hashText(root.toString())).resolve(temporary + ".json");
        ManagedFiles.writeRecord(journal, ManagedFiles.readRecord(journal).put("expiresAt", 1L));
        start(root, "new-test-key-5", false);
        for (int i = 0; i < 100 && Files.exists(journal); i++) Thread.sleep(10);
        check("истёкший черновик удаляется при старте", !Files.exists(journal) && !Files.exists(root.resolve(ManagedFiles.DRAFTS).resolve(temporary + ".part")));
        String empty = id();
        json(Test.post(init(empty, "/", "empty-new.bin", 0, digest(new byte[0]))));
        check("пустой файл проходит ту же проверку SHA-256", json(Test.post("/api/upload-finish?id=" + empty)).getBoolean("done") && Files.size(root.resolve("empty-new.bin")) == 0);
        check("файл больше 20 ГиБ не принимается", Test.post(init(id(), "/", "too-big", RetainedUploads.MAX_BYTES + 1, digest(new byte[0]))).status == 400);
        Path destination = Files.createDirectory(root.resolve("revived"));
        String misplaced = id();
        json(Test.post(init(misplaced, "/revived", "never.txt", 0, digest(new byte[0]))));
        Files.move(destination, root.resolve("revived-before"));
        Files.createDirectory(destination);
        check("пересоздание пути не меняет папку назначения черновика", Test.post("/api/upload-finish?id=" + misplaced).status == 409 && !Files.exists(destination.resolve("never.txt")));
        json(Test.post("/api/upload-abort?id=" + misplaced));
    }

    private void trash() throws Exception {
        Files.writeString(root.resolve("deleted.txt"), "old");
        Path da = Files.createDirectory(root.resolve("dav-del"));
        Files.writeString(da.resolve("child.txt"), "dav");
        String id = json(Test.post("/api/delete?path=/deleted.txt")).getString("id");
        check("WebDAV DELETE папки отправляет её в ту же корзину", Test.req("DELETE", "/dav/dav-del", Test.davAuth(), null).status == 204);
        start(root, "trash-new-key", false);
        JSONArray rows = json(Test.get("/api/trash")).getJSONArray("items");
        check("корзина сохраняется между запусками", rows.length() == 2 && rows.toString().contains("/dav-del"));
        Files.writeString(root.resolve("deleted.txt"), "new");
        check("восстановление не заменяет занятый путь", Test.post("/api/trash-restore?id=" + id).status == 409 && Files.readString(root.resolve("deleted.txt")).equals("new"));
        check("можно восстановить под другим именем", Test.post("/api/trash-restore?id=" + id + "&name=old.txt").status == 200 && Files.readString(root.resolve("old.txt")).equals("old"));
        String directory = json(Test.get("/api/trash")).getJSONArray("items").getJSONObject(0).getString("id");
        check("восстановление папки возвращает вложенный файл", Test.post("/api/trash-restore?id=" + directory).status == 200 && Files.readString(da.resolve("child.txt")).equals("dav"));
        String purge = json(Test.post("/api/delete?path=/dav-del")).getString("id");
        check("очистка отдельной записи необратима", Test.post("/api/trash-purge?id=" + purge).status == 200 && Test.post("/api/trash-restore?id=" + purge).status == 404);
        String expire = json(Test.post("/api/delete?path=/old.txt")).getString("id");
        Path slot = root.resolve(ManagedFiles.TRASH).resolve(expire);
        ManagedFiles.writeRecord(slot.resolve("meta.json"), ManagedFiles.readRecord(slot.resolve("meta.json")).put("deletedAt", 1).put("expiresAt", 1 + TrashStore.TTL));
        start(root, "trash-expire-key", false);
        check("срок хранения корзины: очистка при запуске/чтении", json(Test.get("/api/trash")).getJSONArray("items").length() == 0 && !Files.exists(slot));
        start(other, "trash-broken-key", false);
        Files.createDirectories(other.resolve(ManagedFiles.TRASH)); // not owned by PocketBridge
        Files.writeString(other.resolve("keep.txt"), "keep this");
        check("занятое служебное имя: DELETE браузера и DAV не удаляют источник", Test.post("/api/delete?path=/keep.txt").status == 409
                && Test.req("DELETE", "/dav/keep.txt", Test.davAuth(), null).status == 409 && Files.readString(other.resolve("keep.txt")).equals("keep this"));
        check("служебный путь нельзя назначить корнем профиля", TestThrows.unavailable(other.toFile(), ManagedFiles.TRASH));
        start(root, "trash-new-key-2", false);
        check("корзина требует авторизации и недоступна напрямую по DAV", Test.req("GET", "/api/trash", null, null).status == 403
                && Test.req("PROPFIND", "/dav/" + ManagedFiles.TRASH, Test.davAuth(), null).status == 403);
    }

    private void rename() throws Exception {
        Files.writeString(root.resolve("source1.txt"), "one");
        Files.writeString(root.resolve("source2.txt"), "two");
        JSONArray paths = new JSONArray(Arrays.asList("/source1.txt", "/source2.txt"));
        JSONObject req = new JSONObject().put("paths", paths).put("template", "same{ext}");
        check("предпросмотр обнаруживает одинаковые цели", json(Test.postJson("/api/rename-preview", req.toString())).isNull("previewId"));
        req.put("template", "запись_{n:3}{ext}").put("start", 6);
        JSONObject preview = json(Test.postJson("/api/rename-preview", req.toString()));
        String plan = preview.getString("previewId");
        check("предпросмотр показывает точный шаблон без записи", preview.getJSONArray("items").getJSONObject(0).getString("newName").equals("запись_006.txt")
                && Files.exists(root.resolve("source1.txt")) && !Files.exists(root.resolve("запись_006.txt")));
        Files.writeString(root.resolve("запись_007.txt"), "other");
        JSONObject apply = json(Test.post("/api/rename-apply?id=" + plan));
        check("коллизия между предпросмотром и применением даёт частичный результат", apply.getInt("renamed") == 1 && apply.getInt("failed") == 1
                && Files.readString(root.resolve("запись_007.txt")).equals("other") && Files.exists(root.resolve("source2.txt")));
        check("предпросмотр одноразовый", Test.post("/api/rename-apply?id=" + plan).status == 409);
    }

    private void search() throws Exception {
        Path scope = Files.createDirectory(root.resolve("filtered"));
        Path good = Files.write(scope.resolve("good.JPG"), new byte[65]);
        Files.write(scope.resolve("tiny.jpg"), new byte[3]);
        Files.write(scope.resolve("typed.txt"), new byte[65]);
        Path old = Files.write(scope.resolve("old.jpg"), new byte[65]);
        Files.setLastModifiedTime(good, FileTime.fromMillis(1_800_000_000_000L));
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_700_000_000_000L));
        Files.createSymbolicLink(scope.resolve("external.jpg"), other.resolve("keep.txt"));
        Path sub = Files.createDirectory(scope.resolve("nested"));
        Files.copy(good, sub.resolve("nested.jpg"), StandardCopyOption.COPY_ATTRIBUTES);
        String filter = "/api/search?path=/filtered&type=image&ext=jpg&minBytes=5&maxBytes=65&after=1799999999999&before=1800000000001";
        JSONArray deep = json(Test.get(filter)).getJSONArray("items"), local = json(Test.get(filter + "&deep=0")).getJSONArray("items");
        check("тип, расширение, размер и дата применены вместе, без symlink", deep.length() == 2 && local.length() == 1
                && local.getJSONObject(0).getString("name").equals("good.JPG"));
        check("точный поиск не расширяет корень и валидирует диапазон", Test.get("/api/search?path=/../selected-other&q=keep").status != 200
                && Test.get("/api/search?path=/&minBytes=10&maxBytes=1").status == 400);
    }

    private void scans() throws Exception {
        Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
        Files.writeString(a.resolve("shared.txt"), "ABC"); Files.writeString(b.resolve("shared.txt"), "ABC");
        Files.writeString(a.resolve("copy.txt"), "ABC"); Files.writeString(a.resolve("same-size.txt"), "XYZ");
        Files.writeString(b.resolve("same-size.txt"), "XYz"); Files.writeString(b.resolve("additional.txt"), "additional");
        JSONObject compared = waitScan(scan("compare", "/A", "/B").getString("id"));
        check("SHA-256 сравнение: совпадающие, отсутствующие, изменённые, добавленные", compared.getInt("same") == 1
                && compared.getJSONArray("items").length() == 3 && compared.toString().contains("changed")
                && compared.toString().contains("missing") && compared.toString().contains("added"));
        String job = scan("duplicates", "/A", null).getString("id");
        JSONArray matches = waitScan(job).getJSONArray("groups");
        check("дубли определяются по байтам, не по имени или размеру", matches.length() == 1 && matches.getJSONObject(0).getJSONArray("paths").length() == 2
                && !matches.toString().contains("same-size.txt") && Files.exists(a.resolve("copy.txt")));
        check("дубль нельзя удалить без проверенной копии", Test.post("/api/duplicate-trash?id=" + job + "&path=/A/copy.txt&keep=/A/same-size.txt").status == 400);
        check("выбранный дубль уходит в корзину, вторая копия сохраняется", Test.post("/api/duplicate-trash?id=" + job + "&path=/A/copy.txt&keep=/A/shared.txt").status == 200
                && !Files.exists(a.resolve("copy.txt")) && Files.exists(a.resolve("shared.txt")));
        Path big = Files.createDirectory(root.resolve("big"));
        boolean large = "1".equals(System.getenv("PB_TEST_LARGE"));
        long size = large ? (5L << 30) + 1 : (64L << 20) + 1;
        try (RandomAccessFile f = new RandomAccessFile(big.resolve("sparse.bin").toFile(), "rw")) { f.setLength(size); f.seek(size - 1); f.write(7); }
        start(root, "scan-read-only", true);
        JSONObject manifest = waitScan(scan("manifest", "/big", null).getString("id"));
        JSONObject f = manifest.getJSONArray("files").getJSONObject(0);
        check("папочный хеш в read-only, потоковое чтение " + size + " байт", f.getLong("size") == size && f.getString("sha256").matches("[a-f0-9]{64}"));
        check("read-only: разрешён отчёт, но не перенос дублей и не чтение корзины", Test.post("/api/duplicate-trash?id=" + job).status == 403 && Test.get("/api/trash").status == 403);
        check("папочный хеш остаётся внутри области", Test.post("/api/scan-start?mode=manifest&path=/A/../../selected-other").status == 400);
    }

    private String init(String id, String path, String name, long bytes, String sha) {
        return "/api/upload-init?id=" + id + "&scope=" + scope + "&path=" + enc(path) + "&name=" + enc(name) + "&size=" + bytes + "&sha256=" + sha;
    }
    private static String status(String id) { return "/api/upload-status?id=" + id; }
    private static String chunk(String id, long offset) { return "/api/upload-chunk?id=" + id + "&offset=" + offset; }
    private static String id() { return "r" + UUID.randomUUID().toString().replace("-", ""); }
    private static String digest(byte[] b) throws Exception { return ManagedFiles.hex(MessageDigest.getInstance("SHA-256").digest(b)); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static void check(String text, boolean ok) { Test.check("Выбранные 4.x: " + text, ok, ""); }
    private static JSONObject json(Test.Resp response) throws Exception {
        if (response.status != 200) throw new AssertionError("HTTP " + response.status + ": " + response.text());
        return new JSONObject(response.text());
    }
    private static JSONObject scan(String mode, String path, String other) throws Exception {
        return json(Test.post("/api/scan-start?mode=" + mode + "&path=" + enc(path) + (other == null ? "" : "&other=" + enc(other))));
    }
    private static JSONObject waitScan(String id) throws Exception {
        for (int i = 0; i < 600; i++) {
            JSONObject current = json(Test.get("/api/scan?id=" + id + "&result=1"));
            if (current.getString("state").equals("done")) return current.getJSONObject("result");
            if (!current.getString("state").equals("running")) throw new AssertionError(current.toString());
            Thread.sleep(100);
        }
        throw new AssertionError("scan timeout");
    }
    private static void sendPartial(Socket socket, String path, int declared, byte[] data) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nX-Access-Key: " + Test.KEY + "\r\nContent-Length: " + declared + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data); out.flush();
    }
    private static void dropReply(String path, int declared, byte[] data) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", Test.PORT)) { sendPartial(socket, path, declared, data); socket.shutdownOutput(); }
    }
    private static long waitReceived(String id, long expected) throws Exception {
        long n = -1;
        for (int i = 0; i < 100; i++) {
            n = json(Test.get(status(id))).getLong("received");
            if (n == expected) break;
            Thread.sleep(20);
        }
        return n;
    }
    private static final class TestThrows {
        static boolean unavailable(File base, String selected) {
            try { StorageRoot.resolve(base, selected); return false; }
            catch (IOException ok) { return true; }
        }
    }
}
