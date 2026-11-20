package com.local.wififiles;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static com.local.wififiles.ManagedFiles.NOFOLLOW;
import static com.local.wififiles.ManagedFiles.error;
import static com.local.wififiles.ManagedFiles.hiddenName;

/** Resolves WebDAV paths without following links, including paths used to create new entries. */
final class DavFiles {
    private static final int MAX_TREE_NODES = 50_000;
    private final Path root;

    DavFiles(File canonicalRoot) { root = canonicalRoot.toPath(); }

    File resolve(String path) throws IOException {
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0) throw forbidden();
        ManagedFiles.directory(root);
        Path cursor = root;
        boolean reachable = true;
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment) || hiddenName(segment)) throw forbidden();
            cursor = cursor.resolve(segment);
            if (reachable) {
                try {
                    BasicFileAttributes attrs = ManagedFiles.attrs(cursor);
                    if (attrs.isSymbolicLink()) throw link();
                    reachable = attrs.isDirectory();
                } catch (NoSuchFileException missing) {
                    // A missing tail is valid for PUT, MKCOL, LOCK and Destination.
                    reachable = false;
                }
            }
        }
        return cursor.toFile();
    }

    static boolean visible(File file) {
        return !hiddenName(file.getName()) && !Files.isSymbolicLink(file.toPath());
    }

    static void checkTree(Path source, boolean skipManaged) throws IOException { walkTree(source, null, skipManaged); }

    static void copyTree(Path source, Path target, boolean skipManaged) throws IOException { walkTree(source, target, skipManaged); }

    /** Preflight or copy the same visible tree; never turn a MOVE fallback into a partial copy. */
    private static void walkTree(Path source, Path target, boolean skipManaged) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            int nodes;

            private boolean include(Path entry, BasicFileAttributes attrs) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw error(503, "Доступ остановлен");
                if (!skipManaged && ++nodes > MAX_TREE_NODES) throw error(413, "Слишком большая папка");
                if (hiddenName(entry.getFileName().toString())) {
                    if (skipManaged) return false;
                    throw error(409, "Папка содержит служебные файлы PocketBridge");
                }
                if (attrs.isSymbolicLink()) throw link();
                if (!attrs.isDirectory() && !attrs.isRegularFile()) throw error(400, "Тип файла не поддерживается");
                return true;
            }

            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!include(dir, attrs)) return FileVisitResult.SKIP_SUBTREE;
                if (target != null) Files.createDirectory(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (include(file, attrs) && target != null) {
                    try (InputStream input = Files.newInputStream(file, NOFOLLOW)) {
                        Files.copy(input, target.resolve(source.relativize(file)));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** For overwrite and a completed MOVE fallback, not the user-facing DELETE (which uses trash). */
    static void removeTree(Path entry) throws IOException {
        Files.walkFileTree(entry, new SimpleFileVisitor<Path>() {
            private void check(Path path) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw error(503, "Доступ остановлен");
                if (hiddenName(path.getFileName().toString())) throw error(409, "Папка изменилась");
            }

            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                check(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                check(file);
                if (attrs.isSymbolicLink()) throw link();
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static FileServer.ApiException forbidden() { return error(403, "Путь вне доступного хранилища"); }
    private static FileServer.ApiException link() { return error(403, "Символические ссылки WebDAV не поддерживаются"); }
}
