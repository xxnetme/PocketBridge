package com.local.wififiles;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** In-memory /thumb credentials. Issuance never evicts a live token. */
final class ThumbnailSessions {
    static final long TTL_MS = TimeUnit.HOURS.toMillis(2);
    private static final int LIMIT = 16;
    private final Map<String, Long> expiresAt = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private boolean closed;

    ThumbnailSessions() { this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime())); }
    ThumbnailSessions(LongSupplier clock) { this.clock = clock; }

    synchronized String issue(String previous) throws FileServer.ApiException {
        if (closed) throw ManagedFiles.error(503, "Доступ остановлен");
        long now = clock.getAsLong();
        expiresAt.values().removeIf(expiry -> expiry <= now);
        if (expiresAt.containsKey(previous)) {
            expiresAt.put(previous, now + TTL_MS);
            return previous;
        }
        if (expiresAt.size() >= LIMIT) {
            long next = Long.MAX_VALUE;
            for (long expiry : expiresAt.values()) next = Math.min(next, expiry);
            long seconds = (next - now + 999) / 1000;
            throw new FileServer.ApiException(429, "Лимит сессий миниатюр. Попробуйте позже.",
                    "Retry-After: " + seconds + "\r\n");
        }
        byte[] bytes = new byte[16];
        String token;
        do {
            random.nextBytes(bytes);
            token = ManagedFiles.hex(bytes);
        } while (expiresAt.containsKey(token));
        expiresAt.put(token, now + TTL_MS);
        return token;
    }

    synchronized void require(String token) throws FileServer.ApiException {
        Long expiry = expiresAt.get(token);
        if (expiry == null || expiry <= clock.getAsLong()) {
            expiresAt.remove(token);
            throw ManagedFiles.error(403, "Сессия истекла. Обновите страницу.");
        }
    }

    synchronized void close() {
        closed = true;
        expiresAt.clear();
    }
}
