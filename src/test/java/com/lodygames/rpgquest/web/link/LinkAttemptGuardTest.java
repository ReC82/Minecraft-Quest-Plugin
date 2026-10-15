package com.lodygames.rpgquest.web.link;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LinkAttemptGuardTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void oneAttemptInFlightAndACooldownBetweenAttempts() {
        Instant start = Instant.parse("2026-10-15T18:00:00Z");
        LinkAttemptGuard guard = new LinkAttemptGuard(Duration.ofSeconds(5), Clock.fixed(start, ZoneOffset.UTC));

        assertTrue(guard.tryStart(PLAYER));
        assertFalse(guard.tryStart(PLAYER), "double envoi bloqué");
        assertTrue(guard.tryStart(UUID.randomUUID()), "les autres joueurs ne sont pas concernés");
        guard.finish(PLAYER);
        assertFalse(guard.tryStart(PLAYER), "délai minimal");

        LinkAttemptGuard later = new LinkAttemptGuard(Duration.ofSeconds(5),
                Clock.fixed(start.plusSeconds(6), ZoneOffset.UTC));
        assertTrue(later.tryStart(PLAYER));
    }
}
