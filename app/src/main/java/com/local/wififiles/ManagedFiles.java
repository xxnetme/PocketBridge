package com.local.wififiles;

import android.system.ErrnoException;
import android.system.Os;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongConsumer;

/** Common path, journal and streaming integrity checks for managed data. */
final class ManagedFiles {
    static final String TRASH = ".pocketbridge-trash";
    static final String DRAFTS = ".pocketbridge-uploads";
    static final Object NAMESPACE = new Object();
    static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final String MARKER = "PocketBridge-managed-1";

    private ManagedFiles() {}

    static FileServer.ApiException error(int status, String text) {
        return new FileServer.ApiException(status, text);
    }

    static boolean hiddenName(String name) {
        String lowercase = name.toLowerCase(Locale.ROOT);
        return TRASH.equals(lowercase) || DRAFTS.equals(lowercase)
                || (lowercase.startsWith(".wifi-upload-") && lowercase.endsWith(".part"));
    }

    static boolean hiddenPath(Path path) {
        for (Path item : path) if (hiddenName(item.toString())) return true;
        return false;
    }

    static void publicPath(Path path) throws IOException {
        if (hiddenPath(path)) throw error(400, "Недоступный служебный путь");
    }

    /** Strict virtual paths for operations that keep and revalidate a user-selected entry. */
    static Path scoped(Path root, String raw) throws IOException {
        if (raw == null || !raw.startsWith("/") || raw.indexOf('\0') >= 0) throw error(400, "Недопустимый путь");
        Path path = root.resolve(raw.substring(1)).normalize();
        if (!path.startsWith(root) || !virtual(root, path).equals(raw)) throw error(400, "Недопустимый путь");
        publicPath(root.relativize(path));
        if (!Files.exists(path, NOFOLLOW)) throw error(404, "Объект не найден");
        if (!path.equals(path.toRealPath())) throw error(400, "Символические ссылки не поддерживаются");
        return path;
    }

    static String virtual(Path root, Path file) { return "/" + root.relativize(file).toString().replace(File.separatorChar, '/'); }

    static BasicFileAttributes attrs(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
    }

    static String entryKey(Path path) throws IOException {
        Object id = attrs(path).fileKey();
        return id == null ? "" : id.toString();
    }

    static boolean sameEntry(BasicFileAttributes a, BasicFileAttributes b) {
        return a.isDirectory() == b.isDirectory() && a.isRegularFile() == b.isRegularFile()
                && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
                && Objects.equals(a.fileKey(), b.fileKey());
    }

    static void movableTree(Path path) throws IOException {
        if (!Files.isDirectory(path, NOFOLLOW)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            int count;
            private void check(Path p) throws IOException {
                if (Thread.currentThread().isInterrupted() || ++count > 50_000) throw error(413, "Слишком большая папка");
                if (hiddenName(p.getFileName().toString())) throw error(409, "Папка содержит служебные файлы PocketBridge");
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                check(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                check(file); return FileVisitResult.CONTINUE;
            }
        });
    }

    static Path directory(Path path) throws IOException {
        if (!Files.isDirectory(path, NOFOLLOW) || !path.equals(path.toRealPath())) {
            throw error(409, "Папка недоступна или изменилась");
        }
        return path;
    }

    static Path area(Path root, String name, boolean create) throws IOException {
        directory(root);
        Path area = root.resolve(name), marker = area.resolve("owner");
        if (!Files.exists(area, NOFOLLOW)) {
            if (!create) return null;
            try {
                Files.createDirectory(area);
                Files.write(marker, MARKER.getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException raced) {
                // A concurrent local operation may have created it; verify below.
            }
        }
        directory(area);
        if (!Files.isRegularFile(marker, NOFOLLOW) || !MARKER.equals(readSmall(marker, 128))) {
            throw error(409, "Служебная папка недоступна или уже занята");
        }
        return area;
    }

    static String readSmall(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, NOFOLLOW)) throw new IOException("Missing managed record");
        try (InputStream in = Files.newInputStream(path, NOFOLLOW); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (b.size() + n > limit) throw new IOException("Managed record too big");
                b.write(buf, 0, n);
            }
            return b.toString("UTF-8");
        }
    }

    static JSONObject readRecord(Path path) throws IOException {
        try { return new JSONObject(readSmall(path, 32 * 1024)); }
        catch (org.json.JSONException invalid) { throw new IOException("Invalid managed record", invalid); }
    }

    /** Same-directory replacement; fail instead of publishing an uncommitted record. */
    static void writeRecord(Path path, JSONObject record) throws IOException {
        directory(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), "record-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.write(record.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    /** Refuse a cross-device copy during publication or restore. */
    static void sameVolume(Path from, Path targetParent) throws IOException {
        try {
            if (Os.stat(from.toString()).st_dev != Os.stat(targetParent.toString()).st_dev) {
                throw error(409, "Источник и папка назначения находятся на разных томах");
            }
        } catch (ErrnoException unresolved) { throw error(409, "Не удалось проверить том назначения"); }
    }

    static String sha256(Path path, long deadline) throws IOException { return sha256(path, deadline, null); }

    static String sha256(Path path, long deadline, LongConsumer progress) throws IOException {
        if (!path.equals(path.toRealPath())) throw error(409, "Путь файла изменился");
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        if (!before.isRegularFile()) throw error(400, "Нужен обычный файл");
        MessageDigest digest = digest();
        long bytes = 0;
        try (InputStream in = Files.newInputStream(path, NOFOLLOW)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                bytes += n;
                if (bytes > before.size() || Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
                    throw error(409, "Проверка прервана: файл изменился или превышен лимит времени");
                }
                digest.update(buf, 0, n);
                if (progress != null) progress.accept(bytes);
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        if (!after.isRegularFile() || bytes != before.size() || bytes != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()) || !path.equals(path.toRealPath())) {
            throw error(409, "Файл изменился при проверке");
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String hashText(String text) { return hex(digest().digest(text.getBytes(StandardCharsets.UTF_8))); }

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = digits.charAt((bytes[i] >>> 4) & 15);
            out[2 * i + 1] = digits.charAt(bytes[i] & 15);
        }
        return new String(out);
    }
}
