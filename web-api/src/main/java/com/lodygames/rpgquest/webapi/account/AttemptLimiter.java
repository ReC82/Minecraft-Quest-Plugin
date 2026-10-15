package com.lodygames.rpgquest.webapi.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Compte les échecs par clé (IP, identifiant, UUID) dans une fenêtre fixe (issue #53) : au-delà de
 * {@code maxFailures}, la clé est bloquée jusqu'à la fin de la fenêtre. Un succès n'efface rien —
 * sinon un attaquant alternerait ses essais avec son propre compte.
 *
 * <p>En mémoire seulement : un redémarrage du service remet les compteurs à zéro, ce qui reste
 * borné par la limite de fréquence nginx et le coût du hachage.</p>
 */
public final class AttemptLimiter {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public AttemptLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public boolean isBlocked(String key) {
        Window current = windows.get(key);
        if (current == null) {
            return false;
        }
        synchronized (current) {
            return !expired(current) && current.failures >= maxFailures;
        }
    }

    public void recordFailure(String key) {
        if (windows.size() >= MAX_TRACKED_KEYS) {
            windows.entrySet().removeIf(entry -> expired(entry.getValue()));
        }
        Window current = windows.computeIfAbsent(key, ignored -> new Window(clock.instant()));
        synchronized (current) {
            if (expired(current)) {
                current.start = clock.instant();
                current.failures = 0;
            }
            current.failures++;
        }
    }

    private boolean expired(Window current) {
        return !clock.instant().isBefore(current.start.plus(window));
    }

    private static final class Window {
        Instant start;
        int failures;

        Window(Instant start) {
            this.start = start;
        }
    }
}
