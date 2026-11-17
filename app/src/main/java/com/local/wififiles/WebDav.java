package com.local.wififiles;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Base64;

/**
 * Минимальный, но совместимый с Проводником Windows и Finder WebDAV-сервер.
 * Монтируется по адресу https://ip:port/dav/ ; логин любой, пароль — код доступа.
 */
final class WebDav {
    private static final String PREFIX = "/dav";
    private static final String REALM = "PocketBridge";
    private static final long MAX_PUT = 20L * 1024 * 1024 * 1024;

    private final File root;
    private final String key;

    WebDav(File root, String key) { this.root = root; this.key = key; }

    static boolean matches(String endpoint) { return endpoint != null && (endpoint.equals(PREFIX) || endpoint.startsWith(PREFIX + "/")); }

    boolean handle(String method, String endpoint, Map<String, String> headers, InputStream in, OutputStream out) throws Exception {
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
            case "OPTIONS": return options(out);
            case "PROPFIND": return propfind(out, target, raw, headers);
            case "GET": case "HEAD": return get(out, target, raw, method.equals("HEAD"));
            case "PUT": return put(out, resolveParent(raw), nameOf(raw), in, headers);
            case "MKCOL": return mkcol(out, target);
            case "DELETE": return delete(out, target);
            case "MOVE": return moveOrCopy(out, target, headers.get("destination"), true, headers);
            case "COPY": return moveOrCopy(out, target, headers.get("destination"), false, headers);
            case "PROPPATCH": return proppatch(out, raw);
            case "LOCK": return lock(out, raw);
            case "UNLOCK": return unlock(out);
            default: sendText(out, 405, "Метод не поддерживается"); return true;
        }
    }

    /* ---------------- методы ---------------- */

    private boolean options(OutputStream out) throws IOException {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("DAV", "1, 2");
        extra.put("MS-Author-Via", "DAV");
        extra.put("Allow", "OPTIONS, GET, HEAD, PROPFIND, PUT, MKCOL, DELETE, MOVE, COPY, PROPPATCH, LOCK, UNLOCK");
        send(out, 200, "text/plain; charset=utf-8", new byte[0], extra);
        return true;
    }

    private boolean propfind(OutputStream out, File target, String raw, Map<String, String> headers) throws Exception {
        String depth = headers.getOrDefault("depth", "1");
        int level = depth.equals("0") ? 0 : 1;
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">");
        appendEntry(body, target, raw);
        if (level == 1 && target.isDirectory() && !Files.isSymbolicLink(target.toPath())) {
            File[] children = target.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    if (child.getName().startsWith(".wifi-upload-") && child.getName().endsWith(".part")) continue;
                    appendEntry(body, child, joinRaw(raw, child.getName()));
                }
            }
        }
        body.append("</D:multistatus>");
        Map<String, String> extra = new LinkedHashMap<>();
        send(out, 207, "application/xml; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8), extra);
        return true;
    }

    private void appendEntry(StringBuilder body, File file, String raw) throws Exception {
        boolean dir = file.isDirectory();
        body.append("<D:response><D:href>").append(escape(hrefOf(raw, dir))).append("</D:href><D:propstat><D:prop>");
        body.append("<D:displayname>").append(escape(file.getName().isEmpty() ? "PocketBridge" : file.getName())).append("</D:displayname>");
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
        body.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }

    private boolean get(OutputStream out, File target, String raw, boolean headOnly) throws Exception {
        if (target.isDirectory()) {
            StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><title>PocketBridge WebDAV</title><h1>PocketBridge WebDAV</h1><p>Монтируйте этот адрес в Проводнике Windows или Finder. Логин любой, пароль — код доступа.</p><ul>");
            File[] children = target.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    String name = escapeHtml(child.getName()) + (child.isDirectory() ? "/" : "");
                    html.append("<li><a href=\"").append(escapeHtml(hrefOf(joinRaw(raw, child.getName()), child.isDirectory()))).append("\">").append(name).append("</a></li>");
                }
            }
            html.append("</ul>");
            byte[] body = html.toString().getBytes(StandardCharsets.UTF_8);
            send(out, 200, "text/html; charset=utf-8", headOnly ? new byte[0] : body, null);
            return true;
        }
        if (!target.isFile() || !target.canRead()) { sendText(out, 404, "Файл не найден"); return true; }
        String mime = mimeOf(target);
        if (mime == null) mime = "application/octet-stream";
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Content-Disposition", "inline; filename*=UTF-8''" + URLEncoder.encode(target.getName(), "UTF-8").replace("+", "%20"));
        writeHead(out, 200, mime, target.length(), extra);
        if (headOnly) { out.flush(); return true; }
        try (InputStream file = new BufferedInputStream(new FileInputStream(target))) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = file.read(buffer)) != -1) out.write(buffer, 0, n);
            out.flush();
        }
        return true;
    }

    private boolean put(OutputStream out, File parent, String name, InputStream in, Map<String, String> headers) throws Exception {
        if (parent == null || !parent.isDirectory()) { sendText(out, 409, "Папка назначения не существует"); return true; }
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals("..")) { sendText(out, 400, "Недопустимое имя"); return true; }
        File target = safeChild(parent, name);
        boolean existed = target.exists();
        long length;
        try { length = Long.parseLong(headers.getOrDefault("content-length", "-1")); } catch (Exception e) { length = -1; }
        if (length < 0 || length > MAX_PUT) { sendText(out, 411, "Нужен Content-Length (до 20 ГБ)"); return true; }
        File tmp = File.createTempFile(".wifi-upload-", ".part", parent);
        try {
            try (OutputStream file = new BufferedOutputStream(new FileOutputStream(tmp))) {
                byte[] buffer = new byte[65536];
                long remaining = length;
                while (remaining > 0) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) throw new IOException("Передача прервана");
                    file.write(buffer, 0, n);
                    remaining -= n;
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
        File target = resolve(targetRaw);
        if (!source.exists()) { sendText(out, 404, "Источник не найден"); return true; }
        boolean overwrite = !"F".equalsIgnoreCase(headers.getOrDefault("overwrite", "T"));
        boolean existed = target.exists();
        if (existed) {
            if (!overwrite) { sendText(out, 412, "Цель существует"); return true; }
            if (target.equals(root) || source.equals(target)) { sendText(out, 403, "Недопустимая цель"); return true; }
            deleteTree(target);
        }
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.mkdirs() && !parent.isDirectory()) { sendText(out, 409, "Родительская папка не существует"); return true; }
        if (move) {
            if (source.renameTo(target)) { send(out, existed ? 204 : 201, "text/plain; charset=utf-8", new byte[0], null); return true; }
            copyTree(source, target);
            deleteTree(source);
        } else {
            copyTree(source, target);
        }
        send(out, existed ? 204 : 201, "text/plain; charset=utf-8", new byte[0], null);
        return true;
    }

    private boolean proppatch(OutputStream out, String raw) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>"
                + escape(hrefOf(raw, false)) + "</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>";
        send(out, 207, "application/xml; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), null);
        return true;
    }

    private boolean lock(OutputStream out, String raw) throws IOException {
        String token = "opaquelocktoken:" + UUID.randomUUID();
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>"
                + "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>infinity</D:depth>"
                + "<D:timeout>Second-3600</D:timeout><D:locktoken><D:href>" + token + "</D:href></D:locktoken>"
                + "</D:activelock></D:lockdiscovery></D:prop>";
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Lock-Token", "<" + token + ">");
        send(out, 200, "application/xml; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), extra);
        return true;
    }

    private boolean unlock(OutputStream out) throws IOException {
        send(out, 204, "text/plain; charset=utf-8", new byte[0], null);
        return true;
    }

    /* ---------------- вспомогательное ---------------- */

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
        String clean = raw;
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.indexOf('\0') >= 0) throw new FileServer.ApiException(400, "Недопустимый путь");
        File file = new File(root, clean).getCanonicalFile();
        String base = root.getCanonicalPath();
        if (!file.equals(root) && !file.getPath().startsWith(base + File.separator)) throw new FileServer.ApiException(400, "Путь вне хранилища");
        return file;
    }

    private File resolveParent(String raw) throws IOException {
        String clean = raw;
        while (clean.startsWith("/")) clean = clean.substring(1);
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
        File child = new File(parent, name).getCanonicalFile();
        if (!child.getParentFile().equals(parent.getCanonicalFile())) throw new FileServer.ApiException(400, "Недопустимое имя");
        return child;
    }

    private String destinationPath(String destination) {
        if (destination == null) return null;
        try {
            String path = destination;
            if (path.startsWith("http://") || path.startsWith("https://")) path = new java.net.URI(path).getPath();
            if (path == null) return null;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (!matches(path)) return null;
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
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new IOException("Не удалось создать папку");
            File[] children = source.listFiles();
            if (children != null) for (File child : children) copyTree(child, new File(target, child.getName()));
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
            try { encoded.append('/').append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20").replace("%2F", "/")); }
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
            case "heic": case "heif": return "image/heic";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
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

    private static String escapeHtml(String text) { return escape(text); }

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
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        head.append("Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 207: return "Multi-Status";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 412: return "Precondition Failed";
            case 500: return "Internal Server Error";
            default: return "Status";
        }
    }
}
