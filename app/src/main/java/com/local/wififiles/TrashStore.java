package com.local.wififiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static com.local.wififiles.ManagedFiles.*;

/** Soft deletion on the same filesystem. Unknown/corrupt entries are never purged. */
final class TrashStore {
    static final long CAPACITY = 20L * 1024 * 1024 * 1024;
    static final long TTL = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_NODES = 50_000;
    private final Path root;

    TrashStore(File root) { this.root = root.toPath(); }

    JSONObject put(File file) throws Exception {
        synchronized (NAMESPACE) {
            Path source = file.toPath();
            if (source.equals(root) || !source.startsWith(root) || !source.equals(source.toRealPath())) {
                throw error(400, "Нельзя переместить этот объект в корзину");
            }
            publicPath(root.relativize(source));
            // Bound filesystem work and never move a tree containing another profile's managed data.
            final long[] size = {0};
            final int[] nodes = {0};
            Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                private void count(Path p, long bytes) throws IOException {
                    if (Thread.currentThread().isInterrupted() || ++nodes[0] > MAX_NODES || bytes > CAPACITY - size[0]) throw error(413, "Объект не помещается в корзину");
                    if (hiddenName(p.getFileName().toString())) throw error(409, "Папка содержит служебные файлы PocketBridge");
                    size[0] += bytes;
                }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    count(dir, 0);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() && !attrs.isSymbolicLink()) throw error(400, "Тип файла не поддерживается");
                    count(p, attrs.size());
                    return FileVisitResult.CONTINUE;
                }
            });
            Path home = area(root, TRASH, true);
            long now = System.currentTimeMillis();
            List<JSONObject> entries = scan(home, now);
            long used = 0;
            for (JSONObject entry : entries) used += entry.getLong("size");
            if (entries.size() >= MAX_ENTRIES || size[0] > CAPACITY - used) {
                throw error(409, "Корзина заполнена. Освободите место; файл не удалён");
            }
            String id = UUID.randomUUID().toString();
            Path slot = Files.createDirectory(home.resolve(id));
            JSONObject record = new JSONObject().put("id", id).put("path", virtual(source))
                    .put("name", source.getFileName().toString()).put("size", size[0])
                    .put("dir", Files.isDirectory(source, NOFOLLOW)).put("deletedAt", now).put("expiresAt", now + TTL);
            boolean moved = false;
            try {
                writeRecord(slot.resolve("meta.json"), record);
                if (!source.equals(source.toRealPath()) || !slot.equals(slot.toRealPath())) throw error(409, "Путь изменился");
                Files.move(source, slot.resolve("data"), StandardCopyOption.ATOMIC_MOVE);
                moved = true;
                return record;
            } finally {
                if (!moved && !Files.exists(slot.resolve("data"), NOFOLLOW)) {
                    Files.deleteIfExists(slot.resolve("meta.json"));
                    Files.deleteIfExists(slot);
                }
            }
        }
    }

    void cleanup() throws Exception {
        synchronized (NAMESPACE) {
            if (Thread.currentThread().isInterrupted()) return;
            Path home = area(root, TRASH, false);
            if (home != null) scan(home, System.currentTimeMillis());
        }
    }

    JSONObject list() throws Exception {
        synchronized (NAMESPACE) {
            Path home = area(root, TRASH, false);
            List<JSONObject> entries = home == null ? new ArrayList<>() : scan(home, System.currentTimeMillis());
            entries.sort((a, b) -> Long.compare(b.optLong("deletedAt"), a.optLong("deletedAt")));
            long used = 0;
            for (JSONObject e : entries) used += e.getLong("size");
            return new JSONObject().put("items", new JSONArray(entries)).put("usedBytes", used)
                    .put("limitBytes", CAPACITY).put("ttlMillis", TTL);
        }
    }

    JSONObject restore(String id, String newName, String destination) throws Exception {
        synchronized (NAMESPACE) {
            Path slot = require(id);
            JSONObject entry = read(slot);
            if (entry.getLong("expiresAt") <= System.currentTimeMillis()) throw error(404, "Срок хранения истёк");
            Path original = root.resolve(entry.getString("path").substring(1)).normalize();
            Path parent = destination == null ? original.getParent() : scoped(root, destination);
            if (parent == null || !parent.startsWith(root)) throw error(400, "Недопустимая папка");
            publicPath(root.relativize(directory(parent)));
            String name = newName == null ? entry.getString("name") : newName;
            if (!(newName == null ? validOriginalName(name) : validName(name))) throw error(400, "Недопустимое имя");
            Path target = parent.resolve(name);
            if (Files.exists(target, NOFOLLOW)) throw error(409, "Имя занято. Выберите другое имя");
            Path data = slot.resolve("data");
            if (!data.equals(data.toRealPath())) throw error(409, "Содержимое корзины недоступно");
            sameVolume(data, parent);
            Files.move(data, target);
            retire(slot);
            return new JSONObject().put("path", virtual(target));
        }
    }

    void purge(String id) throws Exception {
        synchronized (NAMESPACE) { purgeSlot(require(id)); }
    }

    private List<JSONObject> scan(Path home, long now) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(home)) {
            for (Path slot : dirs) {
                JSONObject entry;
                try { entry = read(slot); } catch (Exception invalid) { continue; }
                if (entry.getLong("expiresAt") <= now) {
                    try { purgeSlot(slot); continue; } catch (IOException notRemoved) { /* keep listed */ }
                }
                result.add(entry);
            }
        }
        return result;
    }

    private Path require(String id) throws Exception {
        if (!idValid(id)) throw error(400, "Недопустимый ID объекта корзины");
        Path home = area(root, TRASH, false);
        if (home == null) throw error(404, "Объект корзины не найден");
        Path slot = home.resolve(id);
        JSONObject entry;
        try { entry = read(slot); } catch (Exception invalid) { throw error(404, "Объект корзины не найден"); }
        return slot;
    }

    private JSONObject read(Path slot) throws Exception {
        if (!idValid(slot.getFileName().toString())) throw new IOException("Unknown trash entry");
        directory(slot);
        Path data = slot.resolve("data");
        JSONObject entry = readRecord(slot.resolve("meta.json"));
        String path = entry.getString("path"), name = entry.getString("name");
        Path orig = root.resolve(stripSlash(path)).normalize();
        if (!orig.startsWith(root) || orig.equals(root) || !virtual(orig).equals(path)
                || !name.equals(orig.getFileName().toString()) || !validOriginalName(name)
                || !slot.getFileName().toString().equals(entry.getString("id"))
                || !(Files.isDirectory(data, NOFOLLOW) || Files.isRegularFile(data, NOFOLLOW))
                || entry.getLong("size") < 0 || entry.getLong("size") > CAPACITY
                || entry.getLong("expiresAt") != entry.getLong("deletedAt") + TTL) throw new IOException("Invalid trash entry");
        publicPath(root.relativize(orig));
        return entry;
    }

    private void purgeSlot(Path slot) throws IOException {
        directory(slot);
        Path data = slot.resolve("data");
        if (!data.equals(data.toRealPath())) throw new IOException("Trash data moved");
        Files.walkFileTree(data, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        retire(slot);
    }

    private static void retire(Path slot) {
        try { Files.deleteIfExists(slot.resolve("meta.json")); Files.deleteIfExists(slot); }
        catch (IOException ignored) { /* successful move is not undone by failed metadata cleanup */ }
    }

    private String virtual(Path path) { return "/" + root.relativize(path).toString().replace(File.separatorChar, '/'); }
    private static String stripSlash(String path) { return path.replaceFirst("^/+", ""); }
    private static boolean idValid(String id) { return id != null && id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"); }
    private static boolean validOriginalName(String name) { return !name.isEmpty() && !hiddenName(name); }
    static boolean validName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && !name.contains("/") && !name.contains("\\") && name.indexOf('\0') < 0 && name.length() <= 200
                && !hiddenName(name);
    }
}
