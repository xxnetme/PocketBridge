package com.local.wififiles;

import android.content.Context;
import android.util.Xml;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.SSLContext;

final class FileServer {
    static final String READ_ONLY_MESSAGE = "Доступ только для чтения";
    static final String READ_ONLY_HEADER = "X-PocketBridge-Read-Only";
    private static final long PREVIEW_TTL = 10L * 60 * 1000;
    private static final long DOWNLOAD_TTL = 10L * 60 * 1000;
    private static final int TICKET_LIMIT = 256;
    /** Максимальный размер файла для перекодирования в превью (50 МБ). Больше — скачивайте файл. */
    private static final long PREVIEW_MAX_SIZE = 50L * 1024 * 1024;
    private static final long SVG_MAX_SIZE = 5L * 1024 * 1024;
    private static final long MAX_UPLOAD = 20L * 1024 * 1024 * 1024;
    private static final int IO_THREADS_MAX = 32;
    private static final int HEAD_TIMEOUT_MS = 15000;
    private static final int REQUEST_TIMEOUT_MS = 120000;
    private static final int BODY_TIMEOUT_MS = 600000;
    private static final int ZIP_MAX_FILES = 5000;
    private static final int THUMB_WIDTH = 320;
    private static final int CHUNK_MAX = 32 * 1024 * 1024;
    private static final long THUMB_CACHE_LIMIT = 200L * 1024 * 1024;
    private static final long THUMB_CLEANUP_INTERVAL_MS = 60_000L;
    static final int CLIPBOARD_MAX_CHARS = 65_536;
    private static final int CLIPBOARD_MAX_BODY = 256 * 1024;
    private static final int COPY_MAX_NODES = 50_000;
    private static final int TEXT_EDIT_MAX_BYTES = 2 * 1024 * 1024;
    private static final int ZIP_BROWSE_LIMIT = 2000;
    private static final int ZIP_SCAN_MAX = 50_000;
    private static final int ZIP_EXTRACT_MAX_ENTRIES = 10_000;
    private static final long ZIP_EXTRACT_MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long ZIP_BOMB_THRESHOLD = 10L * 1024 * 1024;
    private static final long ZIP_BOMB_MAX_RATIO = 100L;
    private static final long HASH_MAX_BYTES = 4L * 1024 * 1024 * 1024;
    private static final Set<String> TEXT_EXTS = new HashSet<>(Arrays.asList(
            "txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "html", "htm",
            "css", "js", "mjs", "ts", "tsx", "jsx", "yml", "yaml", "toml", "ini", "cfg",
            "conf", "properties", "java", "kt", "kts", "py", "sh", "sql", "gradle",
            "c", "h", "cpp", "go", "rs", "rb", "php"
    ));
    private static final String[] QUICK_DIRS = {
            ShareInbox.DIRECTORY, "DCIM/Camera", "DCIM", "Pictures", "Download", "Documents", "Music", "Movies"
    };

    /** Строгая изоляция SVG, чтобы встроенные скрипты никогда не выполнялись на origin сервера. */
    private static final String SANDBOX_CSP =
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; img-src data:; sandbox\r\n";
    /** CSP главной страницы веб-интерфейса. */
    private static final String APP_CSP =
            "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data: blob:; media-src 'self' blob:; frame-src 'self' blob:; connect-src 'self'; "
                    + "font-src 'self' data:; object-src 'none'; base-uri 'none'\r\n";

    private final Context context;
    private final File root;
    private final boolean readOnly;
    private final String key;
    private final SSLContext ssl;
    private final File cacheDir;
    private final int portStart;
    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final ThumbnailSessions sessions = new ThumbnailSessions();
    private final LegacyUploads uploads = new LegacyUploads();
    private final Set<String> failedVideoThumbs = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastThumbCleanup = new AtomicLong(0);
    private final WebDav dav;
    private final TrashStore trash;
    private final RetainedUploads retained;
    private final BatchRenames renames;
    private final FolderScans scans;
    private ScheduledExecutorService maintenance;
    private volatile boolean stopped;

    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long startedAt;
    private volatile long lastClientTouch;
    private final Object clipLock = new Object();
    private String clipText = "";
    private long clipUpdatedAt;
    private String clipSource = "";
    private volatile Runnable clipboardListener;

    private static final class ZipItem {
        final File file; final String name;
        ZipItem(File file, String name) { this.file = file; this.name = name; }
    }

    private static final class Ticket {
        final String type, path, mime, name;
        final List<ZipItem> items;
        final long expires;
        Ticket(String type, String path, String mime, String name, List<ZipItem> items, long expires) {
            this.type = type; this.path = path; this.mime = mime;
            this.name = name; this.items = items; this.expires = expires;
        }
    }

    /** Ошибка операции с конкретным HTTP-статусом. */
    static final class ApiException extends IOException {
        final int status;
        final String extraHeaders;
        ApiException(int status, String message) { this(status, message, null); }
        ApiException(int status, String message, String extraHeaders) {
            super(message); this.status = status; this.extraHeaders = extraHeaders;
        }
    }

    FileServer(Context c, File root, String key) throws IOException { this(c, root, key, null, null, 8765); }
    FileServer(Context c, File root, String key, SSLContext ssl) throws IOException { this(c, root, key, ssl, null, 8765); }
    FileServer(Context c, File root, String key, SSLContext ssl, File cacheDir, int portStart) throws IOException {
        this(c, root, key, ssl, cacheDir, portStart, false);
    }

    FileServer(Context c, File root, String key, SSLContext ssl, File cacheDir, int portStart, boolean readOnly) throws IOException {
        this.context = c.getApplicationContext();
        this.root = root.getCanonicalFile();
        this.key = key;
        this.ssl = ssl;
        this.cacheDir = cacheDir;
        this.portStart = Math.max(1024, Math.min(65000, portStart));
        this.readOnly = readOnly;
        this.trash = new TrashStore(this.root);
        this.renames = new BatchRenames(this.root);
        this.scans = new FolderScans(this.root, trash);
        this.retained = new RetainedUploads(this.root, context.getFilesDir());
        this.dav = new WebDav(this.root, key, readOnly, trash);
    }

    String key() { return key; }
    boolean isReadOnly() { return readOnly; }
    long idleMillis() { return System.currentTimeMillis() - lastActivity; }
    int activeConnections() { return connections.size(); }
    int activeUploads() { return uploads.size(); }
    long uptimeMillis() {
        long s = startedAt;
        return s <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - s);
    }
    boolean isRecentlyActive(long windowMs) {
        if (windowMs <= 0L) return false;
        if (!connections.isEmpty() || uploads.size() != 0) return true;
        long t = lastClientTouch;
        return t > 0L && (System.currentTimeMillis() - t) <= windowMs;
    }

    void setClipboardListener(Runnable r) { this.clipboardListener = r; }
    String clipboardText() { synchronized (clipLock) { return clipText; } }
    long clipboardUpdatedAt() { synchronized (clipLock) { return clipUpdatedAt; } }
    String clipboardSource() { synchronized (clipLock) { return clipSource; } }

    void updateClipboard(String raw, String source) {
        String clean = raw == null ? "" : raw.replace("\0", "").replace("\r\n", "\n").replace('\r', '\n');
        if (clean.length() > CLIPBOARD_MAX_CHARS) clean = clean.substring(0, CLIPBOARD_MAX_CHARS);
        synchronized (clipLock) {
            clipText = clean;
            if (clean.isEmpty()) {
                clipUpdatedAt = 0L;
                clipSource = "";
            } else {
                clipUpdatedAt = System.currentTimeMillis();
                clipSource = source == null ? "" : source;
            }
        }
        lastActivity = System.currentTimeMillis();
        Runnable cb = clipboardListener;
        if (cb != null) {
            try { cb.run(); } catch (Throwable ignored) {}
        }
    }

    int start() throws IOException {
        IOException last = null;
        for (int p = portStart; p < portStart + 16; p++) {
            try {
                listener = ssl != null ? ssl.getServerSocketFactory().createServerSocket() : new ServerSocket();
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(p));
                break;
            } catch (IOException e) {
                last = e;
                if (listener != null) try { listener.close(); } catch (Exception ignored) {}
                listener = null;
            }
        }
        if (listener == null) throw last;
        workers = new ThreadPoolExecutor(0, IO_THREADS_MAX, 60, TimeUnit.SECONDS, new SynchronousQueue<>());
        Thread t = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket s = listener.accept();
                    if (!LanAddresses.allowed(s.getInetAddress())) { s.close(); continue; }
                    s.setSoTimeout(HEAD_TIMEOUT_MS);
                    connections.add(s);
                    if (stopped) { connections.remove(s); s.close(); break; }
                    try {
                        workers.execute(() -> { try { handle(s); } finally { connections.remove(s); } });
                    } catch (RejectedExecutionException busy) {
                        connections.remove(s);
                        rejectBusy(s);
                    }
                } catch (Exception e) {
                    if (!listener.isClosed()) ServerLog.failure(ServerLog.Event.ACCEPT_FAILED, e);
                }
            }
        }, "wifi-file-server");
        t.setDaemon(true);
        t.start();
        startedAt = System.currentTimeMillis();
        if (!readOnly) {
            maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "pocketbridge-storage-cleanup");
                thread.setDaemon(true);
                return thread;
            });
            maintenance.scheduleWithFixedDelay(() -> {
                if (stopped) return;
                uploads.expire();
                retained.cleanup();
                try { trash.cleanup(); } catch (Exception ignored) {}
            }, 0, 30, TimeUnit.MINUTES);
        }
        return listener.getLocalPort();
    }

    private static void rejectBusy(Socket s) {
        try { s.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().flush(); } catch (Exception ignored) {}
        try { s.close(); } catch (Exception ignored) {}
    }

    void stop() {
        stopped = true;
        uploads.close();
        sessions.close();
        retained.stop();
        scans.stop();
        renames.clear();
        if (maintenance != null) maintenance.shutdownNow();
        try { if (listener != null) listener.close(); } catch (IOException ignored) {}
        for (Socket s : connections) try { s.close(); } catch (IOException ignored) {}
        if (workers != null) workers.shutdownNow();
        tickets.clear();
        failedVideoThumbs.clear();
        synchronized (clipLock) { clipText = ""; clipUpdatedAt = 0L; clipSource = ""; }
        clipboardListener = null;
        cleanupThumbsCache(true);
    }

    private static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > max) throw new IOException("Header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1.name());
    }

    private void handle(Socket socket) {
        try (Socket s = socket; InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            String request = line(in, 8192);
            if (request == null) return;
            lastActivity = System.currentTimeMillis();
            String[] parts = request.split(" ");
            if (parts.length != 3) { sendText(out, 400, "Bad Request"); return; }
            Map<String, String> headers = new HashMap<>();
            String h; int count = 0;
            while ((h = line(in, 8192)) != null && !h.isEmpty()) {
                if (++count > 60) throw new IOException("Too many headers");
                int n = h.indexOf(':');
                if (n > 0) headers.put(h.substring(0, n).trim().toLowerCase(Locale.US), h.substring(n + 1).trim());
            }
            URI uri = new URI(parts[1]);
            String endpoint = uri.getPath();
            if (stopped) return;
            if (endpoint == null || endpoint.isEmpty()) endpoint = "/";
            Map<String, String> q = parseQuery(uri.getRawQuery());
            boolean body = "/api/upload".equals(endpoint) || "/api/download-zip".equals(endpoint)
                    || "/api/zip-ticket".equals(endpoint) || "/api/upload-chunk".equals(endpoint)
                    || ("PUT".equals(parts[0]) && WebDav.matches(endpoint));
            s.setSoTimeout(body ? BODY_TIMEOUT_MS : REQUEST_TIMEOUT_MS);

            if ("GET".equals(parts[0]) && ("/".equals(endpoint) || "/hash-worker.js".equals(endpoint)
                    || "/sha256.min.js".equals(endpoint) || "/transfers.js".equals(endpoint) || "/workbench.js".equals(endpoint)
                    || "/thumb-session.js".equals(endpoint) || "/app.css".equals(endpoint))) {
                boolean page = "/".equals(endpoint);
                byte[] bytes;
                try (InputStream a = context.getAssets().open(page ? "index.html" : endpoint.substring(1))) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = a.read(buf)) != -1) b.write(buf, 0, n);
                    bytes = b.toByteArray();
                }
                String type = page ? "text/html" : "/app.css".equals(endpoint) ? "text/css" : "application/javascript";
                send(out, 200, type + "; charset=utf-8", bytes,
                        page ? APP_CSP : "Content-Security-Policy: default-src 'none'; script-src 'self'\r\n");
                return;
            }
            if ("GET".equals(parts[0]) && "/preview".equals(endpoint)) {
                try { streamTicket(out, q.get("ticket"), true, headers.get("range")); }
                catch (Exception e) { sendText(out, safeStatus(e), e.getMessage() == null ? "Ошибка просмотра" : e.getMessage()); }
                return;
            }
            if ("GET".equals(parts[0]) && "/dl".equals(endpoint)) {
                try { streamTicket(out, q.get("ticket"), false, headers.get("range")); }
                catch (Exception e) { sendText(out, safeStatus(e), e.getMessage() == null ? "Ошибка скачивания" : e.getMessage()); }
                return;
            }
            if ("GET".equals(parts[0]) && "/thumb".equals(endpoint)) {
                try { thumbnail(out, q.get("token"), q.get("path"), q.get("w")); }
                catch (Exception e) { sendText(out, safeStatus(e), e.getMessage() == null ? "Ошибка миниатюры" : e.getMessage()); }
                return;
            }
            if (WebDav.matches(endpoint)) {
                lastClientTouch = System.currentTimeMillis();
                try { dav.handle(parts[0], endpoint, headers, in, out); }
                catch (Exception e) { sendText(out, safeStatus(e), e.getMessage() == null ? "Ошибка WebDAV" : e.getMessage()); }
                return;
            }
            if (!endpoint.startsWith("/api/")) { sendText(out, 404, "Not found"); return; }

            String supplied = headers.getOrDefault("x-access-key", "");
            if (!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                sendText(out, 403, "Неверный код доступа");
                return;
            }
            lastClientTouch = System.currentTimeMillis();
            try {
                if (readOnly && !readOnlyApiAllowed(parts[0], endpoint)) {
                    throw new ApiException(403, READ_ONLY_MESSAGE, READ_ONLY_HEADER + ": 1\r\n");
                }
                if ("GET".equals(parts[0]) && "/api/list".equals(endpoint)) { list(out, q.getOrDefault("path", "/")); }
                else if ("GET".equals(parts[0]) && "/api/search".equals(endpoint)) { search(out, q); }
                else if ("GET".equals(parts[0]) && "/api/download".equals(endpoint)) { download(out, q.getOrDefault("path", "/"), headers.get("range")); }
                else if ("GET".equals(parts[0]) && "/api/size".equals(endpoint)) { folderSize(out, q.getOrDefault("path", "/")); }
                else if ("POST".equals(parts[0]) && "/api/session".equals(endpoint)) { session(out, headers.get("x-pocketbridge-session")); }
                else if ("POST".equals(parts[0]) && "/api/mkdirs".equals(endpoint)) { synchronized (ManagedFiles.NAMESPACE) { checkRunning(); mkdirs(out, q.get("path")); } }
                else if ("POST".equals(parts[0]) && "/api/preview-ticket".equals(endpoint)) { previewTicket(out, q.get("path")); }
                else if ("POST".equals(parts[0]) && "/api/download-ticket".equals(endpoint)) { downloadTicket(out, q.get("path")); }
                else if ("POST".equals(parts[0]) && "/api/zip-ticket".equals(endpoint)) { zipTicket(out, in, headers, q.getOrDefault("base", "/")); }
                else if ("GET".equals(parts[0]) && "/api/text".equals(endpoint)) { previewText(out, q.get("path")); }
                else if ("GET".equals(parts[0]) && "/api/docx".equals(endpoint)) { previewDocx(out, q.get("path")); }
                else if ("POST".equals(parts[0]) && "/api/download-zip".equals(endpoint)) { downloadZip(out, in, headers, q.getOrDefault("base", "/")); }
                else if ("POST".equals(parts[0]) && "/api/mkdir".equals(endpoint)) {
                    synchronized (ManagedFiles.NAMESPACE) {
                        checkRunning();
                        File dir = child(q);
                        if (dir.exists() || !dir.mkdir()) throw new ApiException(409, "Не удалось создать папку (возможно, имя занято)");
                    }
                    ok(out);
                }
                else if ("POST".equals(parts[0]) && "/api/rename".equals(endpoint)) {
                    synchronized (ManagedFiles.NAMESPACE) {
                        checkRunning();
                        File from = resolve(q.getOrDefault("path", "/"));
                        if (from.equals(root) || !from.exists() || isRawSymlink(q.getOrDefault("path", "/"))) throw new ApiException(400, "Нельзя переименовать этот объект");
                        File target = safeChild(from.getParentFile(), q.get("name"));
                        ManagedFiles.movableTree(from.toPath());
                        if (target.exists()) throw new ApiException(409, "Имя занято");
                        Files.move(from.toPath(), target.toPath());
                    }
                    ok(out);
                }
                else if ("POST".equals(parts[0]) && "/api/delete".equals(endpoint)) {
                    File target = resolve(q.getOrDefault("path", "/"));
                    if (target.equals(root) || !target.exists()) throw new ApiException(400, "Нельзя удалить этот объект");
                    if (isRawSymlink(q.getOrDefault("path", "/"))) throw new ApiException(400, "Нельзя удалить ссылку");
                    synchronized (ManagedFiles.NAMESPACE) { checkRunning(); json(out, trash.put(target).put("ok", true)); }
                }
                else if ("POST".equals(parts[0]) && "/api/rename-preview".equals(endpoint)) { json(out, renames.preview(jsonRequest(in, headers, 256 * 1024))); }
                else if ("POST".equals(parts[0]) && "/api/rename-apply".equals(endpoint)) { json(out, renames.apply(q.get("id"))); }
                else if ("POST".equals(parts[0]) && "/api/scan-start".equals(endpoint)) { json(out, scans.start(q.get("mode"), q.getOrDefault("path", "/"), q.get("other"))); }
                else if ("GET".equals(parts[0]) && "/api/scan".equals(endpoint)) { json(out, scans.status(q.get("id"), "1".equals(q.get("result")))); }
                else if ("POST".equals(parts[0]) && "/api/scan-cancel".equals(endpoint)) { scans.cancel(q.get("id")); ok(out); }
                else if ("POST".equals(parts[0]) && "/api/duplicate-trash".equals(endpoint)) { json(out, scans.trashDuplicate(q.get("id"), q.get("path"), q.get("keep"))); }
                else if ("GET".equals(parts[0]) && "/api/trash".equals(endpoint)) { json(out, trash.list()); }
                else if ("POST".equals(parts[0]) && "/api/trash-restore".equals(endpoint)) {
                    synchronized (ManagedFiles.NAMESPACE) { checkRunning(); json(out, trash.restore(q.get("id"), q.get("name"), q.get("path"))); }
                }
                else if ("POST".equals(parts[0]) && "/api/trash-purge".equals(endpoint)) {
                    synchronized (ManagedFiles.NAMESPACE) { checkRunning(); trash.purge(q.get("id")); }
                    ok(out);
                }
                else if ("POST".equals(parts[0]) && "/api/copy".equals(endpoint)) { synchronized (ManagedFiles.NAMESPACE) { checkRunning(); copyOrMove(out, q, false); } }
                else if ("POST".equals(parts[0]) && "/api/move".equals(endpoint)) { synchronized (ManagedFiles.NAMESPACE) { checkRunning(); copyOrMove(out, q, true); } }
                else if ("GET".equals(parts[0]) && "/api/clipboard".equals(endpoint)) { getClipboard(out); }
                else if ("POST".equals(parts[0]) && "/api/clipboard".equals(endpoint)) { postClipboard(out, in, headers); }
                else if ("POST".equals(parts[0]) && "/api/save-text".equals(endpoint)) { saveText(out, in, headers, q); }
                else if ("POST".equals(parts[0]) && "/api/rotate-image".equals(endpoint)) { synchronized (ManagedFiles.NAMESPACE) { checkRunning(); rotateImage(out, q); } }
                else if ("GET".equals(parts[0]) && "/api/zip-list".equals(endpoint)) { zipList(out, q.get("path")); }
                else if ("POST".equals(parts[0]) && "/api/zip-extract".equals(endpoint)) { synchronized (ManagedFiles.NAMESPACE) { checkRunning(); zipExtract(out, in, headers, q); } }
                else if ("GET".equals(parts[0]) && "/api/stat".equals(endpoint)) { statEntry(out, q.get("path")); }
                else if ("GET".equals(parts[0]) && "/api/hash".equals(endpoint)) { hashFile(out, q.get("path")); }
                else if ("POST".equals(parts[0]) && "/api/upload".equals(endpoint)) { upload(out, in, headers, q); }
                else if ("POST".equals(parts[0]) && "/api/upload-init".equals(endpoint)) { uploadInit(out, q); }
                else if ("GET".equals(parts[0]) && "/api/upload-status".equals(endpoint)) { uploadStatus(out, q); }
                else if ("POST".equals(parts[0]) && "/api/upload-chunk".equals(endpoint)) { uploadChunk(out, in, headers, q); }
                else if ("POST".equals(parts[0]) && "/api/upload-finish".equals(endpoint)) { uploadFinish(out, q); }
                else if ("POST".equals(parts[0]) && "/api/upload-abort".equals(endpoint)) { uploadAbort(out, q); }
                else if ("POST".equals(parts[0]) && "/api/upload-retarget".equals(endpoint)) {
                    json(out, retained.retarget(q.get("id"), q.get("name"), "1".equals(q.get("overwrite"))));
                }
                else sendText(out, 404, "Not found");
            } catch (ApiException e) {
                send(out, e.status, "text/plain; charset=utf-8", e.getMessage().getBytes(StandardCharsets.UTF_8), e.extraHeaders);
            } catch (Exception e) {
                sendText(out, 400, e.getMessage() == null ? "Ошибка операции" : e.getMessage());
            }
        } catch (javax.net.ssl.SSLException e) {
            ServerLog.failure(ServerLog.Event.TLS_REJECTED, e);
        } catch (Exception e) {
            ServerLog.failure(ServerLog.Event.REQUEST_FAILED, e);
        }
    }

    /** Разрешаем чтение и POST, которые создают только временные билеты скачивания/просмотра. */
    private static boolean readOnlyApiAllowed(String method, String endpoint) {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return !"/api/upload-status".equals(endpoint) && !"/api/trash".equals(endpoint);
        }
        if (!"POST".equals(method)) return false;
        switch (endpoint) {
            case "/api/scan-start":
            case "/api/scan-cancel":
            case "/api/session":
            case "/api/preview-ticket":
            case "/api/download-ticket":
            case "/api/zip-ticket":
            case "/api/download-zip":
                return true;
            default:
                return false;
        }
    }

    private void checkRunning() throws IOException {
        if (stopped || Thread.currentThread().isInterrupted()) throw new ApiException(503, "Доступ остановлен");
    }

    private static int safeStatus(Exception e) {
        return e instanceof ApiException ? ((ApiException) e).status : (e.getMessage() == null || e.getMessage().isEmpty() ? 400 : 403);
    }

    private Map<String, String> parseQuery(String query) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) continue;
                int i = pair.indexOf('=');
                String a = i < 0 ? pair : pair.substring(0, i);
                String b = i < 0 ? "" : pair.substring(i + 1);
                m.put(URLDecoder.decode(a, "UTF-8"), URLDecoder.decode(b, "UTF-8"));
            }
        }
        return m;
    }

    private File resolve(String path) throws IOException {
        if (path == null || path.indexOf('\0') >= 0) throw new ApiException(400, "Недопустимый путь");
        while (path.startsWith("/")) path = path.substring(1);
        File raw = new File(root, path);
        ManagedFiles.publicPath(root.toPath().relativize(raw.toPath()));
        File f;
        if (raw.exists()) {
            f = raw.toPath().toRealPath().toFile();
        } else if (Files.isSymbolicLink(raw.toPath())) {
            throw new ApiException(400, "Недопустимая ссылка");
        } else {
            f = raw.getCanonicalFile();
        }
        String base = root.getPath();
        String prefix = base.endsWith(File.separator) ? base : base + File.separator;
        if (!f.getPath().equals(base) && !f.getPath().startsWith(prefix)) {
            throw new ApiException(400, "Путь вне доступного хранилища");
        }
        ManagedFiles.publicPath(root.toPath().relativize(f.toPath()));
        return f;
    }

    private File safeChild(File parent, String name) throws IOException {
        if (parent == null || !parent.isDirectory() || name == null || name.isEmpty()
                || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")
                || name.indexOf('\0') >= 0 || name.length() > 200 || isTempName(name)) {
            throw new ApiException(400, "Недопустимое имя");
        }
        File child = new File(parent, name);
        if (Files.isSymbolicLink(child.toPath())) throw new ApiException(400, "Недопустимый путь");
        File parentReal = parent.toPath().toRealPath().toFile();
        File canonical = child.exists() ? child.toPath().toRealPath().toFile() : new File(parentReal, name);
        if (!parentReal.equals(canonical.getParentFile())) throw new ApiException(400, "Недопустимый путь");
        return child;
    }

    private File child(Map<String, String> q) throws IOException { return safeChild(resolve(q.getOrDefault("path", "/")), q.get("name")); }

    private String relative(File f) throws IOException {
        String p = f.getCanonicalPath();
        String base = root.getPath();
        if (p.equals(base)) return "/";
        String prefix = base.endsWith(File.separator) ? base : base + File.separator;
        if (!p.startsWith(prefix)) throw new ApiException(400, "Путь вне доступного хранилища");
        ManagedFiles.publicPath(root.toPath().relativize(new File(p).toPath()));
        return ("/" + p.substring(prefix.length())).replace(File.separatorChar, '/');
    }

    /** NFC-нормализация: macOS отдаёт имена в NFD, поиск должен их находить. */
    static String normalize(String text) {
        try { return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
        catch (Exception e) { return text.toLowerCase(Locale.ROOT); }
    }

    static boolean isTempName(String name) { return ManagedFiles.hiddenName(name); }

    private void list(OutputStream out, String path) throws Exception {
        File d = resolve(path);
        if (!d.isDirectory()) throw new ApiException(400, "Папка недоступна");
        Path folder = ManagedFiles.directory(d.toPath());
        List<JSONObject> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path f : stream) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Доступ остановлен");
                if (isTempName(f.getFileName().toString())) continue;
                try {
                    java.nio.file.attribute.BasicFileAttributes a = ManagedFiles.attrs(f);
                    if (a.isDirectory() || a.isRegularFile()) entries.add(item(f, a));
                } catch (IOException ignored) { /* entry disappeared during listing */ }
            }
        }
        entries.sort(Comparator.comparing((JSONObject entry) -> !entry.optBoolean("dir"))
                .thenComparing(entry -> entry.optString("name"), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.optString("name")));
        JSONArray arr = new JSONArray(entries);
        JSONObject data = new JSONObject();
        data.put("path", relative(d));
        data.put("readOnly", readOnly);
        if (!readOnly) data.put("uploadScope", retained.scope());
        data.put("items", arr);
        long total = root.getTotalSpace();
        if (total > 0L) {
            long free = Math.max(0L, Math.min(total, root.getUsableSpace()));
            data.put("totalBytes", total);
            data.put("freeBytes", free);
        }
        synchronized (clipLock) {
            data.put("clipHasText", !clipText.isEmpty());
            data.put("clipUpdatedAt", clipUpdatedAt);
        }
        if (d.equals(root)) {
            JSONArray quick = new JSONArray();
            boolean hasCamera = false;
            for (String rel : QUICK_DIRS) {
                if ("DCIM".equals(rel) && hasCamera) continue;
                try {
                    File rawQd = new File(root, rel);
                    if (!rawQd.isDirectory()) continue;
                    File qd = resolve("/" + rel);
                    if (qd.isDirectory() && qd.canRead()) {
                        quick.put("/" + rel);
                        if ("DCIM/Camera".equals(rel)) hasCamera = true;
                    }
                } catch (Exception ignored) {}
            }
            data.put("quickDirs", quick);
        }
        send(out, 200, "application/json; charset=utf-8", data.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private JSONObject item(Path f, java.nio.file.attribute.BasicFileAttributes a) throws Exception {
        return new JSONObject().put("name", f.getFileName().toString()).put("path", ManagedFiles.virtual(root.toPath(), f))
                .put("dir", a.isDirectory()).put("size", a.isDirectory() ? 0L : a.size())
                .put("modified", a.lastModifiedTime().toMillis());
    }

    private static long numericFilter(Map<String, String> q, String key, long fallback) throws IOException {
        String raw = q.get(key);
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            long value = Long.parseLong(raw);
            if (value < 0 && !"after".equals(key) && !"before".equals(key)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException bad) { throw new ApiException(400, "Некорректный фильтр: " + key); }
    }

    private static String searchType(Path path, boolean folder) {
        if (folder) return "folder";
        File f = path.toFile();
        String mime = mediaMime(f);
        if (mime != null && mime.indexOf('/') > 0 && !"application/pdf".equals(mime)) return mime.substring(0, mime.indexOf('/'));
        switch (extension(f)) {
            case "zip": case "rar": case "7z": case "tar": case "gz": case "bz2": case "xz": return "archive";
            case "doc": case "docx": case "xls": case "xlsx": case "ppt": case "pptx": case "odt": case "ods": case "pdf": return "document";
            case "mkv": case "avi": case "wmv": case "3gp": return "video";
            case "aac": case "aiff": case "wma": case "m4b": return "audio";
            case "tiff": case "tif": case "ico": return "image";
            default: return TEXT_EXTS.contains(extension(f)) ? "document" : "other";
        }
    }

    private void search(OutputStream out, Map<String, String> q) throws Exception {
        File base = resolve(q.getOrDefault("path", "/"));
        Path folder = ManagedFiles.directory(base.toPath());
        String needle = normalize(q.getOrDefault("q", "").trim());
        String type = q.getOrDefault("type", "all"), ext = normalize(q.getOrDefault("ext", "").trim().replaceFirst("^\\.", ""));
        if (!Arrays.asList("all", "folder", "file", "image", "video", "audio", "document", "archive", "other").contains(type)
                || (!ext.isEmpty() && !ext.matches("[a-z0-9_-]{1,24}"))) throw new ApiException(400, "Некорректный тип файла");
        long min = numericFilter(q, "minBytes", 0), max = numericFilter(q, "maxBytes", Long.MAX_VALUE);
        long after = numericFilter(q, "after", Long.MIN_VALUE), before = numericFilter(q, "before", Long.MAX_VALUE);
        boolean hasSize = q.containsKey("minBytes") || q.containsKey("maxBytes");
        boolean hasFilters = hasSize || !"all".equals(type) || !ext.isEmpty() || after != Long.MIN_VALUE || before != Long.MAX_VALUE;
        boolean deep = !"0".equals(q.get("deep"));
        if (needle.length() < 2 && !hasFilters) throw new ApiException(400, "Введите минимум 2 символа или добавьте фильтр");
        if (min > max || after >= before || needle.length() > 200) throw new ApiException(400, "Некорректный диапазон фильтра");
        ArrayDeque<Path> queue = new ArrayDeque<>();
        queue.add(folder);
        JSONArray found = new JSONArray();
        int visited = 0;
        boolean truncated = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        outer: while (!queue.isEmpty()) {
            Path dir = queue.remove();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(ManagedFiles.directory(dir))) {
                for (Path f : children) {
                    if (++visited > 50_000 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) { truncated = true; break outer; }
                    String name = f.getFileName().toString();
                    if (isTempName(name)) continue;
                    try {
                        java.nio.file.attribute.BasicFileAttributes a = ManagedFiles.attrs(f);
                        boolean isDir = a.isDirectory();
                        if (!isDir && !a.isRegularFile()) continue;
                        if (deep && isDir) queue.add(f);
                        if (!needle.isEmpty() && !normalize(name).contains(needle)) continue;
                        if (hasSize && (isDir || a.size() < min || a.size() > max)) continue;
                        if (a.lastModifiedTime().toMillis() < after || a.lastModifiedTime().toMillis() >= before) continue;
                        if (!"all".equals(type) && !("file".equals(type) && !isDir) && !type.equals(searchType(f, isDir))) continue;
                        if (!ext.isEmpty() && (isDir || !extension(f.toFile()).equals(ext))) continue;
                        if (found.length() >= 200) { truncated = true; break outer; }
                        found.put(item(f, a));
                    } catch (IOException unreadable) { truncated = true; }
                }
            } catch (IOException unreadable) { truncated = true; }
        }
        json(out, new JSONObject().put("items", found).put("truncated", truncated));
    }

    private void download(OutputStream out, String path, String range) throws Exception {
        File f = resolve(path);
        if (!f.isFile() || !f.canRead()) throw new ApiException(400, "Файл недоступен");
        sendFile(out, f, "application/octet-stream", true, range);
    }

    private static String extension(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    private static String mediaMime(File f) {
        switch (extension(f)) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "avif": return "image/avif";
            case "svg": return "image/svg+xml";
            case "mp4": case "m4v": return "video/mp4";
            case "webm": return "video/webm";
            case "ogv": return "video/ogg";
            case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "wav": return "audio/wav";
            case "ogg": case "opus": return "audio/ogg";
            case "flac": return "audio/flac";
            case "heic": case "heif": return "image/heic";
            case "dng": return "image/x-adobe-dng";
            case "cr2": return "image/x-canon-cr2";
            case "nef": return "image/x-nikon-nef";
            case "arw": return "image/x-sony-arw";
            case "raf": return "image/x-fuji-raf";
            case "pdf": return "application/pdf";
            default: return null;
        }
    }

    private File readable(String path) throws IOException {
        File f = resolve(path);
        if (!f.isFile() || !f.canRead()) throw new ApiException(400, "Файл недоступен");
        return f;
    }

    private String issue(String type, String path, String mime, String name, List<ZipItem> items, long ttl) throws IOException {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Ticket> e : tickets.entrySet()) if (e.getValue().expires < now) tickets.remove(e.getKey());
        if (tickets.size() >= TICKET_LIMIT) throw new ApiException(429, "Слишком много активных передач. Подождите пару минут.");
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder id = new StringBuilder(32);
        for (byte b : random) id.append(String.format(Locale.US, "%02x", b & 255));
        tickets.put(id.toString(), new Ticket(type, path, mime, name, items, now + ttl));
        return id.toString();
    }

    private void session(OutputStream out, String previous) throws Exception {
        JSONObject data = new JSONObject();
        data.put("token", sessions.issue(previous));
        data.put("ttl", ThumbnailSessions.TTL_MS);
        json(out, data);
    }

    private void mkdirs(OutputStream out, String path) throws Exception {
        if (path == null) throw new ApiException(400, "Недопустимый путь");
        String clean = path;
        while (clean.startsWith("/")) clean = clean.substring(1);
        File current = root;
        for (String segment : clean.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            current = safeChild(current, segment);
            if (!current.exists() && !current.mkdir()) throw new ApiException(409, "Не удалось создать папку: " + segment);
            if (!current.isDirectory()) throw new ApiException(409, "Занято файлом: " + segment);
        }
        ok(out);
    }

    private void thumbnail(OutputStream out, String token, String path, String width) throws Exception {
        sessions.require(token);
        File f = readable(path);
        String mime = mediaMime(f);
        boolean image = mime != null && mime.startsWith("image/");
        boolean video = mime != null && mime.startsWith("video/");
        if (!image && !video) throw new ApiException(400, "Миниатюры доступны только для изображений и видео");

        if ("image/svg+xml".equals(mime)) {
            if (f.length() > SVG_MAX_SIZE) throw new ApiException(400, "SVG больше 5 МБ");
            String thumbKey = thumbnailKey(f, 0);
            byte[] svgBytes = Files.readAllBytes(f.toPath());
            send(out, 200, "image/svg+xml", svgBytes, SANDBOX_CSP + "X-Thumb-Key: " + thumbKey + "\r\n");
            return;
        }

        int requested = THUMB_WIDTH;
        try { if (width != null && !width.isEmpty()) requested = Integer.parseInt(width); } catch (Exception ignored) {}
        String thumbKey = thumbnailKey(f, requested);
        if (video && failedVideoThumbs.contains(thumbKey)) {
            throw new ApiException(400, "Кадр видео недоступен");
        }
        byte[] jpeg;
        if (cacheDir != null) {
            File cache = new File(cacheDir, "thumbs");
            File cached = new File(cache, thumbKey + ".jpg");
            if (cached.isFile() && cached.length() > 0 && cached.lastModified() >= f.lastModified()) {
                jpeg = Files.readAllBytes(cached.toPath());
            } else {
                try {
                    jpeg = video ? Thumbnails.videoFrame(f, requested) : Thumbnails.jpeg(f, requested);
                } catch (ApiException e) {
                    if (video) {
                        if (failedVideoThumbs.size() >= 256) failedVideoThumbs.clear();
                        failedVideoThumbs.add(thumbKey);
                    }
                    throw e;
                }
                try {
                    if (!cache.isDirectory()) cache.mkdirs();
                    File tmp = File.createTempFile("thumb-", ".part", cache);
                    Files.write(tmp.toPath(), jpeg);
                    Files.move(tmp.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    cleanupThumbsCache(false);
                } catch (Exception ignored) { /* кеш не критичен */ }
            }
        } else {
            jpeg = video ? Thumbnails.videoFrame(f, requested) : Thumbnails.jpeg(f, requested);
        }
        send(out, 200, "image/jpeg", jpeg, "X-Thumb-Key: " + thumbKey + "\r\n");
    }

    private String thumbnailKey(File f, int width) throws Exception {
        String seed;
        try { seed = relative(f) + "|" + f.lastModified() + "|" + f.length() + "|" + width; }
        catch (IOException e) { seed = f.getAbsolutePath() + "|" + width; }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) hex.append(String.format(Locale.US, "%02x", b & 255));
        return hex.toString();
    }

    private void previewTicket(OutputStream out, String path) throws Exception {
        File f = readable(path);
        String mime = mediaMime(f);
        if (mime == null) throw new ApiException(400, "Формат не поддерживается");
        String id = issue("preview", relative(f), mime, f.getName(), null, PREVIEW_TTL);
        JSONObject json = new JSONObject();
        json.put("url", "/preview?ticket=" + id);
        json.put("mime", mime);
        send(out, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private void downloadTicket(OutputStream out, String path) throws Exception {
        File f = readable(path);
        String id = issue("download", relative(f), "application/octet-stream", f.getName(), null, DOWNLOAD_TTL);
        JSONObject json = new JSONObject();
        json.put("url", "/dl?ticket=" + id);
        send(out, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private void zipTicket(OutputStream out, InputStream in, Map<String, String> headers, String base) throws Exception {
        List<ZipItem> items = readSelection(in, headers, base);
        String id = issue("zip", null, "application/zip", "files.zip", items, DOWNLOAD_TTL);
        JSONObject json = new JSONObject();
        json.put("url", "/dl?ticket=" + id);
        send(out, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private void streamTicket(OutputStream out, String token, boolean inline, String range) throws Exception {
        Ticket ticket = token == null ? null : tickets.get(token);
        if (ticket == null || ticket.expires < System.currentTimeMillis()) {
            sendText(out, 403, "Срок действия ссылки истёк. Обновите страницу.");
            return;
        }
        if (inline && !"preview".equals(ticket.type)) { sendText(out, 403, "Неверный тип ссылки"); return; }
        if (!inline && "preview".equals(ticket.type)) { sendText(out, 403, "Неверный тип ссылки"); return; }
        if ("zip".equals(ticket.type)) { writeZip(out, ticket.items); return; }
        File f = readable(ticket.path);
        if (ticket.mime != null && !"application/octet-stream".equals(ticket.mime) && !ticket.mime.equals(mediaMime(f))) {
            throw new ApiException(400, "Файл изменился");
        }
        if ("image/heic".equals(ticket.mime) || (ticket.mime != null && ticket.mime.startsWith("image/x-"))) {
            if (f.length() > PREVIEW_MAX_SIZE) throw new ApiException(400, "Файл больше 50 МБ — скачайте его для просмотра");
            byte[] jpeg = Thumbnails.previewJpeg(f);
            send(out, 200, "image/jpeg", jpeg, null);
            return;
        }
        if ("image/svg+xml".equals(ticket.mime) && f.length() > SVG_MAX_SIZE) {
            throw new ApiException(400, "SVG больше 5 МБ — скачайте его для просмотра");
        }
        sendFile(out, f, ticket.mime != null ? ticket.mime : "application/octet-stream", !inline, range);
    }

    private void sendFile(OutputStream out, File f, String mime, boolean attachment, String range) throws IOException {
        long total = f.length(), start = 0, end = total - 1;
        boolean partial = false;
        if (range != null && !range.isEmpty()) {
            try {
                if (!range.startsWith("bytes=") || range.indexOf(',') >= 0 || total == 0) throw new Exception();
                String spec = range.substring(6);
                int dash = spec.indexOf('-');
                if (dash < 0) throw new Exception();
                String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
                if (a.isEmpty()) {
                    long suffix = Long.parseLong(b);
                    if (suffix <= 0) throw new Exception();
                    start = Math.max(0, total - suffix);
                } else {
                    start = Long.parseLong(a);
                }
                if (!b.isEmpty() && !a.isEmpty()) end = Math.min(end, Long.parseLong(b));
                if (start < 0 || start >= total || end < start) throw new Exception();
                partial = true;
            } catch (Exception e) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
        }
        long count = total == 0 ? 0 : end - start + 1;
        String name = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20").replace("*", "%2A");
        String asciiName = f.getName().replaceAll("[^a-zA-Z0-9 ._()-]", "_");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Content-Length: ").append(count).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
        h.append("Content-Disposition: ").append(attachment ? "attachment" : "inline")
                .append("; filename=\"").append(asciiName).append("\"; filename*=UTF-8''").append(name).append("\r\n");
        if (!attachment && "image/svg+xml".equals(mime)) {
            h.append(SANDBOX_CSP);
        }
        h.append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: SAMEORIGIN\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (total == 0) { out.flush(); return; }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(start);
            byte[] buf = new byte[65536];
            long remaining = count;
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
        }
    }

    private static boolean isEditableTextName(String name) {
        if (name == null || name.isEmpty()) return false;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return false;
        return TEXT_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private void previewText(OutputStream out, String path) throws Exception {
        File f = readable(path);
        String ext = extension(f);
        if (!TEXT_EXTS.contains(ext)) {
            throw new ApiException(400, "Формат не поддерживается");
        }
        if (f.length() > 5L * 1024 * 1024) throw new ApiException(400, "Файл больше 5 МБ — скачайте его для просмотра");
        long mod = f.lastModified();
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 5 * 1024 * 1024) throw new ApiException(400, "Файл слишком большой");
                b.write(buf, 0, n);
            }
            String extra = "X-File-Modified: " + mod + "\r\nX-File-Size: " + total + "\r\nX-File-Editable: " + (!readOnly && total <= TEXT_EDIT_MAX_BYTES ? "1" : "0") + "\r\n";
            send(out, 200, "text/plain; charset=utf-8", b.toByteArray(), extra);
        }
    }

    /** Создание или сохранение текстового файла с проверкой mtime и атомарной записью через .part. */
    private void saveText(OutputStream out, InputStream in, Map<String, String> headers, Map<String, String> q) throws Exception {
        int length;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "-1")); } catch (Exception e) { length = -1; }
        if (length < 0) throw new ApiException(400, "Некорректный размер текста");
        if (length > TEXT_EDIT_MAX_BYTES) throw new ApiException(413, "Текстовый редактор ограничен файлами до 2 МБ");
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(body, read, length - read);
            if (r < 0) throw new IOException("Передача прервана");
            read += r;
        }

        boolean create = "1".equals(q.get("create"));
        boolean overwrite = "1".equals(q.get("overwrite"));
        boolean unique = "1".equals(q.get("unique"));
        boolean force = "1".equals(q.get("force"));

        File parent;
        String name;
        if (create) {
            parent = resolve(q.getOrDefault("path", "/"));
            if (!parent.isDirectory()) throw new ApiException(400, "Папка недоступна");
            name = q.get("name");
        } else {
            String rawPath = q.get("path");
            if (rawPath == null || rawPath.isEmpty()) throw new ApiException(400, "Недопустимый путь");
            if (isRawSymlink(rawPath)) throw new ApiException(400, "Редактирование символической ссылки запрещено");
            String trimmed = rawPath;
            while (trimmed.endsWith("/") && trimmed.length() > 1) trimmed = trimmed.substring(0, trimmed.length() - 1);
            int slash = trimmed.lastIndexOf('/');
            if (slash < 0 || "/".equals(trimmed)) throw new ApiException(400, "Недопустимый путь");
            String parentPath = slash == 0 ? "/" : trimmed.substring(0, slash);
            parent = resolve(parentPath);
            if (!parent.isDirectory()) throw new ApiException(400, "Папка недоступна");
            name = trimmed.substring(slash + 1);
        }

        if (isTempName(name)) throw new ApiException(400, "Недопустимое имя");
        if (!isEditableTextName(name)) {
            throw new ApiException(400, "Поддерживаются только текстовые форматы (.txt, .md, .json, .ini, .cfg, .xml, .csv и др.)");
        }

        File target = safeChild(parent, name);
        if (unique) {
            target = uniqueTarget(parent, name);
        } else if (create) {
            if (target.exists() && !overwrite) {
                throw new ApiException(409, "Файл с таким именем уже существует");
            }
            if (target.isDirectory()) {
                throw new ApiException(409, "Имя занято папкой");
            }
        } else {
            if (!target.exists()) throw new ApiException(404, "Файл не найден (возможно, он был удалён или переименован)");
            if (!target.isFile()) throw new ApiException(400, "Нельзя перезаписать папку текстом");
            String expStr = q.get("expectedModified");
            if (!force && expStr != null && !expStr.isEmpty()) {
                long expected;
                try { expected = Long.parseLong(expStr.trim()); }
                catch (NumberFormatException e) { throw new ApiException(400, "Некорректная метка времени"); }
                long actual = target.lastModified();
                if (expected > 0L && actual != expected) {
                    throw new ApiException(409,
                            "Файл был изменён на телефоне или в другой вкладке",
                            "X-Conflict-Reason: mtime\r\nX-File-Modified: " + actual + "\r\n");
                }
            }
        }

        long usable = parent.getUsableSpace();
        if (usable > 0L && usable < (long) body.length + 1024L * 1024L) {
            throw new ApiException(400, "Недостаточно свободного места на телефоне");
        }

        long prevMod = (!create && target.exists()) ? target.lastModified() : 0L;
        File tmp = File.createTempFile(".wifi-upload-", ".part", parent);
        boolean ok = false;
        try {
            try (OutputStream fout = new BufferedOutputStream(new FileOutputStream(tmp))) {
                fout.write(body);
                fout.flush();
            }
            synchronized (ManagedFiles.NAMESPACE) {
                checkRunning();
                ManagedFiles.directory(parent.toPath());
                if (unique || (create && !overwrite)) {
                    if (Files.exists(target.toPath(), ManagedFiles.NOFOLLOW)) throw new ApiException(409, "Имя занято");
                    Files.move(tmp.toPath(), target.toPath());
                } else {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (prevMod > 0L && target.lastModified() == prevMod) {
                //noinspection ResultOfMethodCallIgnored
                target.setLastModified(Math.max(System.currentTimeMillis(), prevMod + 1000L));
            }
            ok = true;
        } finally {
            if (!ok && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }

        JSONObject resp = new JSONObject();
        resp.put("ok", true);
        resp.put("name", target.getName());
        resp.put("path", relative(target));
        resp.put("size", target.length());
        resp.put("modified", target.lastModified());
        send(out, 200, "application/json; charset=utf-8", resp.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    /** Поворот растрового фото (JPEG/PNG/WebP) на 90/180/270° на месте. */
    private void rotateImage(OutputStream out, Map<String, String> q) throws Exception {
        String rawPath = q.get("path");
        if (rawPath == null || rawPath.isEmpty()) throw new ApiException(400, "Укажите путь к изображению");
        if (isRawSymlink(rawPath)) throw new ApiException(400, "Операция с символической ссылкой запрещена");
        File f = readable(rawPath);
        int deg;
        try { deg = Integer.parseInt(q.getOrDefault("degrees", "90").trim()); }
        catch (NumberFormatException e) { throw new ApiException(400, "Некорректный угол поворота"); }
        long prevMod = f.lastModified();
        int[] dims = Thumbnails.rotateFile(f, deg);
        // Гарантируем сдвиг mtime (даже на ФС с секундной точностью), чтобы кеш миниатюр и превью обновились.
        long targetMod = Math.max(System.currentTimeMillis(), prevMod + 1000L);
        if (f.lastModified() <= prevMod) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(targetMod);
        }
        JSONObject resp = new JSONObject();
        resp.put("ok", true);
        resp.put("path", relative(f));
        resp.put("width", dims[0]);
        resp.put("height", dims[1]);
        resp.put("size", f.length());
        resp.put("modified", f.lastModified());
        send(out, 200, "application/json; charset=utf-8", resp.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    /** Безопасная нормализация пути внутри ZIP-архива; возвращает null при попытке Zip Slip. */
    private static String sanitizeZipEntryPath(String rawName) {
        if (rawName == null || rawName.isEmpty() || rawName.indexOf('\0') >= 0) return null;
        String norm = rawName.replace('\\', '/');
        while (norm.startsWith("/")) norm = norm.substring(1);
        while (norm.endsWith("/")) norm = norm.substring(0, norm.length() - 1);
        if (norm.isEmpty()) return null;
        if (norm.length() >= 2 && Character.isLetter(norm.charAt(0)) && norm.charAt(1) == ':') return null;
        String[] parts = norm.split("/");
        if (parts.length > 32) return null;
        StringBuilder clean = new StringBuilder(norm.length());
        for (String seg : parts) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg) || seg.length() > 200) return null;
            if (isTempName(seg)) return null;
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (c < 0x20 || c == 0x7f) return null;
            }
            if (clean.length() > 0) clean.append('/');
            clean.append(seg);
        }
        return clean.length() == 0 ? null : clean.toString();
    }

    /** Открывает ZIP-архив в UTF-8, а при старой кириллической кодировке (Windows/DOS) — в CP866/CP1251/ISO-8859-1. */
    private static ZipFile openZipWithFallback(File f) throws IOException {
        List<Charset> charsets = new ArrayList<>(4);
        charsets.add(StandardCharsets.UTF_8);
        for (String csName : new String[]{ "IBM866", "CP866", "windows-1251" }) {
            try {
                Charset c = Charset.forName(csName);
                if (!charsets.contains(c)) charsets.add(c);
            } catch (Exception ignored) {}
        }
        charsets.add(StandardCharsets.ISO_8859_1);

        for (Charset cs : charsets) {
            ZipFile zf = null;
            try {
                zf = new ZipFile(f, cs);
                Enumeration<? extends ZipEntry> en = zf.entries();
                int probe = 0;
                while (en.hasMoreElements() && probe++ < 64) {
                    en.nextElement().getName();
                }
                return zf;
            } catch (IllegalArgumentException | java.util.zip.ZipException badCs) {
                // JDK 17 бросает IllegalArgumentException (MALFORMED), JDK 21 и Android 14+ — ZipException (bad entry name).
                if (zf != null) try { zf.close(); } catch (Exception ignored) {}
            } catch (IOException io) {
                if (zf != null) try { zf.close(); } catch (Exception ignored) {}
                break;
            }
        }
        throw new ApiException(400, "Не удалось прочитать ZIP-архив (файл повреждён или не является ZIP)");
    }

    /** Просмотр содержимого .zip без распаковки. */
    private void zipList(OutputStream out, String path) throws Exception {
        File f = readable(path);
        if (!"zip".equals(extension(f))) throw new ApiException(400, "Нужен архив формата .zip");
        JSONArray entries = new JSONArray();
        int totalEntries = 0, fileCount = 0, dirCount = 0, unsafeCount = 0;
        long totalUncompressed = 0L;
        boolean truncated = false;

        try (ZipFile zf = openZipWithFallback(f)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                if (totalEntries >= ZIP_SCAN_MAX) { truncated = true; break; }
                ZipEntry ze;
                try { ze = en.nextElement(); }
                catch (IllegalArgumentException badName) { unsafeCount++; continue; }
                String rawName = ze.getName();
                boolean isDir = ze.isDirectory() || (rawName != null && (rawName.endsWith("/") || rawName.endsWith("\\")));
                String clean = sanitizeZipEntryPath(rawName);
                if (clean == null) {
                    if (rawName != null && !rawName.equals("/") && !rawName.isEmpty()) unsafeCount++;
                    continue;
                }
                totalEntries++;
                if (isDir) dirCount++; else fileCount++;
                long sz = Math.max(0L, ze.getSize());
                long csz = Math.max(0L, ze.getCompressedSize());
                if (!isDir && sz > 0L && totalUncompressed <= Long.MAX_VALUE - sz) {
                    totalUncompressed += sz;
                }
                if (entries.length() < ZIP_BROWSE_LIMIT) {
                    JSONObject j = new JSONObject();
                    j.put("path", clean);
                    j.put("dir", isDir);
                    j.put("size", isDir ? 0L : sz);
                    j.put("compressedSize", isDir ? 0L : csz);
                    long t = ze.getTime();
                    j.put("modified", t > 0L ? t : 0L);
                    entries.put(j);
                } else {
                    truncated = true;
                }
            }
        }

        JSONObject resp = new JSONObject();
        resp.put("name", f.getName());
        resp.put("path", relative(f));
        resp.put("archiveSize", f.length());
        resp.put("totalEntries", totalEntries);
        resp.put("fileCount", fileCount);
        resp.put("dirCount", dirCount);
        resp.put("unsafeCount", unsafeCount);
        resp.put("totalUncompressed", totalUncompressed);
        resp.put("truncated", truncated);
        resp.put("entries", entries);
        send(out, 200, "application/json; charset=utf-8", resp.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    /** Безопасная распаковка .zip на телефоне (защита от Zip Slip, симлинков, Zip Bomb и переполнения диска). */
    private void zipExtract(OutputStream out, InputStream in, Map<String, String> headers, Map<String, String> q) throws Exception {
        String rawZip = q.get("path");
        if (rawZip == null || rawZip.isEmpty()) throw new ApiException(400, "Укажите путь к архиву");
        File zipFile = readable(rawZip);
        if (!"zip".equals(extension(zipFile))) throw new ApiException(400, "Нужен архив формата .zip");

        String rawDest = q.get("dest");
        if (rawDest == null || rawDest.isEmpty()) rawDest = q.get("to");
        File baseDir = (rawDest == null || rawDest.isEmpty()) ? zipFile.getParentFile() : resolve(rawDest);
        if (baseDir == null || !baseDir.exists()) throw new ApiException(404, "Папка назначения не найдена");
        if (!baseDir.isDirectory()) throw new ApiException(400, "Назначение не является папкой");

        // Необязательный JSON-список выбранных элементов {"entries":["a/b.txt", ...]}
        Set<String> selectedPaths = null;
        int bodyLen = 0;
        try { bodyLen = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (Exception ignored) {}
        if (bodyLen > 512 * 1024) throw new ApiException(413, "Слишком большой список элементов");
        if (bodyLen > 0) {
            byte[] body = new byte[bodyLen];
            int rd = 0;
            while (rd < bodyLen) {
                int r = in.read(body, rd, bodyLen - rd);
                if (r < 0) throw new IOException("Передача прервана");
                rd += r;
            }
            String s = new String(body, StandardCharsets.UTF_8).trim();
            if (!s.isEmpty()) {
                JSONObject jb = new JSONObject(s);
                JSONArray arr = jb.optJSONArray("entries");
                if (arr != null && arr.length() > 0) {
                    selectedPaths = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String rawSel = arr.getString(i);
                        String p = sanitizeZipEntryPath(rawSel);
                        if (p == null) throw new ApiException(400, "Недопустимый путь элемента в архиве");
                        selectedPaths.add(p);
                    }
                    if (selectedPaths.isEmpty()) throw new ApiException(400, "Не выбрано допустимых элементов для распаковки");
                }
            }
        }

        // Предварительная проверка центрального каталога до создания папок и файлов на диске.
        try (ZipFile preflight = openZipWithFallback(zipFile)) {
            Enumeration<? extends ZipEntry> preEn = preflight.entries();
            int preCount = 0;
            while (preEn.hasMoreElements()) {
                ZipEntry ze = preEn.nextElement();
                String rawName = ze.getName();
                String clean = sanitizeZipEntryPath(rawName);
                if (clean == null) {
                    if (selectedPaths == null && rawName != null && !rawName.isEmpty() && !"/".equals(rawName)) {
                        throw new ApiException(400, "Заблокирован небезопасный путь в архиве (Zip Slip)");
                    }
                    continue;
                }
                if (selectedPaths != null && !matchesZipSelection(clean, selectedPaths)) continue;
                if (++preCount > ZIP_EXTRACT_MAX_ENTRIES) {
                    throw new ApiException(400, "В архиве слишком много объектов (лимит " + ZIP_EXTRACT_MAX_ENTRIES + ")");
                }
            }
        }

        boolean subfolder = !"0".equals(q.getOrDefault("subfolder", selectedPaths == null ? "1" : "0"));
        boolean overwrite = "1".equals(q.get("overwrite"));
        boolean unique = "1".equals(q.get("unique"));

        File extractRoot;
        boolean createdSubfolder = false;
        if (subfolder) {
            String customFolder = q.get("folder");
            String stem;
            if (customFolder != null && !customFolder.trim().isEmpty()) {
                stem = customFolder.trim();
            } else {
                String zn = zipFile.getName();
                int dot = zn.lastIndexOf('.');
                stem = (dot > 0 ? zn.substring(0, dot) : zn + "-extracted").trim();
                if (stem.isEmpty() || ".".equals(stem) || "..".equals(stem)) stem = "extracted";
            }
            if (isTempName(stem)) throw new ApiException(400, "Недопустимое имя папки");
            extractRoot = safeChild(baseDir, stem);
            if (unique) {
                extractRoot = uniqueEntryTarget(baseDir, stem, true);
            } else if (extractRoot.exists()) {
                if (!overwrite) throw new ApiException(409, "Папка «" + stem + "» уже существует");
                if (!extractRoot.isDirectory()) throw new ApiException(409, "Имя «" + stem + "» занято файлом");
            }
            if (!extractRoot.exists()) {
                if (!extractRoot.mkdir()) throw new ApiException(400, "Не удалось создать папку распаковки");
                createdSubfolder = true;
            }
        } else {
            extractRoot = baseDir;
        }

        File rootReal = extractRoot.toPath().toRealPath().toFile();
        String rootPrefix = rootReal.getPath().endsWith(File.separator)
                ? rootReal.getPath()
                : rootReal.getPath() + File.separator;

        int extractedFiles = 0, extractedDirs = 0, skipped = 0, seen = 0;
        long totalWritten = 0L;
        byte[] buf = new byte[65536];
        boolean completed = false;

        try (ZipFile zf = openZipWithFallback(zipFile)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String rawName = ze.getName();
                boolean isDir = ze.isDirectory() || (rawName != null && (rawName.endsWith("/") || rawName.endsWith("\\")));
                String clean = sanitizeZipEntryPath(rawName);
                if (clean == null) {
                    if (rawName != null && !rawName.isEmpty() && !"/".equals(rawName)) {
                        throw new ApiException(400, "Заблокирован небезопасный путь в архиве (Zip Slip)");
                    }
                    continue;
                }
                if (selectedPaths != null && !matchesZipSelection(clean, selectedPaths)) {
                    continue;
                }
                if (++seen > ZIP_EXTRACT_MAX_ENTRIES) {
                    throw new ApiException(400, "В архиве слишком много объектов (лимит " + ZIP_EXTRACT_MAX_ENTRIES + ")");
                }

                // Пошаговая проверка каждого сегмента пути на отсутствие симлинков и выхода за extractRoot.
                String[] parts = clean.split("/");
                File curDir = rootReal;
                int dirLimit = isDir ? parts.length : parts.length - 1;
                for (int i = 0; i < dirLimit; i++) {
                    File next = new File(curDir, parts[i]);
                    if (Files.isSymbolicLink(next.toPath())) {
                        throw new ApiException(400, "Заблокирована распаковка через символическую ссылку");
                    }
                    if (!next.exists()) {
                        if (!next.mkdir()) throw new ApiException(400, "Не удалось создать подпапку: " + parts[i]);
                        extractedDirs++;
                    } else if (!next.isDirectory()) {
                        throw new ApiException(409, "Путь занят файлом: " + parts[i]);
                    }
                    File nextReal = next.toPath().toRealPath().toFile();
                    if (!nextReal.getPath().startsWith(rootPrefix)) {
                        throw new ApiException(400, "Заблокирован выход за пределы папки распаковки (Zip Slip)");
                    }
                    curDir = nextReal;
                }
                if (isDir) continue;

                String fileName = parts[parts.length - 1];
                File dst = new File(curDir, fileName);
                if (Files.isSymbolicLink(dst.toPath())) {
                    throw new ApiException(400, "Заблокирована перезапись символической ссылки");
                }
                if (!new File(curDir, fileName).getPath().startsWith(rootPrefix)) {
                    throw new ApiException(400, "Заблокирован небезопасный путь в архиве (Zip Slip)");
                }
                if (dst.exists()) {
                    if (dst.isDirectory()) { skipped++; continue; }
                    if (unique && !subfolder) {
                        dst = uniqueTarget(curDir, fileName);
                    } else if (!overwrite) {
                        skipped++;
                        continue;
                    }
                }

                long compSize = ze.getCompressedSize();
                File tmp = File.createTempFile(".wifi-upload-", ".part", curDir);
                boolean fileOk = false;
                long entryWritten = 0L;
                try {
                    try (InputStream zin = zf.getInputStream(ze);
                         OutputStream fout = new BufferedOutputStream(new FileOutputStream(tmp))) {
                        int n;
                        long lastSpaceCheck = 0L;
                        while ((n = zin.read(buf)) != -1) {
                            entryWritten += n;
                            totalWritten += n;
                            if (totalWritten > ZIP_EXTRACT_MAX_BYTES) {
                                throw new ApiException(413, "Превышен лимит распаковки (2 ГБ)");
                            }
                            if (entryWritten > ZIP_BOMB_THRESHOLD && compSize > 0L
                                    && (entryWritten / compSize) > ZIP_BOMB_MAX_RATIO) {
                                throw new ApiException(400, "Архив заблокирован: подозрительная степень сжатия (Zip Bomb)");
                            }
                            if (entryWritten - lastSpaceCheck >= 4L * 1024 * 1024) {
                                lastSpaceCheck = entryWritten;
                                long usable = curDir.getUsableSpace();
                                if (usable > 0L && usable < 16L * 1024 * 1024) {
                                    throw new ApiException(400, "Недостаточно свободного места на телефоне");
                                }
                            }
                            fout.write(buf, 0, n);
                        }
                        fout.flush();
                    } catch (java.util.zip.ZipException zeErr) {
                        throw new ApiException(400, "Архив повреждён или защищён паролем");
                    }
                    long mtime = ze.getTime();
                    if (mtime > 0L) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.setLastModified(mtime);
                    }
                    try {
                        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception moveErr) {
                        if (dst.exists() && !dst.delete()) throw new ApiException(400, "Не удалось заменить файл: " + dst.getName());
                        if (!tmp.renameTo(dst)) throw new ApiException(400, "Не удалось сохранить файл: " + dst.getName());
                    }
                    fileOk = true;
                    extractedFiles++;
                } finally {
                    if (!fileOk && tmp.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
            }
            completed = true;
        } finally {
            // Если распаковка в новую подпапку оборвалась ошибкой (например Zip Slip/Bomb), убираем её целиком.
            if (!completed && createdSubfolder && extractRoot.exists()) {
                try { deleteTree(extractRoot); } catch (Exception ignored) {}
            }
        }

        JSONObject resp = new JSONObject();
        resp.put("ok", true);
        resp.put("dest", relative(rootReal));
        resp.put("name", rootReal.getName());
        resp.put("files", extractedFiles);
        resp.put("dirs", extractedDirs);
        resp.put("skipped", skipped);
        resp.put("bytes", totalWritten);
        send(out, 200, "application/json; charset=utf-8", resp.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static boolean matchesZipSelection(String entryPath, Set<String> selected) {
        if (selected.contains(entryPath)) return true;
        for (String sel : selected) {
            if (entryPath.startsWith(sel + "/")) return true;
        }
        return false;
    }

    /** Подробные свойства файла или папки (включая размеры изображения и состав каталога). */
    private void statEntry(OutputStream out, String path) throws Exception {
        if (path == null || path.isEmpty()) throw new ApiException(400, "Укажите путь");
        File f = resolve(path);
        if (!f.exists()) throw new ApiException(404, "Объект не найден");
        boolean isRoot = f.equals(root);
        boolean isDir = f.isDirectory();
        String rel = relative(f);
        int lastSlash = rel.lastIndexOf('/');
        String parentPath = isRoot ? "" : (lastSlash <= 0 ? "/" : rel.substring(0, lastSlash));

        JSONObject j = new JSONObject();
        j.put("name", isRoot ? "/" : f.getName());
        j.put("path", rel);
        j.put("parent", parentPath);
        j.put("dir", isDir);
        j.put("size", isDir ? 0L : f.length());
        j.put("modified", f.lastModified());
        j.put("readable", f.canRead());
        j.put("writable", f.canWrite());
        j.put("hidden", !isRoot && f.getName().startsWith("."));

        if (isDir) {
            File[] children = f.listFiles();
            int df = 0, dd = 0;
            if (children != null) {
                for (File c : children) {
                    if (isTempName(c.getName())) continue;
                    if (c.isDirectory()) dd++; else df++;
                }
            }
            j.put("directFiles", df);
            j.put("directDirs", dd);
        } else {
            String ext = extension(f);
            String mime = mediaMime(f);
            if (mime == null) {
                if ("zip".equals(ext)) mime = "application/zip";
                else if ("apk".equals(ext)) mime = "application/vnd.android.package-archive";
                else if ("docx".equals(ext)) mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                else if ("json".equals(ext)) mime = "application/json";
                else if ("xml".equals(ext)) mime = "application/xml";
                else if ("html".equals(ext) || "htm".equals(ext)) mime = "text/html";
                else if ("csv".equals(ext)) mime = "text/csv";
                else if ("md".equals(ext) || "markdown".equals(ext)) mime = "text/markdown";
                else if (TEXT_EXTS.contains(ext)) mime = "text/plain";
                else mime = "application/octet-stream";
            }
            j.put("ext", ext);
            j.put("mime", mime);
            if (mime.startsWith("image/") && !"image/svg+xml".equals(mime) && f.length() <= PREVIEW_MAX_SIZE) {
                int[] info = Thumbnails.readImageInfo(f);
                if (info != null) {
                    j.put("width", info[0]);
                    j.put("height", info[1]);
                    if (info[2] > 1) j.put("exifOrientation", info[2]);
                }
            }
        }
        send(out, 200, "application/json; charset=utf-8", j.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    /** Потоковый расчёт контрольной суммы SHA-256 файла без загрузки в память. */
    private void hashFile(OutputStream out, String path) throws Exception {
        File f = readable(path);
        long size = f.length();
        if (size > HASH_MAX_BYTES) throw new ApiException(413, "Расчёт SHA-256 ограничен файлами до 4 ГБ");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        byte[] buf = new byte[65536];
        long processed = 0L;
        try (InputStream fis = new FileInputStream(f)) {
            int n;
            while ((n = fis.read(buf)) != -1) {
                md.update(buf, 0, n);
                processed += n;
                if ((processed & 0x3FFFFFL) == 0L && System.nanoTime() > deadline) {
                    throw new ApiException(400, "Расчёт SHA-256 занял слишком много времени");
                }
            }
        }
        byte[] digest = md.digest();
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b & 0xff));
        JSONObject j = new JSONObject();
        j.put("algo", "SHA-256");
        j.put("sha256", hex.toString());
        j.put("size", processed);
        j.put("modified", f.lastModified());
        send(out, 200, "application/json; charset=utf-8", j.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private void previewDocx(OutputStream out, String path) throws Exception {
        File f = readable(path);
        if (!"docx".equals(extension(f))) throw new ApiException(400, "Нужен файл DOCX");
        StringBuilder text = new StringBuilder();
        boolean truncated = false;
        try (ZipFile zip = new ZipFile(f)) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null || entry.getSize() < 0 || entry.getSize() > 8L * 1024 * 1024) {
                throw new ApiException(400, "Документ слишком большой или повреждён");
            }
            try (InputStream in = new BoundedInputStream(zip.getInputStream(entry), 8L * 1024 * 1024)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                parser.setInput(in, "UTF-8");
                boolean inText = false; int type;
                while ((type = parser.nextToken()) != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.DOCDECL) throw new ApiException(400, "Документ с DTD не поддерживается");
                    if (type == XmlPullParser.START_TAG) {
                        String n = parser.getName();
                        if ("t".equals(n)) inText = true;
                        else if ("tab".equals(n)) text.append('\t');
                        else if ("br".equals(n)) text.append('\n');
                    } else if (type == XmlPullParser.END_TAG) {
                        String n = parser.getName();
                        if ("t".equals(n)) inText = false;
                        else if ("p".equals(n)) text.append('\n');
                    } else if ((type == XmlPullParser.TEXT || type == XmlPullParser.CDSECT || type == XmlPullParser.ENTITY_REF) && inText && parser.getText() != null) {
                        text.append(parser.getText());
                    }
                    if (text.length() > 1000000) { text.setLength(1000000); truncated = true; break; }
                }
            }
        }
        JSONObject data = new JSONObject();
        data.put("text", text.toString());
        data.put("truncated", truncated);
        send(out, 200, "application/json; charset=utf-8", data.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            if (remaining <= 0) throw new IOException("Документ слишком большой");
            int n = super.read(); if (n >= 0) remaining--; return n;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("Документ слишком большой");
            int n = super.read(b, off, (int) Math.min(len, remaining)); if (n > 0) remaining -= n; return n;
        }
    }

    private void folderSize(OutputStream out, String path) throws Exception {
        File folder = resolve(path);
        if (!folder.isDirectory()) throw new ApiException(400, "Папка недоступна");
        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.push(folder.toPath());
        long total = 0, visited = 0;
        boolean partial = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline || visited >= 200000) { partial = true; break; }
            Path dir = pending.pop();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path pathItem : entries) {
                    if (++visited >= 200000 || Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) { partial = true; break; }
                    try {
                        if (Files.isSymbolicLink(pathItem)) { partial = true; continue; }
                        File f = pathItem.toFile();
                        if (isTempName(f.getName())) continue;
                        if (f.isDirectory()) { if (pending.size() >= 20000) partial = true; else pending.push(pathItem); }
                        else if (f.isFile() && f.canRead()) { long size = f.length(); total = Long.MAX_VALUE - total < size ? Long.MAX_VALUE : total + size; }
                        else partial = true;
                    } catch (Exception e) { partial = true; }
                }
            } catch (Exception e) { partial = true; }
            if (partial && (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline || visited >= 200000)) break;
        }
        JSONObject data = new JSONObject();
        data.put("bytes", total);
        data.put("partial", partial);
        send(out, 200, "application/json; charset=utf-8", data.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private List<ZipItem> readSelection(InputStream in, Map<String, String> headers, String basePath) throws Exception {
        int length;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "-1")); } catch (Exception e) { length = -1; }
        if (length < 2 || length > 1024 * 1024) throw new ApiException(400, "Некорректный список файлов");
        byte[] body = new byte[length]; int n = 0;
        while (n < length) { int r = in.read(body, n, length - n); if (r < 0) throw new IOException("Передача прервана"); n += r; }
        JSONArray selected = new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONArray("paths");
        if (selected.length() < 1 || selected.length() > 500) throw new ApiException(400, "Можно выбрать от 1 до 500 объектов");
        File base = resolve(basePath);
        if (!base.isDirectory()) throw new ApiException(400, "Некорректная папка для архива");
        ArrayList<ZipItem> items = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long budget = ZIP_MAX_FILES;
        for (int i = 0; i < selected.length(); i++) {
            File f = resolve(selected.getString(i));
            if (!f.exists()) throw new ApiException(400, "Выберите только доступные объекты");
            budget = collect(f, base, items, names, budget, 0);
        }
        if (items.isEmpty()) throw new ApiException(400, "В выбранных объектах нет файлов");
        return items;
    }

    private long collect(File f, File base, List<ZipItem> items, Set<String> names, long budget, int depth) throws IOException {
        if (budget <= 0) throw new ApiException(400, "В архиве слишком много файлов (лимит " + ZIP_MAX_FILES + ")");
        if (depth > 40) throw new ApiException(400, "Слишком глубокая вложенность папок");
        if (isTempName(f.getName())) return budget;
        String relativeRoot = relative(f);
        String basePath = base.equals(root) ? "/" : relative(base);
        if (relativeRoot.equals(basePath)) {
            if (!f.isDirectory()) return budget;
            File[] children = Files.isSymbolicLink(f.toPath()) ? null : f.listFiles();
            if (children == null) return budget;
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File c : children) budget = collect(c, base, items, names, budget, depth + 1);
            return budget;
        }
        String prefix = basePath.endsWith("/") ? basePath : basePath + "/";
        if (!relativeRoot.startsWith(prefix)) throw new ApiException(400, "Объекты вне выбранной папки");
        String entry = relativeRoot.substring(prefix.length());
        if (f.isDirectory()) {
            if (Files.isSymbolicLink(f.toPath())) return budget;
            File[] children = f.listFiles();
            if (children == null) return budget;
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File c : children) budget = collect(c, base, items, names, budget, depth + 1);
            return budget;
        }
        if (!f.isFile() || !f.canRead()) return budget;
        String unique = entry; int counter = 2;
        while (!names.add(unique)) unique = suffixName(entry, counter++);
        items.add(new ZipItem(f, unique));
        return budget - 1;
    }

    private static String suffixName(String name, int counter) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (dot <= slash) return name + " (" + counter + ")";
        return name.substring(0, dot) + " (" + counter + ")" + name.substring(dot);
    }

    private void downloadZip(OutputStream out, InputStream in, Map<String, String> headers, String base) throws Exception {
        writeZip(out, readSelection(in, headers, base));
    }

    private void writeZip(OutputStream out, List<ZipItem> items) throws IOException {
        String response = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nTransfer-Encoding: chunked\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Disposition: attachment; filename=files.zip\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        ChunkedOutput chunked = new ChunkedOutput(out);
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(chunked, 65536), StandardCharsets.UTF_8);
        zip.setLevel(1);
        byte[] buffer = new byte[65536];
        for (ZipItem item : items) {
            // A ZIP ticket can be used minutes later. Check the real path again before reading.
            Path path;
            InputStream source;
            try {
                path = item.file.toPath().toRealPath();
                if (!path.startsWith(root.toPath()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                source = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException ignored) { continue; }
            try (InputStream file = new BufferedInputStream(source)) {
                ZipEntry entry = new ZipEntry(item.name);
                entry.setTime(path.toFile().lastModified());
                zip.putNextEntry(entry);
                int nread;
                while ((nread = file.read(buffer)) != -1) zip.write(buffer, 0, nread);
                zip.closeEntry();
            }
        }
        zip.finish(); zip.flush(); chunked.finish();
    }

    private static final class ChunkedOutput extends OutputStream {
        private final OutputStream out;
        ChunkedOutput(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { byte[] one = {(byte) b}; write(one, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) return;
            out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(b, off, len);
            out.write('\r'); out.write('\n');
        }
        void finish() throws IOException { out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush(); }
    }

    private void deleteTree(File f) throws IOException {
        if (!f.getCanonicalFile().equals(f.getAbsoluteFile())) {
            if (!f.delete()) throw new ApiException(400, "Не удалось удалить ссылку: " + f.getName());
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children == null) throw new ApiException(400, "Нет доступа к папке: " + f.getName());
            for (File c : children) deleteTree(c);
        }
        if (!f.delete()) throw new ApiException(400, "Не удалось удалить: " + f.getName());
    }

    private static File uniqueEntryTarget(File parent, String name, boolean dir) throws IOException {
        File candidate = new File(parent, name);
        if (!candidate.exists()) return candidate;
        int dot = dir ? -1 : name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 9999; i++) {
            String nextName = base + " (" + i + ")" + ext;
            if (nextName.length() > 200) break;
            File next = new File(parent, nextName);
            if (!next.exists()) return next;
        }
        throw new ApiException(409, "Не удалось подобрать свободное имя");
    }

    private boolean isRawSymlink(String path) {
        if (path == null) return false;
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty()) return false;
        try { return Files.isSymbolicLink(new File(root, path).toPath()); }
        catch (Exception e) { return false; }
    }

    private void copyOrMove(OutputStream out, Map<String, String> q, boolean move) throws Exception {
        String rawSrc = q.get("path");
        String rawDest = q.get("dest");
        if (rawDest == null || rawDest.isEmpty()) rawDest = q.get("to");
        if (rawSrc == null || rawSrc.isEmpty() || rawDest == null || rawDest.isEmpty()) {
            throw new ApiException(400, "Укажите исходный путь и папку назначения");
        }
        File src = resolve(rawSrc);
        if (src.equals(root)) throw new ApiException(400, "Нельзя " + (move ? "переместить" : "скопировать") + " корень хранилища");
        if (isRawSymlink(rawSrc)) throw new ApiException(400, "Операция с символической ссылкой запрещена");
        if (!src.exists()) throw new ApiException(404, "Исходный объект не найден");
        if (isTempName(src.getName())) throw new ApiException(400, "Недопустимый файл");

        File destDir = resolve(rawDest);
        if (!destDir.exists()) throw new ApiException(404, "Папка назначения не найдена");
        if (!destDir.isDirectory()) throw new ApiException(400, "Назначение не является папкой");

        String rawName = q.get("name");
        String desiredName = (rawName == null || rawName.isEmpty()) ? src.getName() : rawName;
        if (isTempName(desiredName)) throw new ApiException(400, "Недопустимое имя");

        boolean isDir = src.isDirectory();
        boolean overwrite = "1".equals(q.get("overwrite"));
        boolean unique = "1".equals(q.get("unique"));

        File target = safeChild(destDir, desiredName);
        if (unique) {
            target = uniqueEntryTarget(destDir, desiredName, isDir);
        }

        String srcCanon = src.getCanonicalPath();
        String dstCanon = target.getCanonicalPath();
        if (srcCanon.equals(dstCanon)) {
            if (move) throw new ApiException(400, "Объект уже находится в этой папке");
            if (overwrite) throw new ApiException(400, "Нельзя перезаписать объект самим собой");
            throw new ApiException(409, "Объект с таким именем уже существует");
        }
        if (isDir) {
            String srcPrefix = srcCanon.endsWith(File.separator) ? srcCanon : srcCanon + File.separator;
            if (dstCanon.startsWith(srcPrefix)) {
                throw new ApiException(400, "Нельзя поместить папку внутрь самой себя");
            }
        }

        if (move) ManagedFiles.movableTree(src.toPath());
        if (target.exists()) {
            if (!overwrite) throw new ApiException(409, "Объект с таким именем уже существует");
            ManagedFiles.movableTree(target.toPath());
            String dstPrefix = dstCanon.endsWith(File.separator) ? dstCanon : dstCanon + File.separator;
            if (srcCanon.startsWith(dstPrefix)) {
                throw new ApiException(400, "Нельзя заменить родительскую папку");
            }
            if (target.isDirectory() != isDir) {
                throw new ApiException(409, "Нельзя заменить папку файлом или файл папкой");
            }
            deleteTree(target);
        }

        if (move) {
            boolean moved = false;
            try {
                Files.move(src.toPath(), target.toPath());
                moved = true;
            } catch (Exception e) {
                if (src.renameTo(target)) moved = true;
            }
            if (!moved) {
                copyTree(src, target, 0, new int[]{ COPY_MAX_NODES });
                deleteTree(src);
            }
        } else {
            copyTree(src, target, 0, new int[]{ COPY_MAX_NODES });
        }

        JSONObject json = new JSONObject();
        json.put("ok", true);
        json.put("name", target.getName());
        json.put("path", relative(target));
        json.put("dir", target.isDirectory());
        send(out, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private void copyTree(File src, File dst, int depth, int[] budget) throws IOException {
        if (depth > 40) throw new ApiException(400, "Слишком глубокая вложенность папок");
        if (--budget[0] < 0) throw new ApiException(400, "Слишком много объектов для копирования (лимит " + COPY_MAX_NODES + ")");
        if (isTempName(src.getName())) return;
        if (Files.isSymbolicLink(src.toPath())) return;
        // Дополнительная проверка, что канонический путь источника внутри root.
        String p = src.getCanonicalPath();
        String base = root.getPath();
        String prefix = base.endsWith(File.separator) ? base : base + File.separator;
        if (!p.equals(base) && !p.startsWith(prefix)) {
            throw new ApiException(400, "Путь вне доступного хранилища");
        }

        if (src.isDirectory()) {
            if (!dst.mkdir()) throw new ApiException(400, "Не удалось создать папку: " + dst.getName());
            File[] children = src.listFiles();
            if (children == null) throw new ApiException(400, "Нет доступа к папке: " + src.getName());
            boolean ok = false;
            try {
                for (File child : children) {
                    if (isTempName(child.getName()) || Files.isSymbolicLink(child.toPath())) continue;
                    copyTree(child, new File(dst, child.getName()), depth + 1, budget);
                }
                //noinspection ResultOfMethodCallIgnored
                dst.setLastModified(src.lastModified());
                ok = true;
            } finally {
                if (!ok && dst.exists()) {
                    try { deleteTree(dst); } catch (Exception ignored) {}
                }
            }
            return;
        }

        if (!src.isFile() || !src.canRead()) throw new ApiException(400, "Нет доступа к файлу: " + src.getName());
        long len = src.length();
        File parent = dst.getParentFile();
        if (parent != null) {
            long usable = parent.getUsableSpace();
            if (usable > 0L && len > usable) throw new ApiException(400, "Недостаточно свободного места на телефоне");
        }
        File tmp = File.createTempFile(".wifi-upload-", ".part", parent);
        boolean ok = false;
        try {
            try (InputStream in = new BufferedInputStream(new FileInputStream(src));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.setLastModified(src.lastModified());
            try { Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception e) {
                if (!tmp.renameTo(dst)) throw new ApiException(400, "Не удалось сохранить копию: " + dst.getName());
            }
            ok = true;
        } finally {
            if (!ok && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private JSONObject clipboardJson() throws Exception {
        JSONObject j = new JSONObject();
        synchronized (clipLock) {
            j.put("text", clipText);
            j.put("updatedAt", clipUpdatedAt);
            j.put("source", clipSource);
        }
        return j;
    }

    private void getClipboard(OutputStream out) throws Exception {
        send(out, 200, "application/json; charset=utf-8", clipboardJson().toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private void postClipboard(OutputStream out, InputStream in, Map<String, String> headers) throws Exception {
        int length;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "-1")); } catch (Exception e) { length = -1; }
        if (length < 0) throw new ApiException(400, "Некорректный размер текста");
        if (length > CLIPBOARD_MAX_BODY) throw new ApiException(413, "Текст слишком длинный (максимум 64 КБ)");
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(body, read, length - read);
            if (r < 0) throw new IOException("Передача прервана");
            read += r;
        }
        String ct = headers.getOrDefault("content-type", "").toLowerCase(Locale.US);
        String rawBody = new String(body, StandardCharsets.UTF_8);
        String text;
        if (ct.startsWith("application/json")) {
            JSONObject req = new JSONObject(rawBody);
            text = req.has("text") && !req.isNull("text") ? req.getString("text") : "";
        } else {
            text = rawBody;
        }
        String normalized = text.replace("\0", "").replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() > CLIPBOARD_MAX_CHARS) {
            throw new ApiException(413, "Текст слишком длинный (максимум 64 КБ)");
        }
        updateClipboard(normalized, "web");
        JSONObject resp = clipboardJson();
        resp.put("ok", true);
        send(out, 200, "application/json; charset=utf-8", resp.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private void upload(OutputStream out, InputStream in, Map<String, String> headers, Map<String, String> q) throws Exception {
        File parent = resolve(q.getOrDefault("path", "/"));
        if (!parent.isDirectory()) throw new ApiException(400, "Папка недоступна");
        String requested = q.get("name");
        File target = safeChild(parent, requested);
        boolean overwrite = "1".equals(q.get("overwrite"));
        boolean unique = "1".equals(q.get("unique"));
        if (unique) target = uniqueTarget(parent, requested);
        else if (target.exists() && !overwrite) throw new ApiException(409, "Файл с таким именем уже существует");
        long length;
        try { length = Long.parseLong(headers.getOrDefault("content-length", "-1")); } catch (Exception e) { length = -1; }
        if (length < 0 || length > MAX_UPLOAD) throw new ApiException(400, "Некорректный размер файла");
        File tmp = File.createTempFile(".wifi-upload-", ".part", parent);
        boolean ok = false;
        try {
            try (OutputStream file = new BufferedOutputStream(new FileOutputStream(tmp))) {
                byte[] b = new byte[65536]; long remaining = length;
                while (remaining > 0) {
                    int n = in.read(b, 0, (int) Math.min(b.length, remaining));
                    if (n < 0) throw new IOException("Передача прервана");
                    file.write(b, 0, n);
                    remaining -= n;
                }
            }
            synchronized (ManagedFiles.NAMESPACE) {
                checkRunning();
                ManagedFiles.directory(parent.toPath());
                if (overwrite && !unique) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    if (Files.exists(target.toPath(), ManagedFiles.NOFOLLOW)) throw new ApiException(409, "Файл с таким именем уже существует");
                    Files.move(tmp.toPath(), target.toPath());
                }
            }
            ok = true;
        } finally { if (!ok) tmp.delete(); }
        ok(out);
    }

    private static File uniqueTarget(File parent, String name) throws IOException {
        File candidate = new File(parent, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 9999; i++) {
            File next = new File(parent, base + " (" + i + ")" + ext);
            if (!next.exists()) return next;
        }
        throw new ApiException(409, "Не удалось подобрать свободное имя");
    }

    private void cleanupThumbsCache(boolean force) {
        if (cacheDir == null) return;
        long now = System.currentTimeMillis();
        long prev = lastThumbCleanup.get();
        if (!force && now - prev < THUMB_CLEANUP_INTERVAL_MS) return;
        if (!force && !lastThumbCleanup.compareAndSet(prev, now)) return;
        lastThumbCleanup.set(now);
        File cache = new File(cacheDir, "thumbs");
        if (!cache.isDirectory()) return;
        File[] files = cache.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null || files.length == 0) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= THUMB_CACHE_LIMIT) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= THUMB_CACHE_LIMIT) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    private void uploadInit(OutputStream out, Map<String, String> q) throws Exception {
        if (q.containsKey("id") || q.containsKey("sha256")) {
            File parent = resolve(q.getOrDefault("path", "/"));
            if (isRawSymlink(q.getOrDefault("path", "/"))) throw new ApiException(400, "Папка является ссылкой");
            if (q.get("scope") == null || !q.get("scope").equals(retained.scope())) throw new ApiException(409, "Общая папка изменилась");
            json(out, retained.init(q.get("id"), parent, q.get("name"), Long.parseLong(q.getOrDefault("size", "-1")),
                    q.get("sha256"), "1".equals(q.get("overwrite")), "1".equals(q.get("unique"))));
            return;
        }
        File parent = resolve(q.getOrDefault("path", "/"));
        if (!parent.isDirectory()) throw new ApiException(400, "Папка недоступна");
        String name = q.get("name");
        File target = safeChild(parent, name);
        boolean overwrite = "1".equals(q.get("overwrite"));
        boolean unique = "1".equals(q.get("unique"));
        if (unique) target = uniqueTarget(parent, name);
        else if (target.exists() && !overwrite) throw new ApiException(409, "Файл с таким именем уже существует");
        long size;
        try { size = Long.parseLong(q.getOrDefault("size", "-1")); } catch (Exception e) { size = -1; }
        if (size < 0 || size > MAX_UPLOAD) throw new ApiException(400, "Некорректный размер файла");
        LegacyUploads.Upload upload;
        synchronized (ManagedFiles.NAMESPACE) {
            checkRunning();
            upload = uploads.create(parent, target.getName(), size, overwrite);
        }
        boolean sent = false;
        try {
            json(out, new JSONObject().put("uploadId", upload.id).put("name", upload.name)
                    .put("chunk", 4 * 1024 * 1024).put("received", 0));
            sent = true;
        } finally {
            if (!sent) uploads.discard(upload);
        }
    }

    private void uploadStatus(OutputStream out, Map<String, String> q) throws Exception {
        if (RetainedUploads.isId(q.get("id"))) { json(out, retained.status(q.get("id"))); return; }
        LegacyUploads.Upload upload = uploads.require(q.get("id"));
        JSONObject json = new JSONObject();
        json.put("uploadId", upload.id);
        json.put("name", upload.name);
        json.put("received", upload.received);
        json.put("size", upload.size);
        send(out, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private void uploadChunk(OutputStream out, InputStream in, Map<String, String> headers, Map<String, String> q) throws Exception {
        long offset;
        try { offset = Long.parseLong(q.getOrDefault("offset", "-1")); } catch (Exception e) { offset = -1; }
        int length;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "-1")); } catch (Exception e) { length = -1; }
        if (length <= 0 || length > CHUNK_MAX) throw new ApiException(400, "Некорректный размер части (до 32 МБ)");

        if (RetainedUploads.isId(q.get("id"))) {
            JSONObject result = retained.chunk(q.get("id"), offset, length, in);
            json(out, result, "X-Upload-Received: " + result.getLong("received") + "\r\n");
            return;
        }
        LegacyUploads.Upload upload = uploads.require(q.get("id"));
        long received = uploads.append(upload, offset, length, in);
        json(out, new JSONObject().put("received", received).put("size", upload.size));
    }

    private void uploadFinish(OutputStream out, Map<String, String> q) throws Exception {
        if (RetainedUploads.isId(q.get("id"))) { json(out, retained.finish(q.get("id"))); return; }
        LegacyUploads.Upload upload = uploads.require(q.get("id"));
        uploads.finish(upload);
        json(out, new JSONObject().put("ok", true).put("name", upload.name).put("size", upload.size));
    }

    private void uploadAbort(OutputStream out, Map<String, String> q) throws Exception {
        String id = q.get("id");
        if (RetainedUploads.isId(id)) { json(out, retained.abort(id)); return; }
        uploads.abort(id);
        ok(out);
    }

    private JSONObject jsonRequest(InputStream in, Map<String, String> headers, int limit) throws Exception {
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        if (length < 2 || length > limit) throw new ApiException(400, "Некорректный размер JSON");
        byte[] bytes = new byte[length];
        new DataInputStream(in).readFully(bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private void json(OutputStream out, JSONObject data) throws IOException { json(out, data, null); }
    private void json(OutputStream out, JSONObject data, String headers) throws IOException {
        send(out, 200, "application/json; charset=utf-8", data.toString().getBytes(StandardCharsets.UTF_8), headers);
    }

    private void ok(OutputStream out) throws IOException { send(out, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null); }
    private void sendText(OutputStream out, int status, String msg) throws IOException { send(out, status, "text/plain; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8), null); }
    private void send(OutputStream out, int status, String type, byte[] data, String extra) throws IOException { header(out, status, type, data.length, extra); out.write(data); out.flush(); }

    private void header(OutputStream out, int status, String type, long length, String extra) throws IOException {
        String headers = "HTTP/1.1 " + status + " " + reason(status)
                + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n"
                + (extra == null ? "" : extra) + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }
}
