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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.zip.ZipFile;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class FileServer {
    private final Context context; private final File root; private final String key; private ServerSocket listener; private ExecutorService workers; private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Map<String,PreviewTicket> previews = new ConcurrentHashMap<>();
    private static final class PreviewTicket { final String path, mime; final long expires; PreviewTicket(String path,String mime) { this.path=path; this.mime=mime; this.expires=System.currentTimeMillis()+30L*60*1000; } }
    FileServer(Context c, File root, String key) throws IOException { this.context=c.getApplicationContext(); this.root=root.getCanonicalFile(); this.key=key; }
    int start() throws IOException {
        IOException last=null; for(int p=8765;p<=8780;p++) try { listener=new ServerSocket(); listener.setReuseAddress(true); listener.bind(new InetSocketAddress(p)); break; } catch(IOException e) { last=e; if(listener!=null) try { listener.close(); } catch(Exception ignored) {} listener=null; }
        if(listener==null) throw last; workers=Executors.newFixedThreadPool(6);
        Thread t=new Thread(() -> { while(!listener.isClosed()) try { Socket s=listener.accept(); if (!s.getInetAddress().isSiteLocalAddress() && !s.getInetAddress().isLoopbackAddress()) { s.close(); continue; } s.setSoTimeout(120000); connections.add(s); try { workers.execute(() -> { try { handle(s); } finally { connections.remove(s); } }); } catch (RuntimeException e) { connections.remove(s); s.close(); } } catch(Exception e) { if(!listener.isClosed()) android.util.Log.e("WiFiFiles","accept",e); } }, "wifi-file-server"); t.setDaemon(true); t.start(); return listener.getLocalPort();
    }
    void stop() { try { if(listener!=null) listener.close(); } catch(IOException ignored) {} for(Socket s:connections) try { s.close(); } catch(IOException ignored) {} if(workers!=null) workers.shutdownNow(); }
    private static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int c; while((c=in.read())!=-1) { if(c=='\n') break; if(c!='\r') b.write(c); if(b.size()>max) throw new IOException("Header too long"); }
        if(c==-1 && b.size()==0) return null; return b.toString("ISO-8859-1");
    }
    private void handle(Socket socket) {
        try (Socket s=socket; InputStream in=new BufferedInputStream(s.getInputStream()); OutputStream out=new BufferedOutputStream(s.getOutputStream())) {
            String request=line(in,8192); if(request==null) return; String[] parts=request.split(" "); if(parts.length!=3) { sendText(out,400,"Bad Request"); return; }
            Map<String,String> headers=new HashMap<>(); String h; int count=0; while((h=line(in,8192))!=null && !h.isEmpty()) { if(++count>60) throw new IOException("Too many headers"); int n=h.indexOf(':'); if(n>0) headers.put(h.substring(0,n).trim().toLowerCase(Locale.US),h.substring(n+1).trim()); }
            URI uri=new URI(parts[1]); String endpoint=uri.getPath(); Map<String,String> q=parseQuery(uri.getRawQuery());
            if ("GET".equals(parts[0]) && "/".equals(endpoint)) { byte[] html; try(InputStream a=context.getAssets().open("index.html")) { ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n; while((n=a.read(buf))!=-1) b.write(buf,0,n); html=b.toByteArray(); } send(out,200,"text/html; charset=utf-8",html,null); return; }
            if("GET".equals(parts[0]) && "/preview".equals(endpoint)) { try { streamPreview(out,q.get("ticket"),headers.get("range")); } catch(Exception e) { sendText(out,400,e.getMessage()==null?"Ошибка просмотра":e.getMessage()); } return; }
            if(!endpoint.startsWith("/api/")) { sendText(out,404,"Not found"); return; }
            String supplied=headers.getOrDefault("x-access-key",""); if(!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) { sendText(out,403,"Неверный код доступа"); return; }
            try {
                if("GET".equals(parts[0]) && "/api/list".equals(endpoint)) { list(out,q.getOrDefault("path","/")); }
                else if("GET".equals(parts[0]) && "/api/download".equals(endpoint)) { download(out,q.getOrDefault("path","/")); }
                else if("GET".equals(parts[0]) && "/api/size".equals(endpoint)) { folderSize(out,q.getOrDefault("path","/")); }
                else if("POST".equals(parts[0]) && "/api/preview-ticket".equals(endpoint)) { previewTicket(out,q.get("path")); }
                else if("GET".equals(parts[0]) && "/api/text".equals(endpoint)) { previewText(out,q.get("path")); }
                else if("GET".equals(parts[0]) && "/api/docx".equals(endpoint)) { previewDocx(out,q.get("path")); }
                else if("POST".equals(parts[0]) && "/api/download-zip".equals(endpoint)) { downloadZip(out,in,headers); }
                else if("POST".equals(parts[0]) && "/api/mkdir".equals(endpoint)) { File dir=child(q); if(dir.exists()||!dir.mkdir()) throw new IOException("Не удалось создать папку (возможно, имя занято)"); ok(out); }
                else if("POST".equals(parts[0]) && "/api/rename".equals(endpoint)) { File from=resolve(q.getOrDefault("path","/")); if(from.equals(root)||!from.exists()) throw new IOException("Нельзя переименовать этот объект"); File target=safeChild(from.getParentFile(), q.get("name")); if(target.exists()||!from.renameTo(target)) throw new IOException("Не удалось переименовать (возможно, имя занято)"); ok(out); }
                else if("POST".equals(parts[0]) && "/api/delete".equals(endpoint)) { File target=resolve(q.getOrDefault("path","/")); if(target.equals(root)||!target.exists()) throw new IOException("Нельзя удалить этот объект"); deleteTree(target); ok(out); }
                else if("POST".equals(parts[0]) && "/api/upload".equals(endpoint)) { upload(out,in,headers,q); }
                else sendText(out,404,"Not found");
            } catch(Exception e) { sendText(out,400,e.getMessage()==null ? "Ошибка операции" : e.getMessage()); }
        } catch(Exception e) { android.util.Log.w("WiFiFiles","request failed: "+e.getMessage()); }
    }
    private Map<String,String> parseQuery(String query) throws IOException { Map<String,String> m=new HashMap<>(); if(query!=null) for(String pair:query.split("&")) { int i=pair.indexOf('='); String a=i<0?pair:pair.substring(0,i); String b=i<0?"":pair.substring(i+1); m.put(URLDecoder.decode(a,"UTF-8"),URLDecoder.decode(b,"UTF-8")); } return m; }
    private File resolve(String path) throws IOException {
        if(path==null || path.indexOf('\0')>=0) throw new IOException("Недопустимый путь");
        while(path.startsWith("/")) path=path.substring(1);
        File f=new File(root,path).getCanonicalFile(); String base=root.getPath(); if(!f.equals(root) && !f.getPath().startsWith(base+File.separator)) throw new IOException("Путь вне доступного хранилища"); return f;
    }
    private File safeChild(File parent,String name) throws IOException {
        if(parent==null || !parent.isDirectory() || name==null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0')>=0 || name.length()>200) throw new IOException("Недопустимое имя");
        File child=new File(parent,name); File canonical=child.getCanonicalFile(); if(!canonical.getParentFile().equals(parent.getCanonicalFile())) throw new IOException("Недопустимый путь"); return child;
    }
    private File child(Map<String,String> q) throws IOException { return safeChild(resolve(q.getOrDefault("path","/")),q.get("name")); }
    private String relative(File f) throws IOException { String p=f.getCanonicalPath(); String base=root.getCanonicalPath(); if(p.equals(base)) return "/"; if(!p.startsWith(base+"/")) throw new IOException("Путь вне доступного хранилища"); return p.substring(base.length()); }
    private void list(OutputStream out,String path) throws Exception {
        File d=resolve(path); if(!d.isDirectory()) throw new IOException("Папка недоступна"); File[] files=d.listFiles(); if(files==null) throw new IOException("Нет доступа к папке");
        Arrays.sort(files,(a,b)-> { if(a.isDirectory()!=b.isDirectory()) return a.isDirectory()?-1:1; return a.getName().compareToIgnoreCase(b.getName()); });
        JSONArray arr=new JSONArray(); for(File f:files) try { String rp=relative(f); JSONObject x=new JSONObject(); x.put("name",f.getName()); x.put("path",rp); x.put("dir",f.isDirectory()); x.put("size",f.length()); x.put("modified",f.lastModified()); arr.put(x); } catch(IOException ignored) {}
        JSONObject data=new JSONObject(); data.put("path",relative(d)); data.put("items",arr); send(out,200,"application/json; charset=utf-8",data.toString().getBytes(StandardCharsets.UTF_8),null);
    }
    private void download(OutputStream out,String path) throws Exception {
        File f=resolve(path); if(!f.isFile()||!f.canRead()) throw new IOException("Файл недоступен");
        try(InputStream file=new BufferedInputStream(new FileInputStream(f))) { String name=URLEncoder.encode(f.getName(),"UTF-8").replace("+","%20"); header(out,200,"application/octet-stream",f.length(),"Content-Disposition: attachment; filename*=UTF-8''"+name+"\r\n"); byte[] b=new byte[65536]; int n; while((n=file.read(b))!=-1) out.write(b,0,n); out.flush(); }
    }
    private static String extension(File f) { String n=f.getName().toLowerCase(Locale.ROOT); int dot=n.lastIndexOf('.'); return dot<0?"":n.substring(dot+1); }
    private static String mediaMime(File f) {
        switch(extension(f)) {
            case "jpg": case "jpeg": return "image/jpeg"; case "png": return "image/png"; case "gif": return "image/gif"; case "webp": return "image/webp"; case "bmp": return "image/bmp"; case "avif": return "image/avif";
            case "mp4": case "m4v": return "video/mp4"; case "webm": return "video/webm"; case "ogv": return "video/ogg"; case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg"; case "m4a": return "audio/mp4"; case "wav": return "audio/wav"; case "ogg": case "opus": return "audio/ogg"; case "flac": return "audio/flac";
            case "pdf": return "application/pdf"; default: return null;
        }
    }
    private File readable(String path) throws IOException { File f=resolve(path); if(!f.isFile() || !f.canRead()) throw new IOException("Файл недоступен"); return f; }
    private void previewTicket(OutputStream out,String path) throws Exception {
        File f=readable(path); String mime=mediaMime(f); if(mime==null) throw new IOException("Формат не поддерживается");
        long now=System.currentTimeMillis(); for(Map.Entry<String,PreviewTicket> e:previews.entrySet()) if(e.getValue().expires<now) previews.remove(e.getKey());
        if(previews.size()>=128) throw new IOException("Слишком много окон просмотра. Перезапустите сервер.");
        byte[] random=new byte[16]; new SecureRandom().nextBytes(random); StringBuilder id=new StringBuilder(); for(byte b:random) id.append(String.format(Locale.US,"%02x",b & 255)); previews.put(id.toString(),new PreviewTicket(relative(f),mime));
        JSONObject json=new JSONObject(); json.put("url","/preview?ticket="+id); send(out,200,"application/json; charset=utf-8",json.toString().getBytes(StandardCharsets.UTF_8),null);
    }
    private void streamPreview(OutputStream out,String token,String range) throws Exception {
        PreviewTicket ticket=token==null?null:previews.get(token); if(ticket==null || ticket.expires<System.currentTimeMillis()) { sendText(out,403,"Срок просмотра истёк. Откройте файл снова."); return; }
        File f=readable(ticket.path); if(!ticket.mime.equals(mediaMime(f))) throw new IOException("Файл изменился");
        long total=f.length(),start=0,end=total-1; boolean partial=false;
        if(range!=null) {
            try {
                if(!range.startsWith("bytes=")||range.indexOf(',')>=0||total==0) throw new Exception(); String spec=range.substring(6); int dash=spec.indexOf('-'); if(dash<0) throw new Exception(); String a=spec.substring(0,dash).trim(),b=spec.substring(dash+1).trim();
                if(a.isEmpty()) { long suffix=Long.parseLong(b); if(suffix<=0) throw new Exception(); start=Math.max(0,total-suffix); } else start=Long.parseLong(a);
                if(!b.isEmpty()&&!a.isEmpty()) end=Math.min(end,Long.parseLong(b)); if(start<0||start>=total||end<start) throw new Exception(); partial=true;
            } catch(Exception e) { out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"+total+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush(); return; }
        }
        long count=total==0?0:end-start+1; String h="HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\nContent-Type: "+ticket.mime+"\r\nContent-Length: "+count+"\r\nAccept-Ranges: bytes\r\n"+(partial?"Content-Range: bytes "+start+"-"+end+"/"+total+"\r\n":"")+"Content-Disposition: inline\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: SAMEORIGIN\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n";
        try(RandomAccessFile file=new RandomAccessFile(f,"r")) { file.seek(start); out.write(h.getBytes(StandardCharsets.US_ASCII)); byte[] buf=new byte[65536]; long remaining=count; while(remaining>0) { int n=file.read(buf,0,(int)Math.min(buf.length,remaining)); if(n<0) break; out.write(buf,0,n); remaining-=n; } out.flush(); }
    }
    private void previewText(OutputStream out,String path) throws Exception {
        File f=readable(path); String ext=extension(f); if(!Arrays.asList("txt","md","csv","log","json","xml","html","htm","css","js").contains(ext)) throw new IOException("Формат не поддерживается");
        if(f.length()>5L*1024*1024) throw new IOException("Файл больше 5 МБ — скачайте его для просмотра");
        try(InputStream in=new FileInputStream(f); ByteArrayOutputStream b=new ByteArrayOutputStream()) { byte[] buf=new byte[8192]; int n,total=0; while((n=in.read(buf))!=-1) { total+=n; if(total>5*1024*1024) throw new IOException("Файл слишком большой"); b.write(buf,0,n); } send(out,200,"text/plain; charset=utf-8",b.toByteArray(),null); }
    }
    private void previewDocx(OutputStream out,String path) throws Exception {
        File f=readable(path); if(!"docx".equals(extension(f))) throw new IOException("Нужен файл DOCX");
        StringBuilder text=new StringBuilder(); boolean truncated=false;
        try(ZipFile zip=new ZipFile(f)) {
            ZipEntry entry=zip.getEntry("word/document.xml"); if(entry==null || entry.getSize()<0 || entry.getSize()>8L*1024*1024) throw new IOException("Документ слишком большой или повреждён");
            try(InputStream in=new BoundedInputStream(zip.getInputStream(entry),8L*1024*1024)) {
                XmlPullParser parser=Xml.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true); parser.setInput(in,"UTF-8"); boolean inText=false; int type;
                while((type=parser.nextToken())!=XmlPullParser.END_DOCUMENT) {
                    if(type==XmlPullParser.DOCDECL) throw new IOException("Документ с DTD не поддерживается");
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
        File folder=resolve(path); if (!folder.isDirectory()) throw new IOException("Папка недоступна");
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
    private void downloadZip(OutputStream out, InputStream in, Map<String,String> headers) throws Exception {
        int length; try { length=Integer.parseInt(headers.getOrDefault("content-length","-1")); } catch(Exception e) { length=-1; }
        if(length<2 || length>1024*1024) throw new IOException("Некорректный список файлов");
        byte[] body=new byte[length]; int n=0; while(n<length) { int r=in.read(body,n,length-n); if(r<0) throw new IOException("Передача прервана"); n+=r; }
        JSONArray selected=new JSONObject(new String(body,StandardCharsets.UTF_8)).getJSONArray("paths"); if(selected.length()<1 || selected.length()>500) throw new IOException("Можно выбрать от 1 до 500 файлов");
        ArrayList<File> files=new ArrayList<>(); Set<String> names=new HashSet<>();
        for(int i=0;i<selected.length();i++) { File f=resolve(selected.getString(i)); if(!f.isFile() || !f.canRead()) throw new IOException("Выберите только доступные файлы"); if(!names.add(f.getName())) throw new IOException("У выбранных файлов совпадают имена"); files.add(f); }
        String response="HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nTransfer-Encoding: chunked\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Disposition: attachment; filename=files.zip\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        ChunkedOutput chunked=new ChunkedOutput(out); ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(chunked,65536),StandardCharsets.UTF_8); zip.setLevel(1);
        byte[] buffer=new byte[65536]; for(File f:files) { try(InputStream file=new BufferedInputStream(new FileInputStream(f))) { ZipEntry entry=new ZipEntry(f.getName()); entry.setTime(f.lastModified()); zip.putNextEntry(entry); int nread; while((nread=file.read(buffer))!=-1) zip.write(buffer,0,nread); zip.closeEntry(); } }
        zip.finish(); zip.flush(); chunked.finish();
    }
    private static final class ChunkedOutput extends OutputStream {
        private final OutputStream out; ChunkedOutput(OutputStream out) { this.out=out; }
        @Override public void write(int b) throws IOException { byte[] one={(byte)b}; write(one,0,1); }
        @Override public void write(byte[] b,int off,int len) throws IOException { if(len==0)return; out.write((Integer.toHexString(len)+"\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(b,off,len); out.write('\r'); out.write('\n'); }
        void finish() throws IOException { out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush(); }
    }
    private void deleteTree(File f) throws IOException {
        if(!f.getCanonicalFile().equals(f.getAbsoluteFile())) { if(!f.delete()) throw new IOException("Не удалось удалить ссылку: "+f.getName()); return; }
        if(f.isDirectory()) { File[] children=f.listFiles(); if(children==null) throw new IOException("Нет доступа к папке: "+f.getName()); for(File c:children) deleteTree(c); }
        if(!f.delete()) throw new IOException("Не удалось удалить: "+f.getName());
    }
    private void upload(OutputStream out,InputStream in,Map<String,String> headers,Map<String,String> q) throws Exception {
        File target=child(q); if(target.exists()) throw new IOException("Файл с таким именем уже существует");
        long length; try { length=Long.parseLong(headers.getOrDefault("content-length","-1")); } catch(Exception e) { length=-1; }
        if(length<0 || length>20L*1024*1024*1024) throw new IOException("Некорректный размер файла");
        File tmp=File.createTempFile(".wifi-upload-", ".part",target.getParentFile()); boolean ok=false;
        try { try(OutputStream file=new BufferedOutputStream(new FileOutputStream(tmp))) { byte[] b=new byte[65536]; long remaining=length; while(remaining>0) { int n=in.read(b,0,(int)Math.min(b.length,remaining)); if(n<0) throw new IOException("Передача прервана"); file.write(b,0,n); remaining-=n; } } if(target.exists() || !tmp.renameTo(target)) throw new IOException("Не удалось сохранить файл"); ok=true; }
        finally { if(!ok) tmp.delete(); } ok(out);
    }
    private void ok(OutputStream out) throws IOException { send(out,200,"application/json; charset=utf-8","{\"ok\":true}".getBytes(StandardCharsets.UTF_8),null); }
    private void sendText(OutputStream out,int status,String msg) throws IOException { send(out,status,"text/plain; charset=utf-8",msg.getBytes(StandardCharsets.UTF_8),null); }
    private void send(OutputStream out,int status,String type,byte[] data,String extra) throws IOException { header(out,status,type,data.length,extra); out.write(data); out.flush(); }
    private void header(OutputStream out,int status,String type,long length,String extra) throws IOException {
        String reason=status==200?"OK":status==403?"Forbidden":status==404?"Not Found":"Bad Request";
        String headers="HTTP/1.1 "+status+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n"+(extra==null?"":extra)+"\r\n"; out.write(headers.getBytes(StandardCharsets.US_ASCII));
    }
}
