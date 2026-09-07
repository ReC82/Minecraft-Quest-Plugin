package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Garde d'idempotence de l'agent (issue #51, phase 10). */
class ProcessedActionCacheTest {

    private static AgentActionOutcome outcome(String id) {
        return AgentActionOutcome.success(id, "v", "ok", java.util.Map.of());
    }

    @Test
    void unknownActionIsAbsent() {
        assertTrue(new ProcessedActionCache().lookup("nope", Instant.now()).isEmpty());
    }

    @Test
    void rememberedActionIsReturnedWithoutReExecution() {
        ProcessedActionCache cache = new ProcessedActionCache();
        Instant now = Instant.now();
        cache.remember("a1", outcome("a1"), now);
        assertTrue(cache.lookup("a1", now.plusSeconds(60)).isPresent());
        assertEquals("a1", cache.lookup("a1", now.plusSeconds(60)).get().actionId());
    }

    @Test
    void entryExpiresAfterTtl() {
        ProcessedActionCache cache = new ProcessedActionCache(Duration.ofMinutes(5), 100);
        Instant now = Instant.now();
        cache.remember("a1", outcome("a1"), now);
        assertTrue(cache.lookup("a1", now.plusSeconds(60)).isPresent());
        assertFalse(cache.lookup("a1", now.plusSeconds(600)).isPresent(), "au-delà du TTL -> oublié");
    }

    @Test
    void sizeIsCappedByEvictingOldest() {
        ProcessedActionCache cache = new ProcessedActionCache(Duration.ofHours(1), 16);
        Instant now = Instant.now();
        for (int i = 0; i < 40; i++) {
            cache.remember("a" + i, outcome("a" + i), now.plusSeconds(i));
        }
        assertTrue(cache.size() <= 16);
        assertTrue(cache.lookup("a39", now.plusSeconds(100)).isPresent(), "la plus récente est conservée");
        assertTrue(cache.lookup("a0", now.plusSeconds(100)).isEmpty(), "la plus ancienne est évincée");
    }
}
