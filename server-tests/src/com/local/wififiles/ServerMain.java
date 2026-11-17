package com.local.wififiles;

import android.content.Context;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.nio.file.Files;

/** Запуск настоящего FileServer на JVM: демо-сервер для браузерных тестов. */
public final class ServerMain {
    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        String key = a[1];
        boolean tls = a.length > 2 && "tls".equals(a[2]);
        String assets = System.getProperty("pb.assets", "app/src/main/assets");
        SSLContext context = null;
        if (tls) {
            File dir = Files.createTempDirectory("pocketbridge-tls").toFile();
            context = SelfSignedCertificate.createContext(dir);
        }
        FileServer server = new FileServer(new Context(new File(assets)), root, key, context);
        int port = server.start();
        System.out.println("PORT=" + port + " ROOT=" + root + " KEY=" + key + " TLS=" + tls);
        System.out.println("READY");
        Thread.currentThread().join();
    }
}
