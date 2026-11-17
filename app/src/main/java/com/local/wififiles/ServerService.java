package com.local.wififiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.net.wifi.WifiManager;
import android.os.IBinder;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

/** Держит HTTPS-сервер живым, пока приложение свёрнуто или экран заблокирован. */
public final class ServerService extends Service {
    static final String ACTION_START = "com.local.wififiles.action.START";
    static final String ACTION_STOP = "com.local.wififiles.action.STOP";
    static final String EXTRA_KEY = "key";
    /** Настройки, которые читает и экран, и сервис. */
    static final String PREFS = "pb-settings";
    static final String PREF_PORT = "port";
    static final String PREF_AUTOSTOP = "autoStop";
    static final String PREF_KEEP_SCREEN = "keepScreen";
    static final int DEFAULT_PORT = 8765;
    static final int DEFAULT_AUTOSTOP = 30;
    private static final String CHANNEL_ID = "server";
    private static final int NOTIFICATION_ID = 1;

    private final LocalBinder binder = new LocalBinder();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private FileServer server;
    private String key = "";
    private int port;
    private String error = "";
    private ScheduledExecutorService watchdog;
    private WifiManager.WifiLock wifiLock;      // удерживает Wi-Fi активным во время передачи

    final class LocalBinder extends Binder {
        ServerService service() { return ServerService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    boolean isRunning() { return server != null; }
    String accessKey() { return key; }
    int port() { return port; }
    String lastError() { return error; }

    void addListener(Runnable r) { listeners.add(r); }
    void removeListener(Runnable r) { listeners.remove(r); }
    private void notifyListeners() { for (Runnable r : listeners) try { r.run(); } catch (Throwable ignored) {} }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { shutdown(); return START_NOT_STICKY; }
        String requested = intent == null ? null : intent.getStringExtra(EXTRA_KEY);
        if (requested == null || requested.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        if (server != null && requested.equals(key)) { notifyListeners(); return START_NOT_STICKY; }
        if (server != null) { server.stop(); server = null; }   // смена кода — перезапуск
        key = requested; error = "";
        startInForeground("Запуск доступа…");
        try {
            SSLContext ssl = SelfSignedCertificate.createContext(getFilesDir());
            int preferredPort = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_PORT, DEFAULT_PORT);
            server = new FileServer(this, Environment.getExternalStorageDirectory(), key, ssl, getCacheDir(), preferredPort);
            port = server.start();
            // Удерживаем Wi-Fi активным, чтобы передача не обрывалась при блокировке экрана
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pocketbridge-wifi");
                    wifiLock.acquire();
                }
            } catch (Exception ignored) {}
            startWatchdog();
        } catch (Exception e) {
            error = e.getMessage() == null ? "Не удалось запустить сервер" : e.getMessage();
            shutdown();
            notifyListeners();
            return START_NOT_STICKY;
        }
        startInForeground("Сервер работает");
        notifyListeners();
        return START_NOT_STICKY;
    }

    private void startWatchdog() {
        if (watchdog != null) return;
        int minutes = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_AUTOSTOP, DEFAULT_AUTOSTOP);
        if (minutes <= 0) return;                       // автостоп отключён
        long limit = minutes * 60_000L;
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "idle-watchdog"); t.setDaemon(true); return t; });
        watchdog.scheduleWithFixedDelay(() -> {
            FileServer current = server;
            if (current != null && current.idleMillis() > limit && current.activeConnections() == 0) {
                error = "Сервер остановлен автоматически: " + minutes + " мин без подключений";
                shutdown();
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    private void releaseWifiLock() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        wifiLock = null;
    }

    private void shutdown() {
        if (watchdog != null) { watchdog.shutdownNow(); watchdog = null; }
        if (server != null) { server.stop(); server = null; }
        releaseWifiLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyListeners();
    }

    @Override public void onDestroy() {
        if (watchdog != null) { watchdog.shutdownNow(); watchdog = null; }
        if (server != null) { server.stop(); server = null; }
        releaseWifiLock();
        notifyListeners();
        super.onDestroy();
    }

    private void startInForeground(String title) {
        Notification notification = buildNotification(title);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String title) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Файловый сервер", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Работа доступа к файлам по Wi-Fi");
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText("Код доступа: " + key)
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopIntent).build());
        if (Build.VERSION.SDK_INT >= 26) b.setChannelId(CHANNEL_ID);
        return b.build();
    }
}
