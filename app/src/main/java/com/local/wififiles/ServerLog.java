package com.local.wififiles;

import android.util.Log;

import java.util.function.BiConsumer;

/** Log only fixed event codes and exception types, never request data or exception text. */
final class ServerLog {
    private static final String TAG = "WiFiFiles";

    enum Event {
        ACCEPT_FAILED(Log::e),
        TLS_REJECTED(Log::d),
        REQUEST_FAILED(Log::w),
        SERVER_START_FAILED(Log::w),
        WIFI_LOCK_FAILED(Log::w),
        FOREGROUND_START_FAILED(Log::e);

        private final BiConsumer<String, String> write;

        Event(BiConsumer<String, String> write) { this.write = write; }
    }

    private ServerLog() {}

    static void failure(Event event, Exception failure) {
        // Do not pass the Throwable to Log: it prints messages, causes and suppressed exceptions.
        event.write.accept(TAG, event.name() + " [" + failure.getClass().getSimpleName() + "]");
    }
}
