package com.lodygames.rpgquest.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #57 — cycle de vie : activation et fin aux bonnes dates, brouillon, annulation, archive. */
class EventLifecycleTest {

    private static final Instant START = Instant.parse("2026-11-07T18:00:00Z");
    private static final Instant END = Instant.parse("2026-11-09T22:00:00Z");

    private static StatPeriod event(StatPeriod.State state) {
        return new StatPeriod("e", StatScope.EVENT, "e", START, END, List.of(StatMetric.ENTITY_KILL), state,
                EventRules.DEFAULT);
    }

    @Test
    void followsTheClockAtTheExactBounds() {
        StatPeriod planned = event(StatPeriod.State.PLANNED);

        assertEquals(EventLifecycle.SCHEDULED, EventLifecycle.of(planned, START.minusSeconds(1), false));
        assertEquals(EventLifecycle.ACTIVE, EventLifecycle.of(planned, START, false), "début inclus");
        assertEquals(EventLifecycle.ACTIVE, EventLifecycle.of(planned, END.minusMillis(1), false));
        assertEquals(EventLifecycle.ENDED, EventLifecycle.of(planned, END, false), "fin exclue");
        assertEquals(EventLifecycle.ARCHIVED, EventLifecycle.of(planned, END, true));
    }

    @Test
    void administratorDecisionsWinOverTheClock() {
        assertEquals(EventLifecycle.DRAFT, EventLifecycle.of(event(StatPeriod.State.DRAFT), START, false));
        assertEquals(EventLifecycle.CANCELLED, EventLifecycle.of(event(StatPeriod.State.CANCELLED), START, false));
        assertEquals(EventLifecycle.CANCELLED, EventLifecycle.of(event(StatPeriod.State.CANCELLED), END, true),
                "annulé après coup : l'archive n'est plus montrée");
    }

    @Test
    void onlyActiveEndedAndArchivedHaveABoard() {
        assertTrue(EventLifecycle.ACTIVE.hasBoard());
        assertTrue(EventLifecycle.ENDED.hasBoard());
        assertTrue(EventLifecycle.ARCHIVED.hasBoard());
        assertFalse(EventLifecycle.SCHEDULED.hasBoard());
        assertFalse(EventLifecycle.DRAFT.hasBoard());
        assertFalse(EventLifecycle.CANCELLED.hasBoard());
    }
}
