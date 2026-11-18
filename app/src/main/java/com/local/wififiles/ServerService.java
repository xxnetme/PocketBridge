package com.local.wififiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

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
    private WifiManager.WifiLock wifiLock;

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

    private boolean isEn() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("ui_lang", "");
        if ("en".equals(saved) || "ru".equals(saved)) return "en".equals(saved);
        String sys = java.util.Locale.getDefault().getLanguage();
        return !("ru".equals(sys) || "uk".equals(sys) || "be".equals(sys) || "kk".equals(sys));
    }

    private String tr(String ru, String en) { return isEn() ? en : ru; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { shutdown(); return START_NOT_STICKY; }
        String requested = intent == null ? null : intent.getStringExtra(EXTRA_KEY);
        if (requested == null || requested.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        if (server != null && requested.equals(key)) { notifyListeners(); return START_NOT_STICKY; }
        if (server != null) { server.stop(); server = null; }   // смена кода — перезапуск
        stopWatchdog();
        releaseWifiLock();
        key = requested;
        error = "";
        startInForeground(tr("Запуск доступа…", "Starting access…"));
        try {
            SSLContext ssl = SelfSignedCertificate.createContext(getFilesDir());
            int preferredPort = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_PORT, DEFAULT_PORT);
            server = new FileServer(this, Environment.getExternalStorageDirectory(), key, ssl, getCacheDir(), preferredPort);
            port = server.start();
            acquireWifiLock();
            startWatchdog();
        } catch (Exception e) {
            error = e.getMessage() == null ? tr("Не удалось запустить сервер", "Could not start server") : e.getMessage();
            shutdown();
            return START_NOT_STICKY;
        }
        startInForeground(tr("PocketBridge активен · :" + port, "PocketBridge active · :" + port));
        notifyListeners();
        return START_NOT_STICKY;
    }

    /** Android 15+ (API 35+): корректная остановка при истечении лимита времени foregroundServiceType=dataSync. */
    @Override public void onTimeout(int startId, int fgsType) {
        error = tr("Фоновый лимит Android исчерпан — запустите доступ снова",
                "Android background limit reached — tap Start again");
        shutdown();
    }

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                int mode = Build.VERSION.SDK_INT >= 29
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "PocketBridge:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w("WiFiFiles", "wifiLock acquire failed: " + e.getMessage());
        }
    }

    private void releaseWifiLock() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        wifiLock = null;
    }

    private void stopWatchdog() {
        if (watchdog != null) { watchdog.shutdownNow(); watchdog = null; }
    }

    private void startWatchdog() {
        stopWatchdog();
        int minutes = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_AUTOSTOP, DEFAULT_AUTOSTOP);
        if (minutes <= 0) return;   // автостоп отключён
        long limit = minutes * 60_000L;
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idle-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(() -> {
            FileServer current = server;
            if (current != null && current.idleMillis() > limit && current.activeConnections() == 0) {
                error = tr("Остановлен автоматически: " + minutes + " мин без подключений",
                        "Stopped automatically after " + minutes + " min idle");
                shutdown();
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    private void shutdown() {
        stopWatchdog();
        if (server != null) { server.stop(); server = null; }
        releaseWifiLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyListeners();
    }

    @Override public void onDestroy() {
        stopWatchdog();
        if (server != null) { server.stop(); server = null; }
        releaseWifiLock();
        notifyListeners();
        super.onDestroy();
    }

    private void startInForeground(String title) {
        Notification notification = buildNotification(title);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String title) {
        ensureChannel();
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Публичная версия для экрана блокировки — без кода доступа
        Notification publicNotification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setColor(0xff0d9473)
                .setContentTitle(title)
                .setContentText(tr("Доступ к файлам по Wi-Fi активен", "Wi-Fi file access is active"))
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setColor(0xff0d9473)
                .setContentTitle(title)
                .setContentText(tr("Код доступа: ", "Access code: ") + key)
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotification)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        tr("Остановить", "Stop"), stopIntent).build())
                .build();
    }

    private void ensureChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    tr("Файловый сервер", "File server"), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(tr("Работа доступа к файлам по Wi-Fi", "Local Wi-Fi file server status"));
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(channel);
        }
    }
}
