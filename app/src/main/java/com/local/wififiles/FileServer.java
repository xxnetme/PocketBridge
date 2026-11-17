package com.local.wififiles;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.zip.ZipFile;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.SSLContext;

final class FileServer {
    private static final long PREVIEW_TTL = 10L * 60 * 1000;
    private static final long DOWNLOAD_TTL = 10L * 60 * 1000;
    private static final int TICKET_LIMIT = 256;
    /** Максимальный размер файла для перекодирования в превью (50 МБ). Больше — скачивайте файл. */
    private static final long PREVIEW_MAX_SIZE = 50L * 1024 * 1024;
    private static final long MAX_UPLOAD = 20L * 1024 * 1024 * 1024;
    private static final int IO_THREADS_MAX = 32;
    private static final int HEAD_TIMEOUT_MS = 15000;
    private static final int REQUEST_TIMEOUT_MS = 120000;
    private static final int BODY_TIMEOUT_MS = 600000;
    private static final int ZIP_MAX_FILES = 5000;
    private static final long SESSION_TTL = 2L * 60 * 60 * 1000;
    private static final int SESSION_LIMIT = 16;
    private static final int THUMB_WIDTH = 320;
    private static final int CHUNK_MIN = 256 * 1024;
    private static final int CHUNK_MAX = 32 * 1024 * 1024;
    private static final long UPLOAD_TTL = 6L * 60 * 60 * 1000;

    private final Context context; private final File root; private final String key; private final SSLContext ssl; private final File cacheDir; private final int portStart;
    private ServerSocket listener; private ThreadPoolExecutor workers;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Map<String,Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String,Long> sessions = new ConcurrentHashMap<>();
    private final Map<String,Upload> uploads = new ConcurrentHashMap<>();
    private final WebDav dav;

    /** Докачиваемая загрузка: файл пишется по частям в .part и переименовывается в конце. */
    private static final class Upload {
        final String id, name; final File parent, tmp; final long size; final boolean overwrite; final long created;
        long received;
        Upload(String id,String name,File parent,File tmp,long size,boolean overwrite) {
            this.id=id; this.name=name; this.parent=parent; this.tmp=tmp; this.size=size; this.overwrite=overwrite; this.created=System.currentTimeMillis();
        }
    }
    private volatile long lastActivity = System.currentTimeMillis();

    private static final class ZipItem { final File file; final String name; ZipItem(File file,String name) { this.file=file; this.name=name; } }

    private static final class Ticket {
        final String type, path, mime, name; final List<ZipItem> items; final long expires;
        Ticket(String type,String path,String mime,String name,List<ZipItem> items,long expires) {
            this.type=type; this.path=path; this.mime=mime; this.name=name; this.items=items; this.expires=expires;
        }
    }
    /** Ошибка операции с конкретным HTTP-статусом. */
    static final class ApiException extends IOException {
        final int status;
        ApiException(int status,String message) { super(message); this.status=status; }
    }

    FileServer(Context c, File root, String key) throws IOException { this(c, root, key, null, null, 8765); }
    FileServer(Context c, File root, String key, SSLContext ssl) throws IOException { this(c, root, key, ssl, null, 8765); }
    FileServer(Context c, File root, String key, SSLContext ssl, File cacheDir, int portStart) throws IOException {
        this.context=c.getApplicationContext(); this.root=root.getCanonicalFile(); this.key=key; this.ssl=ssl;
        this.cacheDir=cacheDir; this.portStart=Math.max(1024, Math.min(65000, portStart));
        this.dav=new WebDav(this.root, key);
    }

    String key() { return key; }
    long idleMillis() { return System.currentTimeMillis()-lastActivity; }
    int activeConnections() { return connections.size(); }

    int start() throws IOException {
        IOException last=null;
        for(int p=portStart;p<portStart+16;p++) try {
            listener = ssl != null ? ssl.getServerSocketFactory().createServerSocket() : new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(p));
            break;
        } catch(IOException e) { last=e; if(listener!=null) try { listener.close(); } catch(Exception ignored) {} listener=null; }
        if(listener==null) throw last;
        workers=new ThreadPoolExecutor(0, IO_THREADS_MAX, 60, TimeUnit.SECONDS, new SynchronousQueue<>());
        Thread t=new Thread(() -> {
            while(!listener.isClosed()) try {
                Socket s=listener.accept();
                if (!allowedAddress(s.getInetAddress())) { s.close(); continue; }
                s.setSoTimeout(HEAD_TIMEOUT_MS);
                connections.add(s);
                try {
                    workers.execute(() -> { try { handle(s); } finally { connections.remove(s); } });
                } catch (RejectedExecutionException busy) {
                    connections.remove(s);
                    rejectBusy(s);
                }
            } catch(Exception e) { if(!listener.isClosed()) android.util.Log.e("WiFiFiles","accept",e); }
        }, "wifi-file-server");
        t.setDaemon(true); t.start();
        return listener.getLocalPort();
    }

    /** Локальные сети: RFC1918, loopback, CGNAT 100.64/10, IPv6 ULA и link-local. */
    private static boolean allowedAddress(InetAddress ip) {
        if (ip.isLoopbackAddress() || ip.isSiteLocalAddress()) return true;
        byte[] b=ip.getAddress();
        if (b.length==4) { int second=b[1]&0xff; return (b[0]&0xff)==100 && second>=64 && second<=127; }
        if (b.length==16) { int first=b[0]&0xff; if(first==0xfc||first==0xfd) return true; return first==0xfe && (b[1]&0xc0)==0x80; }
        return false;
    }

    private static void rejectBusy(Socket s) {
        try { s.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().flush(); } catch(Exception ignored) {}
        try { s.close(); } catch(Exception ignored) {}
    }

    void stop() {
        try { if(listener!=null) listener.close(); } catch(IOException ignored) {}
        for(Socket s:connections) try { s.close(); } catch(IOException ignored) {}
        if(workers!=null) workers.shutdownNow();
        tickets.clear(); sessions.clear();
        for(Upload upload:uploads.values()) try { upload.tmp.delete(); } catch(Exception ignored) {}
        uploads.clear();
        cleanupThumbsCache();
    }

    private static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int c; while((c=in.read())!=-1) { if(c=='\n') break; if(c!='\r') b.write(c); if(b.size()>max) throw new IOException("Header too long"); }
        if(c==-1 && b.size()==0) return null; return b.toString(StandardCharsets.ISO_8859_1);
    }

    private void handle(Socket socket) {
        try (Socket s=socket; InputStream in=new BufferedInputStream(s.getInputStream()); OutputStream out=new BufferedOutputStream(s.getOutputStream())) {
            String request=line(in,8192); if(request==null) return;
            lastActivity=System.currentTimeMillis();
            String[] parts=request.split(" "); if(parts.length!=3) { sendText(out,400,"Bad Request"); return; }
            Map<String,String> headers=new HashMap<>(); String h; int count=0; while((h=line(in,8192))!=null && !h.isEmpty()) { if(++count>60) throw new IOException("Too many headers"); int n=h.indexOf(':'); if(n>0) headers.put(h.substring(0,n).trim().toLowerCase(Locale.US),h.substring(n+1).trim()); }
            URI uri=new URI(parts[1]); String endpoint=uri.getPath(); Map<String,String> q=parseQuery(uri.getRawQuery());
            boolean body = "/api/upload".equals(endpoint) || "/api/download-zip".equals(endpoint) || "/api/zip-ticket".equals(endpoint) || "/api/upload-chunk".equals(endpoint);
            s.setSoTimeout(body ? BODY_TIMEOUT_MS : REQUEST_TIMEOUT_MS);

            if ("GET".equals(parts[0]) && "/".equals(endpoint)) { byte[] html; try(InputStream a=context.getAssets().open("index.html")) { ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n; while((n=a.read(buf))!=-1) b.write(buf,0,n); html=b.toByteArray(); } send(out,200,"text/html; charset=utf-8",html,null); return; }
            if("GET".equals(parts[0]) && "/preview".equals(endpoint)) { try { streamTicket(out,q.get("ticket"),true,headers.get("range")); } catch(Exception e) { sendText(out,safeStatus(e),e.getMessage()==null?"Ошибка просмотра":e.getMessage()); } return; }
            if("GET".equals(parts[0]) && "/dl".equals(endpoint)) { try { streamTicket(out,q.get("ticket"),false,headers.get("range")); } catch(Exception e) { sendText(out,safeStatus(e),e.getMessage()==null?"Ошибка скачивания":e.getMessage()); } return; }
            if("GET".equals(parts[0]) && "/thumb".equals(endpoint)) { try { thumbnail(out,q.get("token"),q.get("path"),q.get("w")); } catch(Exception e) { sendText(out,safeStatus(e),e.getMessage()==null?"Ошибка миниатюры":e.getMessage()); } return; }
            if(WebDav.matches(endpoint)) { try { dav.handle(parts[0],endpoint,headers,in,out); } catch(Exception e) { sendText(out,safeStatus(e),e.getMessage()==null?"Ошибка WebDAV":e.getMessage()); } return; }
            if(!endpoint.startsWith("/api/")) { sendText(out,404,"Not found"); return; }
            String supplied=headers.getOrDefault("x-access-key",""); if(!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) { sendText(out,403,"Неверный код доступа"); return; }
            try {
                if("GET".equals(parts[0]) && "/api/list".equals(endpoint)) { list(out,q.getOrDefault("path","/")); }
                else if("GET".equals(parts[0]) && "/api/search".equals(endpoint)) { search(out,q.getOrDefault("path","/"),q.get("q")); }
                else if("GET".equals(parts[0]) && "/api/download".equals(endpoint)) { download(out,q.getOrDefault("path","/"),headers.get("range")); }
                else if("GET".equals(parts[0]) && "/api/size".equals(endpoint)) { folderSize(out,q.getOrDefault("path","/")); }
                else if("POST".equals(parts[0]) && "/api/session".equals(endpoint)) { session(out); }
                else if("POST".equals(parts[0]) && "/api/mkdirs".equals(endpoint)) { mkdirs(out,q.get("path")); }
                else if("POST".equals(parts[0]) && "/api/preview-ticket".equals(endpoint)) { previewTicket(out,q.get("path")); }
                else if("POST".equals(parts[0]) && "/api/download-ticket".equals(endpoint)) { downloadTicket(out,q.get("path")); }
                else if("POST".equals(parts[0]) && "/api/zip-ticket".equals(endpoint)) { zipTicket(out,in,headers,q.getOrDefault("base","/")); }
                else if("GET".equals(parts[0]) && "/api/text".equals(endpoint)) { previewText(out,q.get("path")); }
                else if("GET".equals(parts[0]) && "/api/docx".equals(endpoint)) { previewDocx(out,q.get("path")); }
                else if("POST".equals(parts[0]) && "/api/download-zip".equals(endpoint)) { downloadZip(out,in,headers,q.getOrDefault("base","/")); }
                else if("POST".equals(parts[0]) && "/api/mkdir".equals(endpoint)) { File dir=child(q); if(dir.exists()||!dir.mkdir()) throw new ApiException(409,"Не удалось создать папку (возможно, имя занято)"); ok(out); }
                else if("POST".equals(parts[0]) && "/api/rename".equals(endpoint)) { File from=resolve(q.getOrDefault("path","/")); if(from.equals(root)||!from.exists()) throw new ApiException(400,"Нельзя переименовать этот объект"); File target=safeChild(from.getParentFile(), q.get("name")); if(target.exists()||!from.renameTo(target)) throw new ApiException(409,"Не удалось переименовать (возможно, имя занято)"); ok(out); }
                else if("POST".equals(parts[0]) && "/api/delete".equals(endpoint)) { File target=resolve(q.getOrDefault("path","/")); if(target.equals(root)||!target.exists()) throw new ApiException(400,"Нельзя удалить этот объект"); deleteTree(target); ok(out); }
                else if("POST".equals(parts[0]) && "/api/upload".equals(endpoint)) { upload(out,in,headers,q); }
                else if("POST".equals(parts[0]) && "/api/upload-init".equals(endpoint)) { uploadInit(out,q); }
                else if("POST".equals(parts[0]) && "/api/upload-chunk".equals(endpoint)) { uploadChunk(out,in,headers,q); }
                else if("POST".equals(parts[0]) && "/api/upload-finish".equals(endpoint)) { uploadFinish(out,q); }
                else if("POST".equals(parts[0]) && "/api/upload-abort".equals(endpoint)) { uploadAbort(out,q); }
                else sendText(out,404,"Not found");
            } catch(ApiException e) { sendText(out,e.status,e.getMessage()); }
            catch(Exception e) { sendText(out,400,e.getMessage()==null ? "Ошибка операции" : e.getMessage()); }
        } catch(javax.net.ssl.SSLException e) {
            android.util.Log.d("WiFiFiles","tls handshake rejected: "+e.getMessage());   // ожидаемо: клиент не подтвердил сертификат
        } catch(Exception e) { android.util.Log.w("WiFiFiles","request failed: "+e.getMessage()); }
    }

    private static int safeStatus(Exception e) { return e instanceof ApiException ? ((ApiException)e).status : (e.getMessage()==null||e.getMessage().isEmpty()?400:403); }

    private Map<String,String> parseQuery(String query) throws IOException { Map<String,String> m=new HashMap<>(); if(query!=null) for(String pair:query.split("&")) { int i=pair.indexOf('='); String a=i<0?pair:pair.substring(0,i); String b=i<0?"":pair.substring(i+1); m.put(URLDecoder.decode(a,"UTF-8"),URLDecoder.decode(b,"UTF-8")); } return m; }

    private File resolve(String path) throws IOException {
        if(path==null || path.indexOf('\0')>=0) throw new ApiException(400,"Недопустимый путь");
        while(path.startsWith("/")) path=path.substring(1);
        File f=new File(root,path).getCanonicalFile(); String base=root.getPath(); if(!f.equals(root) && !f.getPath().startsWith(base+File.separator)) throw new ApiException(400,"Путь вне доступного хранилища"); return f;
    }

    private File safeChild(File parent,String name) throws IOException {
        if(parent==null || !parent.isDirectory() || name==null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0')>=0 || name.length()>200) throw new ApiException(400,"Недопустимое имя");
        File child=new File(parent,name); File canonical=child.getCanonicalFile(); if(!canonical.getParentFile().equals(parent.getCanonicalFile())) throw new ApiException(400,"Недопустимый путь"); return child;
    }

    private File child(Map<String,String> q) throws IOException { return safeChild(resolve(q.getOrDefault("path","/")),q.get("name")); }

    private String relative(File f) throws IOException { String p=f.getCanonicalPath(); String base=root.getCanonicalPath(); if(p.equals(base)) return "/"; if(!p.startsWith(base+"/")) throw new ApiException(400,"Путь вне доступного хранилища"); return p.substring(base.length()); }

    /** NFC-нормализация: macOS отдаёт имена в NFD, поиск должен их находить. */
    static String normalize(String text) {
        try { return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
        catch(Exception e) { return text.toLowerCase(Locale.ROOT); }
    }

    private static boolean isTempName(String name) { return name.startsWith(".wifi-upload-") && name.endsWith(".part"); }

    private void list(OutputStream out,String path) throws Exception {
        File d=resolve(path); if(!d.isDirectory()) throw new ApiException(400,"Папка недоступна"); File[] files=d.listFiles(); if(files==null) throw new ApiException(400,"Нет доступа к папке");
        Arrays.sort(files,(a,b)-> { if(a.isDirectory()!=b.isDirectory()) return a.isDirectory()?-1:1; return a.getName().compareToIgnoreCase(b.getName()); });
        JSONArray arr=new JSONArray(); for(File f:files) { if(isTempName(f.getName())) continue; try { arr.put(item(f)); } catch(IOException ignored) {} }
        JSONObject data=new JSONObject(); data.put("path",relative(d)); data.put("items",arr); send(out,200,"application/json; charset=utf-8",data.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private JSONObject item(File f) throws Exception {
        JSONObject x=new JSONObject(); x.put("name",f.getName()); x.put("path",relative(f)); x.put("dir",f.isDirectory()); x.put("size",f.length()); x.put("modified",f.lastModified()); return x;
    }

    private void search(OutputStream out,String path,String query) throws Exception {
        File base=resolve(path); if(!base.isDirectory()) throw new ApiException(400,"Папка недоступна");
        String needle=query==null?"":normalize(query.trim());
        if(needle.length()<2) throw new ApiException(400,"Введите минимум 2 символа");
        ArrayDeque<File> queue=new ArrayDeque<>(); queue.add(base);
        JSONArray arr=new JSONArray(); long visited=0; boolean truncated=false;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!queue.isEmpty()) {
            if(visited>=20000 || arr.length()>=200 || System.nanoTime()>deadline) { truncated=true; break; }
            File dir=queue.poll(); File[] children=dir.listFiles(); if(children==null) continue;
            for(File f:children) {
                if(++visited>=20000) { truncated=true; break; }
                String name=f.getName();
                if(isTempName(name)) continue;
                if(normalize(name).contains(needle)) { arr.put(item(f)); if(arr.length()>=200) { truncated=true; break; } }
                if(f.isDirectory() && !Files.isSymbolicLink(f.toPath())) queue.add(f);
            }
        }
        JSONObject data=new JSONObject(); data.put("items",arr); data.put("truncated",truncated); send(out,200,"application/json; charset=utf-8",data.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private void download(OutputStream out,String path,String range) throws Exception {
        File f=resolve(path); if(!f.isFile()||!f.canRead()) throw new ApiException(400,"Файл недоступен");
        sendFile(out,f,"application/octet-stream",true,range);
    }

    private static String extension(File f) { String n=f.getName().toLowerCase(Locale.ROOT); int dot=n.lastIndexOf('.'); return dot<0?"":n.substring(dot+1); }
    private static String mediaMime(File f) {
        switch(extension(f)) {
            case "jpg": case "jpeg": return "image/jpeg"; case "png": return "image/png"; case "gif": return "image/gif"; case "webp": return "image/webp"; case "bmp": return "image/bmp"; case "avif": return "image/avif";
            case "mp4": case "m4v": return "video/mp4"; case "webm": return "video/webm"; case "ogv": return "video/ogg"; case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg"; case "m4a": return "audio/mp4"; case "wav": return "audio/wav"; case "ogg": case "opus": return "audio/ogg"; case "flac": return "audio/flac";
            case "heic": case "heif": return "image/heic";
            case "dng": return "image/x-adobe-dng";
            case "cr2": return "image/x-canon-cr2";
            case "nef": return "image/x-nikon-nef";
            case "arw": return "image/x-sony-arw";
            case "raf": return "image/x-fuji-raf";
            case "pdf": return "application/pdf"; default: return null;
        }
    }
    private File readable(String path) throws IOException { File f=resolve(path); if(!f.isFile() || !f.canRead()) throw new ApiException(400,"Файл недоступен"); return f; }

    private String issueSession() throws IOException {
        long now=System.currentTimeMillis();
        for(Map.Entry<String,Long> e:sessions.entrySet()) if(e.getValue()<now) sessions.remove(e.getKey());
        if(sessions.size()>=SESSION_LIMIT) sessions.clear();     // старые браузерные сессии не должны блокировать новые
        byte[] random=new byte[16]; new SecureRandom().nextBytes(random); StringBuilder id=new StringBuilder(); for(byte b:random) id.append(String.format(Locale.US,"%02x",b & 255));
        sessions.put(id.toString(),now+SESSION_TTL); return id.toString();
    }

    private void requireSession(String token) throws ApiException {
        Long expires=token==null?null:sessions.get(token);
        if(expires==null || expires<System.currentTimeMillis()) throw new ApiException(403,"Сессия истекла. Обновите страницу.");
    }

    private String issue(String type,String path,String mime,String name,List<ZipItem> items,long ttl) throws IOException {
        long now=System.currentTimeMillis(); for(Map.Entry<String,Ticket> e:tickets.entrySet()) if(e.getValue().expires<now) tickets.remove(e.getKey());
        if(tickets.size()>=TICKET_LIMIT) throw new ApiException(429,"Слишком много активных передач. Подождите пару минут.");
        byte[] random=new byte[16]; new SecureRandom().nextBytes(random); StringBuilder id=new StringBuilder(); for(byte b:random) id.append(String.format(Locale.US,"%02x",b & 255));
        tickets.put(id.toString(),new Ticket(type,path,mime,name,items,now+ttl)); return id.toString();
    }

    private void session(OutputStream out) throws Exception {
        JSONObject json=new JSONObject(); json.put("token",issueSession()); json.put("ttl",SESSION_TTL);
        send(out,200,"application/json; charset=utf-8",json.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private void mkdirs(OutputStream out,String path) throws Exception {
        if(path==null) throw new ApiException(400,"Недопустимый путь");
        String clean=path; while(clean.startsWith("/")) clean=clean.substring(1);
        File current=root;
        for(String segment:clean.split("/")) {
            if(segment.isEmpty()||segment.equals(".")) continue;
            current=safeChild(current,segment);
            if(!current.exists() && !current.mkdir()) throw new ApiException(409,"Не удалось создать папку: "+segment);
            if(!current.isDirectory()) throw new ApiException(409,"Занято файлом: "+segment);
        }
        ok(out);
    }

    private void thumbnail(OutputStream out,String token,String path,String width) throws Exception {
        requireSession(token);
        File f=readable(path);
        String mime=mediaMime(f);
        boolean image=mime!=null && mime.startsWith("image/");
        boolean video=mime!=null && mime.startsWith("video/");
        if(!image && !video) throw new ApiException(400,"Миниатюры доступны только для изображений и видео");
        int requested=THUMB_WIDTH; try { if(width!=null && !width.isEmpty()) requested=Integer.parseInt(width); } catch(Exception ignored) {}
        String key=thumbnailKey(f,requested);
        byte[] jpeg=null;
        if(cacheDir!=null) {
            File cache=new File(cacheDir,"thumbs");
            File cached=new File(cache,key+".jpg");
            if(cached.isFile() && cached.length()>0 && cached.lastModified()>=f.lastModified()) {
                jpeg=Files.readAllBytes(cached.toPath());
            } else {
                jpeg = video ? Thumbnails.videoFrame(f,requested) : Thumbnails.jpeg(f,requested);
                try {
                    if(!cache.isDirectory()) cache.mkdirs();
                    File tmp=File.createTempFile("thumb-",".part",cache);
                    Files.write(tmp.toPath(),jpeg);
                    Files.move(tmp.toPath(),cached.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    cleanupThumbsCache();
                } catch(Exception ignored) { /* кеш не критичен */ }
            }
        } else {
            jpeg = video ? Thumbnails.videoFrame(f,requested) : Thumbnails.jpeg(f,requested);
        }
        String extra="X-Thumb-Key: "+key+"\r\n";
        send(out,200,"image/jpeg",jpeg,extra);
    }

    private String thumbnailKey(File f,int width) throws Exception {
        String seed; try { seed=relative(f)+"|"+f.lastModified()+"|"+f.length()+"|"+width; } catch(IOException e) { seed=f.getAbsolutePath()+"|"+width; }
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(); for(byte b:hash) hex.append(String.format(Locale.US,"%02x",b & 255));
        return hex.toString();
    }

    private void previewTicket(OutputStream out,String path) throws Exception {
        File f=readable(path); String mime=mediaMime(f); if(mime==null) throw new ApiException(400,"Формат не поддерживается");
        String id=issue("preview",relative(f),mime,f.getName(),null,PREVIEW_TTL);
        JSONObject json=new JSONObject(); json.put("url","/preview?ticket="+id); send(out,200,"application/json; charset=utf-8",json.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private void downloadTicket(OutputStream out,String path) throws Exception {
        File f=readable(path);
        String id=issue("download",relative(f),"application/octet-stream",f.getName(),null,DOWNLOAD_TTL);
        JSONObject json=new JSONObject(); json.put("url","/dl?ticket="+id); send(out,200,"application/json; charset=utf-8",json.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private void zipTicket(OutputStream out,InputStream in,Map<String,String> headers,String base) throws Exception {
        List<ZipItem> items=readSelection(in,headers,base);
        String id=issue("zip",null,"application/zip","files.zip",items,DOWNLOAD_TTL);
        JSONObject json=new JSONObject(); json.put("url","/dl?ticket="+id); send(out,200,"application/json; charset=utf-8",json.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private void streamTicket(OutputStream out,String token,boolean inline,String range) throws Exception {
        Ticket ticket=token==null?null:tickets.get(token); if(ticket==null || ticket.expires<System.currentTimeMillis()) { sendText(out,403,"Срок действия ссылки истёк. Обновите страницу."); return; }
        if("zip".equals(ticket.type)) { writeZip(out,ticket.items); return; }
        File f=readable(ticket.path); if(ticket.mime!=null && !"application/octet-stream".equals(ticket.mime) && !ticket.mime.equals(mediaMime(f))) throw new ApiException(400,"Файл изменился");
        if("image/heic".equals(ticket.mime) || ticket.mime!=null && ticket.mime.startsWith("image/x-")) {
            // Браузеры не умеют HEIC/RAW: отдаём перекодированный JPEG (лимит 50 МБ)
            if(f.length()>PREVIEW_MAX_SIZE) throw new ApiException(400,"Файл больше 50 МБ — скачайте его для просмотра");
            byte[] jpeg=Thumbnails.previewJpeg(f);
            send(out,200,"image/jpeg",jpeg,null);
            return;
        }
        sendFile(out,f,ticket.mime!=null?ticket.mime:"application/octet-stream",!inline,range);
    }

    private void sendFile(OutputStream out,File f,String mime,boolean attachment,String range) throws IOException {
        long total=f.length(),start=0,end=total-1; boolean partial=false;
        if(range!=null && !range.isEmpty()) {
            try {
                if(!range.startsWith("bytes=")||range.indexOf(',')>=0||total==0) throw new Exception(); String spec=range.substring(6); int dash=spec.indexOf('-'); if(dash<0) throw new Exception(); String a=spec.substring(0,dash).trim(),b=spec.substring(dash+1).trim();
                if(a.isEmpty()) { long suffix=Long.parseLong(b); if(suffix<=0) throw new Exception(); start=Math.max(0,total-suffix); } else start=Long.parseLong(a);
                if(!b.isEmpty()&&!a.isEmpty()) end=Math.min(end,Long.parseLong(b)); if(start<0||start>=total||end<start) throw new Exception(); partial=true;
            } catch(Exception e) { out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"+total+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush(); return; }
        }
        long count=total==0?0:end-start+1;
        String name=URLEncoder.encode(f.getName(),"UTF-8").replace("+","%20");
        StringBuilder h=new StringBuilder();
        h.append("HTTP/1.1 ").append(partial?"206 Partial Content":"200 OK").append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Content-Length: ").append(count).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if(partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
        h.append("Content-Disposition: ").append(attachment?"attachment":"inline").append("; filename*=UTF-8''").append(name).append("\r\n");
        h.append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: SAMEORIGIN\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if(total==0) { out.flush(); return; }
        try(RandomAccessFile raf=new RandomAccessFile(f,"r")) { raf.seek(start); byte[] buf=new byte[65536]; long remaining=count; while(remaining>0) { int n=raf.read(buf,0,(int)Math.min(buf.length,remaining)); if(n<0) break; out.write(buf,0,n); remaining-=n; } out.flush(); }
    }

    private void previewText(OutputStream out,String path) throws Exception {
        File f=readable(path); String ext=extension(f); if(!Arrays.asList("txt","md","csv","log","json","xml","html","htm","css","js").contains(ext)) throw new ApiException(400,"Формат не поддерживается");
        if(f.length()>5L*1024*1024) throw new ApiException(400,"Файл больше 5 МБ — скачайте его для просмотра");
        try(InputStream in=new FileInputStream(f); ByteArrayOutputStream b=new ByteArrayOutputStream()) { byte[] buf=new byte[8192]; int n,total=0; while((n=in.read(buf))!=-1) { total+=n; if(total>5*1024*1024) throw new ApiException(400,"Файл слишком большой"); b.write(buf,0,n); } send(out,200,"text/plain; charset=utf-8",b.toByteArray(),null); }
    }

    private void previewDocx(OutputStream out,String path) throws Exception {
        File f=readable(path); if(!"docx".equals(extension(f))) throw new ApiException(400,"Нужен файл DOCX");
        StringBuilder text=new StringBuilder(); boolean truncated=false;
        try(ZipFile zip=new ZipFile(f)) {
            ZipEntry entry=zip.getEntry("word/document.xml"); if(entry==null || entry.getSize()<0 || entry.getSize()>8L*1024*1024) throw new ApiException(400,"Документ слишком большой или повреждён");
            try(InputStream in=new BoundedInputStream(zip.getInputStream(entry),8L*1024*1024)) {
                XmlPullParser parser=Xml.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true); parser.setInput(in,"UTF-8"); boolean inText=false; int type;
                while((type=parser.nextToken())!=XmlPullParser.END_DOCUMENT) {
                    if(type==XmlPullParser.DOCDECL) throw new ApiException(400,"Документ с DTD не поддерживается");
                    if(type==XmlPullParser.START_TAG) { String n=parser.getName(); if("t".equals(n)) inText=true; else if("tab".equals(n)) text.append('\t'); else if("br".equals(n)) text.append('\n'); }
                    else if(type==XmlPullParser.END_TAG) { String n=parser.getName(); if("t".equals(n)) inText=false; else if("p".equals(n)) text.append('\n'); }
                    else if((type==XmlPullParser.TEXT || type==XmlPullParser.CDSECT || type==XmlPullParser.ENTITY_REF) && inText && parser.getText()!=null) text.append(parser.getText());
                    if(text.length()>1000000) { text.setLength(1000000); truncated=true; break; }
                }
            }
        }
        JSONObject data=new JSONObject(); data.put("text",text.toString()); data.put("truncated",truncated); send(out,200,"application/json; charset=utf-8",data.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining; BoundedInputStream(InputStream input,long limit) { super(input); remaining=limit; }
        @Override public int read() throws IOException { if(remaining<=0) throw new IOException("Документ слишком большой"); int n=super.read(); if(n>=0) remaining--; return n; }
        @Override public int read(byte[] b,int off,int len) throws IOException { if(remaining<=0) throw new IOException("Документ слишком большой"); int n=super.read(b,off,(int)Math.min(len,remaining)); if(n>0) remaining-=n; return n; }
    }

    private void folderSize(OutputStream out,String path) throws Exception {
        File folder=resolve(path); if (!folder.isDirectory()) throw new ApiException(400,"Папка недоступна");
        ArrayDeque<Path> pending=new ArrayDeque<>(); pending.push(folder.toPath()); long total=0, visited=0; boolean partial=false; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime()>deadline || visited>=200000) { partial=true; break; }
            Path dir=pending.pop();
            try (DirectoryStream<Path> entries=Files.newDirectoryStream(dir)) {
                for (Path pathItem:entries) {
                    if (++visited>=200000 || Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) { partial=true; break; }
                    try {
                        if (Files.isSymbolicLink(pathItem)) { partial=true; continue; }
                        File f=pathItem.toFile();
                        if (f.isDirectory()) { if (pending.size() >= 20000) partial=true; else pending.push(pathItem); }
                        else if (f.isFile() && f.canRead()) { long size=f.length(); total=Long.MAX_VALUE-total<size ? Long.MAX_VALUE : total+size; } else partial=true;
                    } catch(Exception e) { partial=true; }
                }
            } catch(Exception e) { partial=true; }
            if (partial && (Thread.currentThread().isInterrupted() || System.nanoTime()>deadline || visited>=200000)) break;
        }
        JSONObject data=new JSONObject(); data.put("bytes",total); data.put("partial",partial); send(out,200,"application/json; charset=utf-8",data.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    /* ---------- ZIP: с сохранением структуры папок и папками целиком ---------- */

    private List<ZipItem> readSelection(InputStream in,Map<String,String> headers,String basePath) throws Exception {
        int length; try { length=Integer.parseInt(headers.getOrDefault("content-length","-1")); } catch(Exception e) { length=-1; }
        if(length<2 || length>1024*1024) throw new ApiException(400,"Некорректный список файлов");
        byte[] body=new byte[length]; int n=0; while(n<length) { int r=in.read(body,n,length-n); if(r<0) throw new IOException("Передача прервана"); n+=r; }
        JSONArray selected=new JSONObject(new String(body,StandardCharsets.UTF_8)).getJSONArray("paths");
        if(selected.length()<1 || selected.length()>500) throw new ApiException(400,"Можно выбрать от 1 до 500 объектов");
        File base=resolve(basePath); if(!base.isDirectory()) throw new ApiException(400,"Некорректная папка для архива");
        ArrayList<ZipItem> items=new ArrayList<>(); Set<String> names=new HashSet<>(); long budget=ZIP_MAX_FILES;
        for(int i=0;i<selected.length();i++) {
            File f=resolve(selected.getString(i));
            if(!f.exists()) throw new ApiException(400,"Выберите только доступные объекты");
            budget=collect(f,base,items,names,budget,0);
        }
        if(items.isEmpty()) throw new ApiException(400,"В выбранных объектах нет файлов");
        return items;
    }

    private long collect(File f,File base,List<ZipItem> items,Set<String> names,long budget,int depth) throws IOException {
        if(budget<=0) throw new ApiException(400,"В архиве слишком много файлов (лимит "+ZIP_MAX_FILES+")");
        if(depth>40) throw new ApiException(400,"Слишком глубокая вложенность папок");
        String relativeRoot=relative(f);
        String basePath=base.equals(root)?"/":relative(base);
        if(relativeRoot.equals(basePath)) {
            if(!f.isDirectory()) return budget;              // сам base-файл не архивируем
            File[] children=(Files.isSymbolicLink(f.toPath())?null:f.listFiles());
            if(children==null) return budget;
            Arrays.sort(children,Comparator.comparing(File::getName));
            for(File c:children) budget=collect(c,base,items,names,budget,depth+1);
            return budget;
        }
        String prefix=basePath.endsWith("/")?basePath:basePath+"/";
        if(!relativeRoot.startsWith(prefix)) throw new ApiException(400,"Объекты вне выбранной папки");
        String entry=relativeRoot.substring(prefix.length());
        if(f.isDirectory()) {
            if(Files.isSymbolicLink(f.toPath())) return budget;
            File[] children=f.listFiles(); if(children==null) return budget;
            Arrays.sort(children,Comparator.comparing(File::getName));
            for(File c:children) budget=collect(c,base,items,names,budget,depth+1);
            return budget;
        }
        if(!f.isFile() || !f.canRead()) return budget;
        String unique=entry; int counter=2;
        while(!names.add(unique)) { unique=suffixName(entry,counter++); }
        items.add(new ZipItem(f,unique));
        return budget-1;
    }

    private static String suffixName(String name,int counter) {
        int slash=name.lastIndexOf('/'); int dot=name.lastIndexOf('.');
        if(dot<=slash) return name+" ("+counter+")";
        return name.substring(0,dot)+" ("+counter+")"+name.substring(dot);
    }

    private void downloadZip(OutputStream out,InputStream in,Map<String,String> headers,String base) throws Exception {
        writeZip(out,readSelection(in,headers,base));
    }

    private void writeZip(OutputStream out,List<ZipItem> items) throws IOException {
        String response="HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nTransfer-Encoding: chunked\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Disposition: attachment; filename=files.zip\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        ChunkedOutput chunked=new ChunkedOutput(out); ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(chunked,65536),StandardCharsets.UTF_8); zip.setLevel(1);
        byte[] buffer=new byte[65536];
        for(ZipItem item:items) {
            try(InputStream file=new BufferedInputStream(new FileInputStream(item.file))) {
                ZipEntry entry=new ZipEntry(item.name); entry.setTime(item.file.lastModified());
                zip.putNextEntry(entry); int nread; while((nread=file.read(buffer))!=-1) zip.write(buffer,0,nread); zip.closeEntry();
            } catch(IOException e) {
                android.util.Log.w("WiFiFiles","zip skip: "+item.name+" "+e.getMessage());
            }
        }
        zip.finish(); zip.flush(); chunked.finish();
    }

    private static final class ChunkedOutput extends OutputStream {
        private final OutputStream out; ChunkedOutput(OutputStream out) { this.out=out; }
        @Override public void write(int b) throws IOException { byte[] one={(byte)b}; write(one,0,1); }
        @Override public void write(byte[] b,int off,int len) throws IOException { if(len==0)return; out.write((Integer.toHexString(len)+"\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(b,off,len); out.write('\r'); out.write('\n'); }
        void finish() throws IOException { out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush(); }
    }

    private void deleteTree(File f) throws IOException {
        if(!f.getCanonicalFile().equals(f.getAbsoluteFile())) { if(!f.delete()) throw new ApiException(400,"Не удалось удалить ссылку: "+f.getName()); return; }
        if(f.isDirectory()) { File[] children=f.listFiles(); if(children==null) throw new ApiException(400,"Нет доступа к папке: "+f.getName()); for(File c:children) deleteTree(c); }
        if(!f.delete()) throw new ApiException(400,"Не удалось удалить: "+f.getName());
    }

    private void upload(OutputStream out,InputStream in,Map<String,String> headers,Map<String,String> q) throws Exception {
        File parent=resolve(q.getOrDefault("path","/"));
        String requested=q.get("name");
        File target=safeChild(parent,requested);
        boolean overwrite="1".equals(q.get("overwrite"));
        boolean unique="1".equals(q.get("unique"));
        if(unique) target=uniqueTarget(parent,requested);
        else if(target.exists() && !overwrite) throw new ApiException(409,"Файл с таким именем уже существует");
        long length; try { length=Long.parseLong(headers.getOrDefault("content-length","-1")); } catch(Exception e) { length=-1; }
        if(length<0 || length>MAX_UPLOAD) throw new ApiException(400,"Некорректный размер файла");
        File tmp=File.createTempFile(".wifi-upload-", ".part",parent); boolean ok=false;
        try {
            try(OutputStream file=new BufferedOutputStream(new FileOutputStream(tmp))) { byte[] b=new byte[65536]; long remaining=length; while(remaining>0) { int n=in.read(b,0,(int)Math.min(b.length,remaining)); if(n<0) throw new IOException("Передача прервана"); file.write(b,0,n); remaining-=n; } }
            if(overwrite && !unique) {
                try { Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING); }
                catch(Exception moveError) { if(target.exists() && !target.delete()) throw new ApiException(400,"Не удалось заменить файл"); if(!tmp.renameTo(target)) throw new ApiException(400,"Не удалось сохранить файл"); }
            } else {
                if(target.exists() || !tmp.renameTo(target)) throw new ApiException(409,"Файл с таким именем уже существует");
            }
            ok=true;
        } finally { if(!ok) tmp.delete(); }
        ok(out);
    }

    private static File uniqueTarget(File parent,String name) throws IOException {
        File candidate=new File(parent,name); if(!candidate.exists()) return candidate;
        int dot=name.lastIndexOf('.'); String base=dot>0?name.substring(0,dot):name; String ext=dot>0?name.substring(dot):"";
        for(int i=1;i<=9999;i++) { File next=new File(parent,base+" ("+i+")"+ext); if(!next.exists()) return next; }
        throw new ApiException(409,"Не удалось подобрать свободное имя");
    }

    private void cleanupUploads() {
        long now=System.currentTimeMillis();
        for(Map.Entry<String,Upload> e:uploads.entrySet()) {
            if(now-e.getValue().created>UPLOAD_TTL) { Upload upload=uploads.remove(e.getKey()); if(upload!=null) upload.tmp.delete(); }
        }
    }

    /** Ограничивает кеш миниатюр 200 МБ: удаляет самые старые файлы, пока размер не уложится в лимит. */
    private static final long THUMB_CACHE_LIMIT = 200L * 1024 * 1024;
    private void cleanupThumbsCache() {
        if(cacheDir==null) return;
        File cache=new File(cacheDir,"thumbs");
        if(!cache.isDirectory()) return;
        File[] files=cache.listFiles((dir,name)->name.endsWith(".jpg"));
        if(files==null || files.length==0) return;
        java.util.Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified));
        long total=0;
        for(File f:files) total+=f.length();
        for(File f:files) {
            if(total<=THUMB_CACHE_LIMIT) break;
            total-=f.length();
            f.delete();
        }
    }

    private Upload requireUpload(String id) throws ApiException {
        cleanupUploads();
        Upload upload=id==null?null:uploads.get(id);
        if(upload==null) throw new ApiException(404,"Загрузка не найдена или устарела. Начните заново.");
        return upload;
    }

    private void uploadInit(OutputStream out,Map<String,String> q) throws Exception {
        cleanupUploads();
        File parent=resolve(q.getOrDefault("path","/"));
        if(!parent.isDirectory()) throw new ApiException(400,"Папка недоступна");
        String name=q.get("name");
        File target=safeChild(parent,name);
        boolean overwrite="1".equals(q.get("overwrite"));
        boolean unique="1".equals(q.get("unique"));
        if(unique) target=uniqueTarget(parent,name);
        else if(target.exists() && !overwrite) throw new ApiException(409,"Файл с таким именем уже существует");
        long size; try { size=Long.parseLong(q.getOrDefault("size","-1")); } catch(Exception e) { size=-1; }
        if(size<0 || size>MAX_UPLOAD) throw new ApiException(400,"Некорректный размер файла");
        if(uploads.size()>=32) throw new ApiException(429,"Слишком много одновременных загрузок");
        byte[] random=new byte[12]; new SecureRandom().nextBytes(random); StringBuilder id=new StringBuilder(); for(byte b:random) id.append(String.format(Locale.US,"%02x",b & 255));
        File tmp=File.createTempFile(".wifi-upload-", ".part", parent);
        Upload upload=new Upload(id.toString(),target.getName(),parent,tmp,size,overwrite);
        uploads.put(upload.id, upload);
        JSONObject json=new JSONObject(); json.put("uploadId",upload.id); json.put("name",upload.name); json.put("chunk",4*1024*1024); json.put("received",0);
        send(out,200,"application/json; charset=utf-8",json.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private void uploadChunk(OutputStream out,InputStream in,Map<String,String> headers,Map<String,String> q) throws Exception {
        Upload upload=requireUpload(q.get("id"));
        long offset; try { offset=Long.parseLong(q.getOrDefault("offset","-1")); } catch(Exception e) { offset=-1; }
        if(offset!=upload.received) { JSONObject json=new JSONObject(); json.put("received",upload.received); throw new ApiException(409,"Несовпадение смещения: ожидается "+upload.received); }
        int length; try { length=Integer.parseInt(headers.getOrDefault("content-length","-1")); } catch(Exception e) { length=-1; }
        if(length<=0 || length>CHUNK_MAX) throw new ApiException(400,"Некорректный размер части (до 32 МБ)");
        if(upload.received+length>upload.size) throw new ApiException(400,"Часть выходит за размер файла");
        byte[] buffer=new byte[Math.min(65536,length)]; long remaining=length;
        try(OutputStream file=new FileOutputStream(upload.tmp,true)) {
            while(remaining>0) {
                int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(n<0) throw new IOException("Передача прервана");
                file.write(buffer,0,n); remaining-=n;
            }
        }
        upload.received+=length;
        JSONObject json=new JSONObject(); json.put("received",upload.received); json.put("size",upload.size);
        send(out,200,"application/json; charset=utf-8",json.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private void uploadFinish(OutputStream out,Map<String,String> q) throws Exception {
        Upload upload=requireUpload(q.get("id"));
        if(upload.received!=upload.size) throw new ApiException(409,"Загружено "+upload.received+" из "+upload.size+" байт");
        File target=new File(upload.parent,upload.name);
        boolean ok=false;
        try {
            if(target.exists() && !upload.overwrite) throw new ApiException(409,"Файл с таким именем уже существует");
            if(upload.overwrite) {
                try { Files.move(upload.tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING); }
                catch(Exception moveError) { if(target.exists() && !target.delete()) throw new ApiException(400,"Не удалось заменить файл"); if(!upload.tmp.renameTo(target)) throw new ApiException(400,"Не удалось сохранить файл"); }
            } else {
                try { Files.move(upload.tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE); }
                catch(Exception e) { if(!upload.tmp.renameTo(target)) throw new ApiException(409,"Не удалось сохранить файл: "+e.getMessage()); }
            }
            ok=true;
        } finally {
            uploads.remove(upload.id);
            if(!ok) upload.tmp.delete();
        }
        JSONObject json=new JSONObject(); json.put("ok",true); json.put("name",upload.name); json.put("size",upload.size);
        send(out,200,"application/json; charset=utf-8",json.toString().getBytes(StandardCharsets.UTF_8),null);
    }

    private void uploadAbort(OutputStream out,Map<String,String> q) throws Exception {
        Upload upload=uploads.remove(q.get("id"));
        if(upload!=null) upload.tmp.delete();
        ok(out);
    }

    private void ok(OutputStream out) throws IOException { send(out,200,"application/json; charset=utf-8","{\"ok\":true}".getBytes(StandardCharsets.UTF_8),null); }
    private void sendText(OutputStream out,int status,String msg) throws IOException { send(out,status,"text/plain; charset=utf-8",msg.getBytes(StandardCharsets.UTF_8),null); }
    private void send(OutputStream out,int status,String type,byte[] data,String extra) throws IOException { header(out,status,type,data.length,extra); out.write(data); out.flush(); }
    private void header(OutputStream out,int status,String type,long length,String extra) throws IOException {
        String reason=reason(status);
        String headers="HTTP/1.1 "+status+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n"+(extra==null?"":extra)+"\r\n"; out.write(headers.getBytes(StandardCharsets.US_ASCII));
    }
    private static String reason(int status) {
        switch(status) { case 200: return "OK"; case 206: return "Partial Content"; case 400: return "Bad Request"; case 403: return "Forbidden"; case 404: return "Not Found"; case 409: return "Conflict"; case 416: return "Range Not Satisfiable"; case 429: return "Too Many Requests"; case 503: return "Service Unavailable"; default: return "Error"; }
    }
}
