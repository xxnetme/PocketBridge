package com.local.wififiles;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Совместимый с Проводником Windows, macOS Finder и rclone WebDAV-сервер.
 * Монтируется по адресу https://ip:port/dav/ ; логин любой, пароль — код доступа.
 */
final class WebDav {
    private static final String PREFIX = "/dav";
    private static final String REALM = "PocketBridge";
    private static final long MAX_PUT = 20L * 1024 * 1024 * 1024;
    private static final String SANDBOX_CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; sandbox";

    private final File root;
    private final String key;

    WebDav(File root, String key) throws IOException {
        this.root = root.getCanonicalFile();
        this.key = key;
    }

    static boolean matches(String endpoint) {
        return endpoint != null && (endpoint.equals(PREFIX) || endpoint.startsWith(PREFIX + "/"));
    }

    boolean handle(String method, String endpoint, Map<String, String> headers, InputStream in, OutputStream out) throws Exception {
        // Windows WebClient и macOS Finder проверяют OPTIONS до отправки Basic-авторизации
        if ("OPTIONS".equals(method)) return options(out);

        if (!authorized(headers.get("authorization"))) {
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
            send(out, 401, "text/plain; charset=utf-8", "Нужен код доступа".getBytes(StandardCharsets.UTF_8), extra);
            return true;
        }
        String raw = endpoint.length() > PREFIX.length() ? endpoint.substring(PREFIX.length()) : "/";
        if (raw.isEmpty()) raw = "/";
        File target;
        try { target = resolve(raw); }
        catch (FileServer.ApiException e) { sendText(out, 403, "Путь вне хранилища"); return true; }

        switch (method) {
            case "PROPFIND": return propfind(out, target, raw, headers, in);
            case "GET": case "HEAD": return get(out, target, raw, method.equals("HEAD"), headers.get("range"));
            case "PUT":
                File parent;
                try { parent = resolveParent(raw); }
                catch (FileServer.ApiException e) { sendText(out, 403, "Путь вне хранилища"); return true; }
                return put(out, parent, nameOf(raw), in, headers);
            case "MKCOL": return mkcol(out, target);
            case "DELETE": return delete(out, target);
            case "MOVE": return moveOrCopy(out, target, headers.get("destination"), true, headers);
            case "COPY": return moveOrCopy(out, target, headers.get("destination"), false, headers);
            case "PROPPATCH": return proppatch(out, target, raw);
            case "LOCK": return lock(out, target, raw);
            case "UNLOCK": return unlock(out);
            default: sendText(out, 405, "Метод не поддерживается"); return true;
        }
    }

    private boolean options(OutputStream out) throws IOException {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("DAV", "1, 2");
        extra.put("MS-Author-Via", "DAV");
        extra.put("Allow", "OPTIONS, GET, HEAD, PROPFIND, PUT, MKCOL, DELETE, MOVE, COPY, PROPPATCH, LOCK, UNLOCK");
        send(out, 200, "text/plain; charset=utf-8", new byte[0], extra);
        return true;
    }

    private boolean propfind(OutputStream out, File target, String raw, Map<String, String> headers, InputStream in) throws Exception {
        if (!target.exists()) { sendText(out, 404, "Не найдено"); return true; }
        String depth = headers.getOrDefault("depth", "1").trim();
        if ("infinity".equalsIgnoreCase(depth)) {
            sendText(out, 403, "Depth: infinity не поддерживается");
            return true;
        }
        int level = "0".equals(depth) ? 0 : 1;
        String propfindBody = "";
        try {
            String cl = headers.get("content-length");
            if (cl != null) {
                int len = Integer.parseInt(cl);
                if (len > 0 && len < 100000) {
                    byte[] buf = new byte[len];
                    int total = 0;
                    while (total < len) {
                        int n = in.read(buf, total, len - total);
                        if (n < 0) break;
                        total += n;
                    }
                    propfindBody = new String(buf, 0, total, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        boolean propnameOnly = propfindBody.contains("propname");
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">");
        appendEntry(body, target, raw, propnameOnly);
        if (level == 1 && target.isDirectory() && !Files.isSymbolicLink(target.toPath())) {
            File[] children = target.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    if (FileServer.isTempName(child.getName())) continue;
                    appendEntry(body, child, joinRaw(raw, child.getName()), propnameOnly);
                }
            }
        }
        body.append("</D:multistatus>");
        send(out, 207, "application/xml; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8), null);
        return true;
    }

    private void appendEntry(StringBuilder body, File file, String raw, boolean propnameOnly) {
        boolean dir = file.isDirectory();
        boolean isRoot = file.equals(root);
        body.append("<D:response><D:href>").append(escape(hrefOf(raw, dir))).append("</D:href><D:propstat><D:prop>");
        if (propnameOnly) {
            body.append("<D:displayname/><D:resourcetype/><D:getcontentlength/><D:getcontenttype/><D:getlastmodified/><D:creationdate/>");
        } else {
            String displayName = isRoot || file.getName().isEmpty() ? "PocketBridge" : file.getName();
            body.append("<D:displayname>").append(escape(displayName)).append("</D:displayname>");
            if (dir) {
                body.append("<D:resourcetype><D:collection/></D:resourcetype>");
            } else {
                body.append("<D:resourcetype/>");
                body.append("<D:getcontentlength>").append(file.length()).append("</D:getcontentlength>");
                String mime = mimeOf(file);
                if (mime != null) body.append("<D:getcontenttype>").append(mime).append("</D:getcontenttype>");
            }
            body.append("<D:getlastmodified>").append(rfc1123(file.lastModified())).append("</D:getlastmodified>");
            body.append("<D:creationdate>").append(iso8601(file.lastModified())).append("</D:creationdate>");
        }
        body.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }

    private boolean get(OutputStream out, File target, String raw, boolean headOnly, String range) throws Exception {
        if (target.isDirectory()) {
            StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><title>PocketBridge WebDAV</title><h1>PocketBridge WebDAV</h1><p>Монтируйте этот адрес в Проводнике Windows или Finder. Логин любой, пароль — код доступа.</p><ul>");
            File[] children = target.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    if (FileServer.isTempName(child.getName())) continue;
                    String name = escape(child.getName()) + (child.isDirectory() ? "/" : "");
                    html.append("<li><a href=\"").append(escape(hrefOf(joinRaw(raw, child.getName()), child.isDirectory()))).append("\">").append(name).append("</a></li>");
                }
            }
            html.append("</ul>");
            byte[] body = html.toString().getBytes(StandardCharsets.UTF_8);
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("Content-Security-Policy", SANDBOX_CSP);
            writeHead(out, 200, "text/html; charset=utf-8", body.length, extra);
            if (!headOnly && body.length > 0) out.write(body);
            out.flush();
            return true;
        }
        if (!target.isFile() || !target.canRead()) { sendText(out, 404, "Файл не найден"); return true; }
        String mime = mimeOf(target);
        if (mime == null) mime = "application/octet-stream";

        long total = target.length(), start = 0, end = total - 1;
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
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("Content-Range", "bytes */" + total);
                send(out, 416, "text/plain; charset=utf-8", new byte[0], extra);
                return true;
            }
        }
        long count = total == 0 ? 0 : end - start + 1;
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Accept-Ranges", "bytes");
        if (partial) extra.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
        extra.put("Content-Disposition", "inline; filename*=UTF-8''" + URLEncoder.encode(target.getName(), "UTF-8").replace("+", "%20"));
        if (mime.startsWith("text/html") || mime.startsWith("image/svg+xml")) {
            extra.put("Content-Security-Policy", SANDBOX_CSP);
        }
        writeHead(out, partial ? 206 : 200, mime, count, extra);
        if (headOnly || count == 0) { out.flush(); return true; }
        try (RandomAccessFile raf = new RandomAccessFile(target, "r")) {
            raf.seek(start);
            byte[] buffer = new byte[65536];
            long remaining = count;
            while (remaining > 0) {
                int n = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) break;
                out.write(buffer, 0, n);
                remaining -= n;
            }
            out.flush();
        }
        return true;
    }

    private boolean put(OutputStream out, File parent, String name, InputStream in, Map<String, String> headers) throws Exception {
        if (parent == null || !parent.isDirectory()) { sendText(out, 409, "Папка назначения не существует"); return true; }
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            sendText(out, 400, "Недопустимое имя");
            return true;
        }
        File target;
        try { target = safeChild(parent, name); }
        catch (FileServer.ApiException e) { sendText(out, 400, e.getMessage()); return true; }
        if (target.isDirectory()) { sendText(out, 409, "Имя занято папкой"); return true; }

        boolean existed = target.exists();
        String transferEncoding = headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.US);
        boolean chunked = transferEncoding.contains("chunked");
        long length = -1;
        if (!chunked) {
            try { length = Long.parseLong(headers.getOrDefault("content-length", "-1")); } catch (Exception ignored) {}
            if (length < 0 || length > MAX_PUT) { sendText(out, 411, "Нужен Content-Length (до 20 ГБ)"); return true; }
        }

        File tmp = File.createTempFile(".wifi-upload-", ".part", parent);
        try {
            try (OutputStream file = new BufferedOutputStream(new FileOutputStream(tmp))) {
                if (chunked) {
                    copyChunked(in, file, MAX_PUT);
                } else {
                    byte[] buffer = new byte[65536];
                    long remaining = length;
                    while (remaining > 0) {
                        int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (n < 0) throw new IOException("Передача прервана");
                        file.write(buffer, 0, n);
                        remaining -= n;
                    }
                }
            }
            try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception moveError) {
                if (target.exists() && !target.delete()) { sendText(out, 500, "Не удалось заменить файл"); return true; }
                if (!tmp.renameTo(target)) { sendText(out, 500, "Не удалось сохранить файл"); return true; }
            }
            send(out, existed ? 204 : 201, "text/plain; charset=utf-8", new byte[0], null);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
        return true;
    }

    private static void copyChunked(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        while (true) {
            String sizeLine = readAsciiLine(in, 256);
            if (sizeLine == null) throw new IOException("Незавершённый chunked-поток");
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
            long chunkSize;
            try { chunkSize = Long.parseLong(sizeLine.trim(), 16); }
            catch (NumberFormatException e) { throw new IOException("Некорректный размер чанка"); }
            if (chunkSize < 0) throw new IOException("Некорректный размер чанка");
            if (chunkSize == 0) {
                String trailer;
                while ((trailer = readAsciiLine(in, 8192)) != null && !trailer.isEmpty()) { /* skip */ }
                return;
            }
            if (total + chunkSize > maxBytes) throw new FileServer.ApiException(413, "Файл больше 20 ГБ");
            long remaining = chunkSize;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) throw new IOException("Передача прервана");
                out.write(buffer, 0, n);
                remaining -= n;
                total += n;
            }
            String crlf = readAsciiLine(in, 4);
            if (crlf == null || !crlf.isEmpty()) throw new IOException("Нарушен формат чанка");
        }
    }

    private static String readAsciiLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > max) throw new IOException("Строка слишком длинная");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.US_ASCII.name());
    }

    private boolean mkcol(OutputStream out, File target) throws IOException {
        if (target.exists()) { sendText(out, 405, "Уже существует"); return true; }
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) { sendText(out, 409, "Родительская папка не существует"); return true; }
        if (!target.mkdir()) { sendText(out, 500, "Не удалось создать папку"); return true; }
        send(out, 201, "text/plain; charset=utf-8", new byte[0], null);
        return true;
    }

    private boolean delete(OutputStream out, File target) throws Exception {
        if (target.equals(root)) { sendText(out, 403, "Корень удалить нельзя"); return true; }
        if (!target.exists()) { sendText(out, 404, "Не найдено"); return true; }
        deleteTree(target);
        send(out, 204, "text/plain; charset=utf-8", new byte[0], null);
        return true;
    }

    private boolean moveOrCopy(OutputStream out, File source, String destination, boolean move, Map<String, String> headers) throws Exception {
        String targetRaw = destinationPath(destination);
        if (targetRaw == null) { sendText(out, 400, "Некорректный Destination"); return true; }
        File target;
        try { target = resolve(targetRaw); }
        catch (FileServer.ApiException e) { sendText(out, 403, "Путь вне хранилища"); return true; }
        if (!source.exists()) { sendText(out, 404, "Источник не найден"); return true; }
        if (source.equals(root) || target.equals(root) || source.equals(target)) {
            sendText(out, 403, "Недопустимая цель");
            return true;
        }
        if (source.isDirectory() && target.getPath().startsWith(source.getPath() + File.separator)) {
            sendText(out, 403, "Нельзя копировать или перемещать папку внутрь самой себя");
            return true;
        }
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) { sendText(out, 409, "Родительская папка не существует"); return true; }
        boolean overwrite = !"F".equalsIgnoreCase(headers.getOrDefault("overwrite", "T").trim());
        boolean existed = target.exists();
        if (existed) {
            if (!overwrite) { sendText(out, 412, "Цель существует"); return true; }
            deleteTree(target);
        }
        if (move) {
            if (source.renameTo(target)) {
                send(out, existed ? 204 : 201, "text/plain; charset=utf-8", new byte[0], null);
                return true;
            }
            copyTree(source, target);
            deleteTree(source);
        } else {
            copyTree(source, target);
        }
        send(out, existed ? 204 : 201, "text/plain; charset=utf-8", new byte[0], null);
        return true;
    }

    private boolean proppatch(OutputStream out, File target, String raw) throws IOException {
        if (!target.exists()) { sendText(out, 404, "Не найдено"); return true; }
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>"
                + escape(hrefOf(raw, target.isDirectory())) + "</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>";
        send(out, 207, "application/xml; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), null);
        return true;
    }

    private boolean lock(OutputStream out, File target, String raw) throws IOException {
        boolean created = false;
        if (!target.exists()) {
            File parent = target.getParentFile();
            if (parent == null || !parent.isDirectory()) { sendText(out, 409, "Родительская папка не существует"); return true; }
            if (target.createNewFile()) created = true;
        }
        String token = "opaquelocktoken:" + UUID.randomUUID();
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>"
                + "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>infinity</D:depth>"
                + "<D:timeout>Second-3600</D:timeout><D:locktoken><D:href>" + token + "</D:href></D:locktoken>"
                + "</D:activelock></D:lockdiscovery></D:prop>";
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Lock-Token", "<" + token + ">");
        send(out, created ? 201 : 200, "application/xml; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), extra);
        return true;
    }

    private boolean unlock(OutputStream out) throws IOException {
        send(out, 204, "text/plain; charset=utf-8", new byte[0], null);
        return true;
    }

    private boolean authorized(String header) {
        if (header == null) return false;
        if (!header.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) return false;
            String password = decoded.substring(colon + 1);
            return MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private File resolve(String raw) throws IOException {
        if (raw == null || raw.indexOf('\0') >= 0) throw new FileServer.ApiException(400, "Недопустимый путь");
        String clean = raw;
        while (clean.startsWith("/")) clean = clean.substring(1);
        File file = new File(root, clean).getCanonicalFile();
        String base = root.getPath();
        String prefix = base.endsWith(File.separator) ? base : base + File.separator;
        if (!file.getPath().equals(base) && !file.getPath().startsWith(prefix)) {
            throw new FileServer.ApiException(400, "Путь вне хранилища");
        }
        return file;
    }

    private File resolveParent(String raw) throws IOException {
        String clean = raw;
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        int slash = clean.lastIndexOf('/');
        String parent = slash < 0 ? "/" : clean.substring(0, slash);
        return resolve(parent);
    }

    private static String nameOf(String raw) {
        String clean = raw;
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        int slash = clean.lastIndexOf('/');
        return slash < 0 ? clean : clean.substring(slash + 1);
    }

    private File safeChild(File parent, String name) throws IOException {
        if (name.indexOf('\0') >= 0 || name.length() > 200) throw new FileServer.ApiException(400, "Недопустимое имя");
        File child = new File(parent, name).getCanonicalFile();
        if (!child.getParentFile().equals(parent.getCanonicalFile())) throw new FileServer.ApiException(400, "Недопустимое имя");
        return child;
    }

    /** Корректно декодирует URL как для абсолютного (https://host/dav/...), так и для относительного (/dav/...) Destination. */
    private String destinationPath(String destination) {
        if (destination == null || destination.trim().isEmpty()) return null;
        try {
            URI uri = new URI(destination.trim());
            String path = uri.getPath();
            if (path == null || !matches(path)) return null;
            return path.length() > PREFIX.length() ? path.substring(PREFIX.length()) : "/";
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteTree(File file) throws IOException {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("Не удалось удалить: " + file.getName());
    }

    private void copyTree(File source, File target) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) return;
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new IOException("Не удалось создать папку");
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (FileServer.isTempName(child.getName())) continue;
                    copyTree(child, new File(target, child.getName()));
                }
            }
        } else {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Не удалось создать папку");
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String joinRaw(String raw, String name) {
        if (raw.endsWith("/")) return raw + name;
        return raw + "/" + name;
    }

    private String hrefOf(String raw, boolean dir) {
        StringBuilder encoded = new StringBuilder(PREFIX);
        for (String segment : raw.split("/")) {
            if (segment.isEmpty()) continue;
            try { encoded.append('/').append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20")); }
            catch (UnsupportedEncodingException e) { encoded.append('/').append(segment); }
        }
        if (encoded.length() == PREFIX.length()) encoded.append('/');
        if (dir && encoded.charAt(encoded.length() - 1) != '/') encoded.append('/');
        return encoded.toString();
    }

    private static String mimeOf(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        switch (ext) {
            case "txt": case "md": case "log": return "text/plain; charset=utf-8";
            case "html": case "htm": return "text/html; charset=utf-8";
            case "json": return "application/json";
            case "csv": return "text/csv";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "svg": return "image/svg+xml";
            case "heic": case "heif": return "image/heic";
            case "mp4": case "m4v": return "video/mp4";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "wav": return "audio/wav";
            case "ogg": case "opus": return "audio/ogg";
            case "flac": return "audio/flac";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            default: return null;
        }
    }

    private static String rfc1123(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(millis));
    }

    private static String iso8601(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(millis));
    }

    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private void sendText(OutputStream out, int status, String message) throws IOException {
        send(out, status, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8), null);
    }

    private void send(OutputStream out, int status, String type, byte[] body, Map<String, String> extra) throws IOException {
        writeHead(out, status, type, body.length, extra);
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private void writeHead(OutputStream out, int status, String type, long length, Map<String, String> extra) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append("Date: ").append(rfc1123(System.currentTimeMillis())).append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        head.append("Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 207: return "Multi-Status";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 412: return "Precondition Failed";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            default: return "Status";
        }
    }
}
