package com.lodygames.rpgquest.mob.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommunityBossScheduleTest {

    private static final long NOW = 10_000_000L;
    private static final long INTERVAL = 15 * 60_000L;

    @Test
    void neverArmedSchedulerWaitsAFullInterval() {
        assertEquals(NOW + INTERVAL, CommunityBossSchedule.armOnStart(NOW, null, INTERVAL));
    }

    @Test
    void missedDeadlinesGiveOneAttemptAfterTheGraceNeverABurst() {
        long armed = CommunityBossSchedule.armOnStart(NOW, NOW - 50 * INTERVAL, INTERVAL);
        assertEquals(NOW + CommunityBossSchedule.STARTUP_GRACE_MILLIS, armed);
    }

    @Test
    void graceNeverExceedsAShortInterval() {
        assertEquals(NOW + 60_000L, CommunityBossSchedule.armOnStart(NOW, NOW - 1, 60_000L));
    }

    @Test
    void futureDeadlineIsKeptButCappedToOneInterval() {
        assertEquals(NOW + 1000, CommunityBossSchedule.armOnStart(NOW, NOW + 1000, INTERVAL));
        assertEquals(NOW + INTERVAL, CommunityBossSchedule.armOnStart(NOW, NOW + 10 * INTERVAL, INTERVAL));
    }

    @Test
    void reenablingRestartsAFullIntervalAndShorteningNeverPushesFurther() {
        assertEquals(NOW + INTERVAL, CommunityBossSchedule.rearm(NOW, NOW + 10, false, INTERVAL));
        assertEquals(NOW + 60_000L, CommunityBossSchedule.rearm(NOW, NOW + INTERVAL, true, 60_000L));
        assertEquals(NOW + 10, CommunityBossSchedule.rearm(NOW, NOW + 10, true, INTERVAL));
    }

    @Test
    void dueOnlyAtOrAfterTheDeadline() {
        assertFalse(CommunityBossSchedule.isDue(NOW, null));
        assertFalse(CommunityBossSchedule.isDue(NOW - 1, NOW));
        assertTrue(CommunityBossSchedule.isDue(NOW, NOW));
        assertEquals(NOW + INTERVAL, CommunityBossSchedule.afterAttempt(NOW, INTERVAL));
    }
}
