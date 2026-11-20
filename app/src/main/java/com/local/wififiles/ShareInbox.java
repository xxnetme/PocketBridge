package com.local.wififiles;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Приём файлов телефона: потоковая запись во временный файл, затем публикация в Inbox без перезаписи. */
final class ShareInbox {
    static final String DIRECTORY = "PocketBridge Inbox";
    static final int MAX_FILES = 32;
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private ShareInbox() {}

    static final class Imported {
        final String name;
        final long bytes;
        Imported(String name, long bytes) { this.name = name; this.bytes = bytes; }
    }

    static final class TooLarge extends IOException {}

    static Imported copy(File storage, String selection, String displayName, InputStream input, long budget)
            throws IOException {
        Path root = StorageRoot.resolve(storage, selection).toPath();
        Path inbox = root.resolve(DIRECTORY);
        try { Files.createDirectory(inbox); }
        catch (FileAlreadyExistsException alreadyExists) { /* checked below */ }
        checkDirectory(inbox);
        Path temp = Files.createTempFile(inbox, ".wifi-upload-", ".part");
        long total = 0L;
        long limit = Math.min(MAX_BYTES, budget);
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = input.read(buffer)) != -1; ) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if (n > limit - total) throw new TooLarge();
                    out.write(buffer, 0, n);
                    total += n;
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (!root.equals(StorageRoot.resolve(storage, selection).toPath())) throw new IOException("Shared folder changed");
            checkDirectory(inbox);
            return new Imported(publish(temp, inbox, safeName(displayName)), total);
        } finally {
            try {
                checkDirectory(inbox);
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {}
        }
    }

    private static void checkDirectory(Path inbox) throws IOException {
        if (!Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS) || !inbox.equals(inbox.toRealPath())) {
            throw new IOException("Inbox is unavailable");
        }
    }

    private static synchronized String publish(Path temp, Path inbox, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; i <= 999; i++) {
            String candidate = i == 0 ? name : stem + " (" + i + ")" + ext;
            try {
                Files.move(temp, inbox.resolve(candidate)); // no REPLACE_EXISTING
                return candidate;
            } catch (FileAlreadyExistsException ignored) {}
        }
        throw new IOException("Too many files with this name");
    }

    private static String safeName(String displayName) {
        if (displayName == null) return "Shared file";
        String name = displayName.trim().replace('\\', '_').replaceAll("[/\\p{Cntrl}<>:\"|?*]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "Shared file";
        if (name.startsWith(".wifi-upload-")) name = "Shared " + name;
        // Limit UTF-8 bytes, leaving room for the collision suffix on Android's filesystem.
        if (name.getBytes(StandardCharsets.UTF_8).length <= 180) return name;
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
        String stem = ext.isEmpty() ? name : name.substring(0, dot);
        int end = Math.min(stem.length(), 180);
        while ((stem.substring(0, end) + ext).getBytes(StandardCharsets.UTF_8).length > 180) {
            end = stem.offsetByCodePoints(end, -1);
        }
        if (end > 0 && Character.isHighSurrogate(stem.charAt(end - 1))) end--;
        return (end > 0 ? stem.substring(0, end) : "Shared file") + ext;
    }
}
