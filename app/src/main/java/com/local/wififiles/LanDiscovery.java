package com.local.wififiles;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.function.Consumer;

/** Bonjour/DNS-SD: публикуем HTTPS-сервис и порт, без путей к файлам и кода доступа. */
final class LanDiscovery implements NsdManager.RegistrationListener {
    static final String TYPE = "_https._tcp";
    static final String NAME = "PocketBridge";
    private final NsdManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> result;
    private boolean started;

    LanDiscovery(Context context, Consumer<String> result) {
        manager = context.getSystemService(NsdManager.class);
        this.result = result;
    }

    void start(int port) {
        if (manager == null) { result.accept(""); return; }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(NAME);
        info.setServiceType(TYPE);
        info.setPort(port);
        info.setAttribute("path", "/");
        try {
            started = true;
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, this);
        } catch (RuntimeException blocked) {
            started = false;
            result.accept("");
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        unregister();
    }

    private void unregister() {
        try { manager.unregisterService(this); }
        catch (RuntimeException ignored) { /* no longer registered */ }
    }

    @Override public void onServiceRegistered(NsdServiceInfo info) {
        main.post(() -> {
            if (started) result.accept(info.getServiceName());
            else unregister();
        });
    }

    @Override public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
        main.post(() -> {
            if (!started) return;
            started = false;
            result.accept("");
        });
    }

    @Override public void onServiceUnregistered(NsdServiceInfo info) {}
    @Override public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {}
}
