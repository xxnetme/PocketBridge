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
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

/** Держит HTTPS-сервер живым, пока приложение свёрнуто или экран заблокирован. */
public final class ServerService extends Service {
    static final String ACTION_START = "com.local.wififiles.action.START";
    static final String ACTION_STOP = "com.local.wififiles.action.STOP";
    static final String ACTION_TILE_START = "com.local.wififiles.action.TILE_START";
    static final String ACTION_RELOAD = "com.local.wififiles.action.RELOAD";
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
    // Lifecycle, startup generation and DNS-SD registration are main-thread owned.
    private static ServerService current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService tlsWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pocketbridge-tls-identity");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> preparation;
    private long startGeneration;
    private volatile FileServer server;
    private List<InetAddress> certifiedAddresses = Collections.emptyList();
    private LanDiscovery discovery;
    private String discoveryName;
    private String key = "";
    private int port;
    private String error = "";
    private ScheduledExecutorService watchdog;
    private WifiManager.WifiLock wifiLock;

    final class LocalBinder extends Binder {
        ServerService service() { return ServerService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    static ServerService current() { return current; }
    static ServerService active() { return current != null && current.isRunning() ? current : null; }
    boolean isStarting() { return preparation != null; }
    boolean isRunning() { return server != null; }
    boolean isReadOnly() { FileServer s = server; return s != null && s.isReadOnly(); }
    String discoveryName() { return discoveryName; }
    List<InetAddress> certifiedAddresses() { return certifiedAddresses; }

    static String newKey() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder b = new StringBuilder(16);
        for (byte x : bytes) b.append(String.format(Locale.US, "%02x", x & 0xff));
        return b.toString();
    }
    String accessKey() { return key; }
    int port() { return port; }
    String lastError() { return error; }
    int activeConnections() { FileServer s = server; return s == null ? 0 : s.activeConnections(); }
    int activeUploads() { FileServer s = server; return s == null ? 0 : s.activeUploads(); }
    long uptimeMillis() { FileServer s = server; return s == null ? 0L : s.uptimeMillis(); }
    boolean isClientActive() { FileServer s = server; return s != null && s.isRecentlyActive(12_000L); }
    String clipboardText() { FileServer s = server; return s == null ? "" : s.clipboardText(); }
    long clipboardUpdatedAt() { FileServer s = server; return s == null ? 0L : s.clipboardUpdatedAt(); }
    String clipboardSource() { FileServer s = server; return s == null ? "" : s.clipboardSource(); }
    void setPhoneClipboard(String text) { FileServer s = server; if (s != null) s.updateClipboard(text, "phone"); }

    void addListener(Runnable r) { listeners.add(r); }
    void removeListener(Runnable r) { listeners.remove(r); }
    private void notifyListeners() { for (Runnable r : listeners) try { r.run(); } catch (Throwable ignored) {} }

    private int safePrefInt(String k, int def) {
        try { return getSharedPreferences(PREFS, MODE_PRIVATE).getInt(k, def); }
        catch (ClassCastException e) { return def; }
    }

    private String tr(String ru, String en) { return BridgeOptions.english(this) ? en : ru; }

    void stopFromUi() { shutdown(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { shutdown(); return START_NOT_STICKY; }
        boolean tileStart = ACTION_TILE_START.equals(action);
        boolean reload = ACTION_RELOAD.equals(action);
        if (reload && server == null && !isStarting()) { stopSelf(); return START_NOT_STICKY; }
        String requested = reload ? key : tileStart ? (current == this ? key : newKey())
                : intent == null ? null : intent.getStringExtra(EXTRA_KEY);
        if (requested == null || requested.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        if (!reload && current == this && requested.equals(key)) {
            String title = server != null ? runningTitle() : startingTitle();
            if (!startInForeground(title)) shutdown();
            else publishState();
            return START_NOT_STICKY;
        }
        closeServer();
        key = requested;
        error = "";
        if (!startInForeground(startingTitle())) {
            failStart(tileStart);
            return START_NOT_STICKY;
        }
        if (readyRoot(tileStart) == null) return START_NOT_STICKY;
        File identityDir = getFilesDir();
        long generation = startGeneration;
        current = this;
        preparation = tlsWorker.submit(() -> {
            try {
                List<InetAddress> addresses = LanAddresses.advertised();
                SSLContext ssl = SelfSignedCertificate.createContext(identityDir, addresses);
                main.post(() -> finishStart(generation, tileStart, addresses, ssl));
            } catch (Exception failure) {
                main.post(() -> { if (generation == startGeneration) startFailed(tileStart, failure); });
            }
        });
        publishState();
        return START_NOT_STICKY;
    }

    private String startingTitle() { return tr("Запуск доступа…", "Starting access…"); }
    private String runningTitle() { return tr("PocketBridge активен · :" + port, "PocketBridge active · :" + port); }

    private File readyRoot(boolean tileStart) {
        if (!BridgeOptions.hasStorageAccess(this)) {
            error = tr("Сначала разрешите доступ к файлам", "Grant storage access first");
        } else {
            try { return BridgeOptions.sharedFolder(this); }
            catch (Exception unavailable) {
                error = tr("Общая папка недоступна. Выберите другую в настройках.",
                        "Shared folder unavailable. Choose another folder in Settings.");
            }
        }
        failStart(tileStart);
        return null;
    }

    private void finishStart(long generation, boolean tileStart, List<InetAddress> addresses, SSLContext ssl) {
        // A stopped or replaced request may still finish native RSA work. Never open its listener.
        if (generation != startGeneration) return;
        preparation = null;
        File root = readyRoot(tileStart);
        if (root == null) return;
        try {
            int preferredPort = safePrefInt(PREF_PORT, DEFAULT_PORT);
            server = new FileServer(this, root, key, ssl, getCacheDir(), preferredPort, BridgeOptions.readOnly(this));
            server.setClipboardListener(this::notifyListeners);
            port = server.start();
            certifiedAddresses = addresses;
            acquireWifiLock();
            startWatchdog();
        } catch (Exception failure) {
            startFailed(tileStart, failure);
            return;
        }
        if (!startInForeground(runningTitle())) {
            failStart(tileStart);
            return;
        }
        updateDiscovery();
        publishState();
    }

    private void startFailed(boolean tileStart, Exception failure) {
        Log.w("WiFiFiles", "Server startup failed", failure);
        error = failure.getMessage() == null ? tr("Не удалось запустить сервер", "Could not start server") : failure.getMessage();
        failStart(tileStart);
    }

    private void failStart(boolean tileStart) {
        if (tileStart) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        shutdown();
    }

    /** Переключение объявления не сбрасывает существующие клиентские сессии. */
    void updateDiscovery() {
        stopDiscovery();
        if (server != null && BridgeOptions.discovery(this)) {
            try {
                discovery = new LanDiscovery(this, name -> {
                    discoveryName = name;
                    notifyListeners();
                });
                discovery.start(port);
            } catch (RuntimeException unavailable) {
                stopDiscovery();
                discoveryName = "";
            }
        }
        notifyListeners();
    }

    private void stopDiscovery() {
        if (discovery != null) discovery.stop();
        discovery = null;
        discoveryName = null;
    }

    private void publishState() {
        notifyListeners();
        BridgeTileService.refresh(this);
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
        int minutes = safePrefInt(PREF_AUTOSTOP, DEFAULT_AUTOSTOP);
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
                main.post(() -> {
                    if (current != server || current.idleMillis() <= limit || current.activeConnections() != 0) return;
                    error = tr("Остановлен автоматически: " + minutes + " мин без подключений",
                            "Stopped automatically after " + minutes + " min idle");
                    shutdown();
                });
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    private void closeServer() {
        startGeneration++;
        if (preparation != null) {
            preparation.cancel(true);
            preparation = null;
        }
        stopWatchdog();
        stopDiscovery();
        FileServer old = server;
        server = null;
        certifiedAddresses = Collections.emptyList();
        if (current == this) current = null;
        if (old != null) old.stop();
        releaseWifiLock();
    }

    private void shutdown() {
        closeServer();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
        publishState();
    }

    @Override public void onDestroy() {
        closeServer();
        tlsWorker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        publishState();
        super.onDestroy();
    }

    private boolean startInForeground(String title) {
        try {
            Notification notification = buildNotification(title);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Exception e) {
            Log.e("WiFiFiles", "startForeground failed", e);
            error = tr("Android запретил фоновый запуск — откройте приложение и нажмите «Запустить»",
                    "Foreground start blocked — keep PocketBridge open and tap Start");
            return false;
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
