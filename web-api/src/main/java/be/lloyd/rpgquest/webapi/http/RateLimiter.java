package be.lloyd.rpgquest.webapi.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Limite de fréquence par client (adresse IP), fenêtre fixe d'une minute —
 * même conception que {@code ProgressionService.ThrottleWindow} côté plugin.
 */
public final class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int maxRequestsPerWindow;
    private final LongSupplier clock;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequestsPerWindow) {
        this(maxRequestsPerWindow, System::currentTimeMillis);
    }

    RateLimiter(int maxRequestsPerWindow, LongSupplier clock) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.clock = clock;
    }

    /** {@code true} si la requête est autorisée (et comptée), {@code false} si la limite est dépassée. */
    public boolean tryAcquire(String clientKey) {
        long now = clock.getAsLong();
        Window window = windows.computeIfAbsent(clientKey, key -> new Window());
        synchronized (window) {
            if (now - window.startMillis >= WINDOW_MILLIS) {
                window.startMillis = now;
                window.count = 0;
            }
            if (window.count >= maxRequestsPerWindow) {
                return false;
            }
            window.count++;
            return true;
        }
    }

    private static final class Window {
        long startMillis;
        int count;
    }
}
