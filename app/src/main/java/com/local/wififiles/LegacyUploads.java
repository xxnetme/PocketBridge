package com.local.wififiles;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static com.local.wififiles.ManagedFiles.NOFOLLOW;
import static com.local.wififiles.ManagedFiles.error;

/** Legacy, in-memory chunked uploads; retained drafts have their own journal and limits. */
final class LegacyUploads {
    static final long TTL_MS = TimeUnit.HOURS.toMillis(6);
    private static final int LIMIT = 32;
    private final Map<String, Upload> jobs = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private boolean closed;

    static final class Upload {
        final String id, name;
        final File parent, tmp;
        final long size, created;
        final boolean overwrite;
        volatile long received;
        private volatile boolean discarded;

        Upload(String id, String name, File parent, File tmp, long size, boolean overwrite, long created) {
            this.id = id;
            this.name = name;
            this.parent = parent;
            this.tmp = tmp;
            this.size = size;
            this.overwrite = overwrite;
            this.created = created;
        }

        void requireActive() throws FileServer.ApiException {
            if (discarded) throw missing();
            if (Thread.currentThread().isInterrupted()) throw error(503, "Доступ остановлен");
        }

        private void discard() {
            discarded = true;
            tmp.delete();
        }
    }

    LegacyUploads() { this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime())); }
    LegacyUploads(LongSupplier clock) { this.clock = clock; }

    /** Admission, temp creation and registration share one lock; no request body is read under it. */
    synchronized Upload create(File parent, String name, long size, boolean overwrite) throws IOException {
        checkOpen();
        expire();
        if (jobs.size() >= LIMIT) throw error(429, "Слишком много одновременных загрузок");
        ManagedFiles.directory(parent.toPath());
        byte[] randomId = new byte[12];
        String id;
        do {
            random.nextBytes(randomId);
            id = ManagedFiles.hex(randomId);
        } while (jobs.containsKey(id));
        File tmp = File.createTempFile(".wifi-upload-", ".part", parent);
        Upload upload = new Upload(id, name, parent, tmp, size, overwrite, clock.getAsLong());
        jobs.put(id, upload);
        return upload;
    }

    synchronized Upload require(String id) throws FileServer.ApiException {
        expire();
        Upload upload = jobs.get(id);
        if (upload == null) throw missing();
        return upload;
    }

    long append(Upload upload, long offset, int length, InputStream in) throws IOException {
        synchronized (upload) {
            upload.requireActive();
            if (offset != upload.received) {
                throw offsetConflict(upload, "Несовпадение смещения: ожидается " + upload.received);
            }
            if (upload.received + length > upload.size) throw error(400, "Часть выходит за размер файла");
            byte[] buffer = new byte[Math.min(65536, length)];
            int remaining = length;
            IOException readFailure = null;
            // No CREATE: a late chunk must not recreate a job removed by abort, expiry or stop.
            try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(upload.tmp.toPath(),
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND, NOFOLLOW))) {
                while (remaining > 0) {
                    int n;
                    try { n = in.read(buffer, 0, Math.min(buffer.length, remaining)); }
                    catch (IOException interrupted) { readFailure = interrupted; break; }
                    upload.requireActive();
                    if (n < 0) break;
                    file.write(buffer, 0, n);
                    remaining -= n;
                }
            } catch (IOException writeFailure) {
                // An incomplete client request can resume; an unconfirmed disk write cannot.
                discard(upload);
                throw writeFailure;
            }
            upload.requireActive();
            upload.received += length - remaining;
            if (readFailure != null) throw readFailure;
            if (remaining > 0) throw new IOException("Передача прервана");
            return upload.received;
        }
    }

    void finish(Upload upload) throws IOException {
        synchronized (upload) {
            upload.requireActive();
            if (upload.received != upload.size) {
                throw offsetConflict(upload, "Загружено " + upload.received + " из " + upload.size + " байт");
            }
            synchronized (ManagedFiles.NAMESPACE) { publish(upload); }
        }
    }

    /** Linearize publication with abort/expiry/close. Lock order: job, namespace, registry. */
    private synchronized void publish(Upload upload) throws IOException {
        checkOpen();
        upload.requireActive();
        try {
            ManagedFiles.directory(upload.parent.toPath());
            File target = new File(upload.parent, upload.name);
            if (Files.isSymbolicLink(target.toPath()) || (!upload.overwrite && Files.exists(target.toPath(), NOFOLLOW))) {
                throw error(409, "Файл с таким именем уже существует");
            }
            if (!Files.isRegularFile(upload.tmp.toPath(), NOFOLLOW)) throw missing();
            if (upload.overwrite) {
                Files.move(upload.tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(upload.tmp.toPath(), target.toPath());
            }
        } finally {
            discard(upload);
        }
    }

    synchronized void abort(String id) {
        Upload upload = jobs.remove(id);
        if (upload != null) upload.discard();
    }

    synchronized void discard(Upload upload) {
        if (jobs.remove(upload.id, upload)) upload.discard();
    }

    synchronized int size() { return jobs.size(); }

    synchronized void expire() {
        long now = clock.getAsLong();
        for (Iterator<Upload> iterator = jobs.values().iterator(); iterator.hasNext();) {
            Upload upload = iterator.next();
            if (now - upload.created >= TTL_MS) {
                iterator.remove();
                upload.discard();
            }
        }
    }

    synchronized void close() {
        closed = true;
        for (Upload upload : jobs.values()) upload.discard();
        jobs.clear();
    }

    private void checkOpen() throws FileServer.ApiException {
        if (closed || Thread.currentThread().isInterrupted()) throw error(503, "Доступ остановлен");
    }

    private static FileServer.ApiException missing() {
        return error(404, "Загрузка не найдена или устарела. Начните заново.");
    }

    private static FileServer.ApiException offsetConflict(Upload upload, String message) {
        return new FileServer.ApiException(409, message, "X-Upload-Received: " + upload.received + "\r\n");
    }
}
