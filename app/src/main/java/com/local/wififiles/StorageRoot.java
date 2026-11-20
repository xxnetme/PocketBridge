package com.local.wififiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Корень файловой сессии: относительная папка внутри основного общего хранилища. */
final class StorageRoot {
    private StorageRoot() {}

    static File resolve(File storage, String selection) throws IOException {
        if (selection == null || selection.indexOf('\\') >= 0 || selection.indexOf('\0') >= 0) {
            throw unavailable();
        }
        Path base = storage.toPath().toRealPath();
        Path path = base;
        if (!selection.isEmpty()) {
            for (String part : selection.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..") || ManagedFiles.hiddenName(part)) throw unavailable();
                path = path.resolve(part);
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            }
        }
        Path real = path.toRealPath();
        if (!path.equals(real) || !real.startsWith(base) || !Files.isDirectory(real) || !Files.isReadable(real)) {
            throw unavailable();
        }
        return real.toFile();
    }

    private static IOException unavailable() { return new IOException("Shared folder is unavailable"); }
}
