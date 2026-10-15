package com.lodygames.rpgquest.web.link;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-abus côté serveur Minecraft pour {@code /lien} (issue #53) : une seule preuve en vol par
 * joueur, et un délai minimal entre deux essais. Le portail applique en plus sa propre limite
 * d'échecs par UUID : celle-ci évite seulement de le solliciter pour rien (double-clic, spam).
 */
public final class LinkAttemptGuard {

    private final Duration cooldown;
    private final Clock clock;
    private final Map<UUID, Instant> lastAttempt = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();

    public LinkAttemptGuard(Duration cooldown, Clock clock) {
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** {@code true} si l'essai peut partir ; il est alors marqué « en vol » jusqu'à {@link #finish}. */
    public boolean tryStart(UUID player) {
        Instant now = clock.instant();
        Instant previous = lastAttempt.get(player);
        if (previous != null && now.isBefore(previous.plus(cooldown))) {
            return false;
        }
        if (inFlight.putIfAbsent(player, Boolean.TRUE) != null) {
            return false;
        }
        lastAttempt.put(player, now);
        return true;
    }

    public void finish(UUID player) {
        inFlight.remove(player);
    }
}
