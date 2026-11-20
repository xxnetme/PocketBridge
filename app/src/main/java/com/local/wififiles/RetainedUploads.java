package com.local.wififiles;

import org.json.JSONObject;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.local.wififiles.ManagedFiles.*;

/** Checkpointed uploads. Private records and same-volume bodies outlive the listener. */
final class RetainedUploads {
    static final long MAX_BYTES = 20L * 1024 * 1024 * 1024;
    static final int CHUNK = 4 * 1024 * 1024;
    static final long TTL = 24L * 60 * 60 * 1000;
    private static final long FREE_RESERVE = 32L * 1024 * 1024;
    private static final int MAX_PENDING = 32;
    private static final int MAX_RECORDS = 256;
    private static final Object CREATE = new Object();
    private static final Object[] LOCKS = new Object[64];
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object(); }
    private final Path root, home;
    private volatile String scope;
    private volatile boolean closed;

    RetainedUploads(File root, File privateFiles) throws IOException {
        this.root = root.toPath();
        this.home = privateFiles.getCanonicalFile().toPath().resolve("upload-journal-v1").resolve(hashText(root.getPath()));
    }

    static boolean isId(String id) { return id != null && id.matches("r[0-9a-f]{32}"); }
    static boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private Object jobLock(String id) throws IOException {
        if (!isId(id)) throw error(400, "Недопустимый ID загрузки");
        if (scope() == null) throw error(503, "Хранилище черновиков недоступно");
        return lock(id);
    }
    private Object lock(String id) { return LOCKS[(id.hashCode() & Integer.MAX_VALUE) % LOCKS.length]; }
    void stop() { closed = true; }

    String scope() {
        String current = scope;
        if (current != null) return current;
        synchronized (CREATE) {
            if (scope != null) return scope;
            try {
                Files.createDirectories(home);
                directory(home);
                Path file = home.resolve("scope.json");
                if (!Files.exists(file, NOFOLLOW)) writeRecord(file, new JSONObject().put("scope", UUID.randomUUID().toString()));
                String id = readRecord(file).getString("scope");
                if (!id.matches("[0-9a-f-]{36}")) return null;
                scope = id;
                return id;
            } catch (Exception unavailable) { return null; }
        }
    }

    void cleanup() {
        if (!Files.isDirectory(home, NOFOLLOW) || closed) return;
        synchronized (CREATE) {
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory(home), "r*.json")) {
                for (Path path : paths) {
                    if (closed) return;
                    String name = path.getFileName().toString();
                    String id = name.substring(0, name.length() - 5);
                    if (!isId(id)) continue;
                    try {
                        if (!expired(readRecord(path))) continue;
                        synchronized (lock(id)) {
                            if (!closed && Files.exists(path, NOFOLLOW) && expired(readRecord(path))) remove(id);
                        }
                    } catch (IOException ignored) { /* Keep unknown/corrupt records. */ }
                }
            } catch (IOException ignored) {}
        }
    }

    JSONObject init(String id, File parent, String requested, long size, String digest,
                    boolean overwrite, boolean unique) throws Exception {
        if (!isId(id) || !isDigest(digest) || !TrashStore.validName(requested) || size < 0 || size > MAX_BYTES) {
            throw error(400, "Некорректные параметры сохранённой загрузки");
        }
        if (scope() == null) throw error(503, "Хранилище черновиков недоступно");
        synchronized (CREATE) {
            long reserved = 0;
            int active = 0, total = 0;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(home, "r*.json")) {
                for (Path f : files) {
                    String name = f.getFileName().toString();
                    String jobId = name.substring(0, name.length() - 5);
                    if (!isId(jobId)) continue;
                    JSONObject r;
                    try { r = readRecord(f); } catch (IOException invalid) { total++; continue; }
                    if (expired(r)) {
                        synchronized (lock(jobId)) {
                            if (!Files.exists(f, NOFOLLOW)) continue;
                            r = readRecord(f);
                            if (expired(r)) { remove(jobId); continue; }
                        }
                    }
                    total++;
                    if (!r.optBoolean("done")) {
                        active++;
                        reserved += r.optLong("size", MAX_BYTES);
                    }
                }
            }
            // All scopes on Android use the same primary volume. Include non-expired reservations
            // from previously selected roots, without opening or deleting their shared storage.
            try (DirectoryStream<Path> scopes = Files.newDirectoryStream(home.getParent())) {
                for (Path other : scopes) {
                    if (other.equals(home) || !other.getFileName().toString().matches("[a-f0-9]{64}")) continue;
                    try (DirectoryStream<Path> records = Files.newDirectoryStream(directory(other), "r*.json")) {
                        for (Path f : records) {
                            String name = f.getFileName().toString();
                            if (!isId(name.substring(0, name.length() - 5))) continue;
                            try {
                                JSONObject stored = readRecord(f);
                                if (expired(stored) || stored.optBoolean("done")) continue;
                                active++;
                                reserved = Math.min(MAX_BYTES, reserved + Math.max(0L, Math.min(MAX_BYTES, stored.optLong("size", MAX_BYTES))));
                            } catch (IOException invalid) { /* do not trust damaged metadata */ }
                        }
                    }
                }
            }
            synchronized (jobLock(id)) {
                activeServer();
                Path folder = directory(parent.toPath());
                if (!folder.startsWith(root)) throw error(400, "Папка вне общей области");
                publicPath(root.relativize(folder));
                String path = virtual(folder);
                if (Files.exists(record(id), NOFOLLOW)) {
                    JSONObject old = require(id);
                    if (!path.equals(old.getString("parent")) || size != old.getLong("size")
                            || !digest.equals(old.getString("sha256")) || !requested.equals(old.getString("requested"))) {
                        throw error(409, "Идентификатор относится к другой загрузке");
                    }
                    return describe(id, old);
                }
                if (active >= MAX_PENDING || total >= MAX_RECORDS || size > MAX_BYTES - reserved) {
                    throw error(429, "Лимит черновиков. Удалите ненужную загрузку");
                }
                Path store = area(root, DRAFTS, true);
                requireSpace(store, size);
                Path target = folder.resolve(requested);
                if (unique && Files.exists(target, NOFOLLOW)) target = uniqueName(folder, requested);
                String previous = expectedTarget(target, overwrite && !unique);
                JSONObject entry = new JSONObject().put("scope", scope).put("parent", path)
                        .put("requested", requested).put("name", target.getFileName().toString())
                        .put("sha256", digest).put("previous", previous).put("size", size)
                        .put("parentKey", entryKey(folder)).put("rootKey", entryKey(root))
                        .put("received", 0).put("expiresAt", System.currentTimeMillis() + TTL);
                if (Files.exists(store.resolve(id + ".part"), NOFOLLOW)) throw error(409, "Место для черновика занято");
                writeRecord(record(id), entry);
                return describe(id, entry);
            }
        }
    }

    JSONObject status(String id) throws Exception {
        synchronized (jobLock(id)) { return describe(id, require(id)); }
    }

    JSONObject chunk(String id, long offset, int length, InputStream in) throws Exception {
        synchronized (jobLock(id)) {
            activeServer();
            JSONObject entry = require(id);
            recoverPublished(id, entry);
            long received = entry.getLong("received");
            if (entry.optBoolean("done") || offset != received) throw offset(received);
            if (length <= 0 || length > CHUNK || length > entry.getLong("size") - received) {
                throw error(400, "Некорректный размер части сохранённой загрузки");
            }
            parent(entry); // A moved/deleted destination must not be replaced by the global root.
            Path file = body(id, true);
            requireSpace(file.getParent(), length);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE, NOFOLLOW)) {
                checkLength(ch, received);
                ch.position(received);
                byte[] buf = new byte[Math.min(65536, length)];
                int left = length;
                try {
                    while (left > 0) {
                        activeServer();
                        int n = in.read(buf, 0, Math.min(left, buf.length));
                        if (n < 0) throw new EOFException("Передача части прервана");
                        ByteBuffer bytes = ByteBuffer.wrap(buf, 0, n);
                        while (bytes.hasRemaining()) ch.write(bytes);
                        left -= n;
                    }
                    ch.force(true);
                    activeServer();
                    entry.put("received", received + length).put("expiresAt", System.currentTimeMillis() + TTL);
                    writeRecord(record(id), entry);
                } catch (Exception failed) {
                    ch.truncate(received);
                    ch.force(true);
                    throw failed;
                }
            }
            return describe(id, entry);
        }
    }

    JSONObject finish(String id) throws Exception {
        synchronized (jobLock(id)) {
            activeServer();
            JSONObject entry = require(id);
            recoverPublished(id, entry);
            if (!entry.optBoolean("done")) {
                long size = entry.getLong("size");
                if (entry.getLong("received") != size) throw offset(entry.getLong("received"));
                Path temp = body(id, true);
                try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE, NOFOLLOW)) { checkLength(ch, size); }
                if (!entry.getString("sha256").equals(sha256(temp, deadline()))) {
                    throw error(409, "SHA-256 не совпадает. Выберите исходный файл или отмените черновик");
                }
                Path folder = parent(entry);
                Path target = folder.resolve(entry.getString("name"));
                String previous = entry.getString("previous");
                boolean replace = !previous.isEmpty();
                java.nio.file.attribute.BasicFileAttributes oldTarget = replace && Files.isRegularFile(target, NOFOLLOW) ? attrs(target) : null;
                if (!previous.equals(expectedTarget(target, replace))) throw collision();
                synchronized (NAMESPACE) {
                    activeServer();
                    sameVolume(temp, parent(entry));
                    entry.put("publishing", true);
                    writeRecord(record(id), entry);
                    if (replace) {
                        if (oldTarget == null || !Files.isRegularFile(target, NOFOLLOW) || !sameEntry(oldTarget, attrs(target))) throw collision();
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        if (Files.exists(target, NOFOLLOW)) throw collision();
                        try { Files.move(temp, target); }
                        catch (FileAlreadyExistsException raced) { throw collision(); }
                    }
                }
                markDone(id, entry);
            }
            return describe(id, entry).put("ok", true);
        }
    }

    JSONObject retarget(String id, String name, boolean overwrite) throws Exception {
        if (!TrashStore.validName(name)) throw error(400, "Недопустимое имя");
        synchronized (jobLock(id)) {
            activeServer();
            JSONObject entry = require(id);
            recoverPublished(id, entry);
            if (entry.optBoolean("done")) return describe(id, entry);
            String previous = expectedTarget(parent(entry).resolve(name), overwrite);
            entry.put("name", name).put("previous", previous).put("publishing", false);
            writeRecord(record(id), entry);
            return describe(id, entry);
        }
    }

    JSONObject abort(String id) throws Exception {
        synchronized (jobLock(id)) {
            activeServer();
            JSONObject reply = new JSONObject().put("ok", true);
            if (!Files.exists(record(id), NOFOLLOW)) return reply;
            try {
                JSONObject entry = require(id);
                recoverPublished(id, entry);
                if (entry.optBoolean("done")) reply.put("done", true).put("name", entry.getString("name"));
            } catch (Exception invalid) { /* even a damaged draft can be explicitly discarded */ }
            remove(id);
            return reply;
        }
    }

    private JSONObject describe(String id, JSONObject entry) throws Exception {
        recoverPublished(id, entry);
        if (!entry.optBoolean("done")) {
            Path file = body(id, false);
            long received = entry.getLong("received");
            if (file == null && received > 0 || file != null && Files.size(file) < received) {
                throw error(409, "Файл черновика повреждён или отсутствует");
            }
        }
        return new JSONObject().put("uploadId", id).put("name", entry.getString("name"))
                .put("received", entry.getLong("received")).put("size", entry.getLong("size"))
                .put("expiresAt", entry.getLong("expiresAt")).put("done", entry.optBoolean("done"))
                .put("durable", true).put("chunk", CHUNK);
    }

    private void recoverPublished(String id, JSONObject entry) throws Exception {
        if (!entry.optBoolean("publishing") || entry.optBoolean("done") || body(id, false) != null) return;
        Path target = parent(entry).resolve(entry.getString("name"));
        if (!Files.isRegularFile(target, NOFOLLOW) || Files.size(target) != entry.getLong("size")
                || !entry.getString("sha256").equals(sha256(target, deadline()))) {
            throw error(409, "Не удалось подтвердить результат предыдущего сохранения");
        }
        markDone(id, entry);
    }

    private void markDone(String id, JSONObject entry) throws Exception {
        entry.put("done", true).put("publishing", false).put("expiresAt", System.currentTimeMillis() + TTL);
        writeRecord(record(id), entry);
    }

    private JSONObject require(String id) throws Exception {
        activeServer();
        Path path = record(id);
        if (!Files.exists(path, NOFOLLOW)) throw error(404, "Черновик не найден или срок хранения истёк");
        JSONObject r = readRecord(path);
        long size = r.getLong("size"), received = r.getLong("received");
        if (!r.getString("scope").equals(scope) || !sameKey(root, r.optString("rootKey")) || !isDigest(r.getString("sha256")) || size < 0 || size > MAX_BYTES
                || received < 0 || received > size || !TrashStore.validName(r.getString("name"))) {
            throw error(409, "Повреждена запись черновика");
        }
        if (expired(r)) {
            remove(id);
            throw error(404, "Срок хранения черновика истёк");
        }
        return r;
    }

    private Path parent(JSONObject entry) throws Exception {
        String path = entry.getString("parent");
        if (!path.startsWith("/")) throw error(400, "Недопустимый путь");
        Path folder = root.resolve(path.substring(1)).normalize();
        if (!folder.startsWith(root) || !virtual(folder).equals(path)) throw error(400, "Недопустимая папка");
        publicPath(root.relativize(folder));
        directory(folder);
        if (!sameKey(folder, entry.optString("parentKey"))) throw error(409, "Папка назначения была заменена");
        return folder;
    }

    private Path body(String id, boolean create) throws IOException {
        if (!isId(id)) throw error(400, "Недопустимый ID загрузки");
        Path folder = area(root, DRAFTS, create);
        if (folder == null) return null;
        Path file = folder.resolve(id + ".part");
        if (!Files.exists(file, NOFOLLOW)) {
            if (!create) return null;
            Files.createFile(file);
        }
        if (!Files.isRegularFile(file, NOFOLLOW) || !file.equals(file.toRealPath())) throw error(409, "Черновик недоступен");
        return file;
    }

    private void remove(String id) throws IOException {
        Path file = body(id, false);
        if (file != null) Files.delete(file);
        Files.deleteIfExists(record(id));
    }

    private Path record(String id) throws IOException {
        if (!isId(id)) throw error(400, "Недопустимый ID загрузки");
        return home.resolve(id + ".json");
    }

    private static Path uniqueName(Path dir, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, suffix = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 1000; i++) {
            String next = stem + " (" + i + ")" + suffix;
            if (!TrashStore.validName(next)) break;
            Path p = dir.resolve(next);
            if (!Files.exists(p, NOFOLLOW)) return p;
        }
        throw error(409, "Не удалось подобрать имя");
    }

    private static String expectedTarget(Path target, boolean overwrite) throws IOException {
        if (!Files.exists(target, NOFOLLOW)) return "";
        if (!overwrite || !Files.isRegularFile(target, NOFOLLOW) || Files.size(target) > MAX_BYTES) {
            throw collision();
        }
        return sha256(target, deadline());
    }

    private static boolean sameKey(Path path, String expected) throws IOException {
        return expected.isEmpty() || expected.equals(entryKey(path));
    }

    private static void checkLength(FileChannel ch, long received) throws IOException {
        if (ch.size() < received) throw error(409, "Черновик короче сохранённого прогресса");
        if (ch.size() > received) ch.truncate(received);
    }

    private static void requireSpace(Path path, long bytes) throws IOException {
        long usable = path.toFile().getUsableSpace();
        if (usable < FREE_RESERVE || bytes > usable - FREE_RESERVE) throw error(507, "Недостаточно свободного места");
    }

    private void activeServer() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw error(503, "Доступ остановлен");
    }
    private static FileServer.ApiException collision() {
        return new FileServer.ApiException(409, "Имя занято или файл назначения изменился",
                "X-PocketBridge-Upload-Conflict: 1\r\n");
    }
    private static FileServer.ApiException offset(long bytes) {
        return new FileServer.ApiException(409, "Ожидается смещение " + bytes, "X-Upload-Received: " + bytes + "\r\n");
    }
    private static boolean expired(JSONObject r) { return r.optLong("expiresAt", 0) <= System.currentTimeMillis(); }
    private static long deadline() { return System.nanoTime() + TimeUnit.MINUTES.toNanos(10); }
    private String virtual(Path folder) { return "/" + root.relativize(folder).toString().replace(File.separatorChar, '/'); }
}
