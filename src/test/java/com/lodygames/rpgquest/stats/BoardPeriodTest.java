package com.lodygames.rpgquest.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Issue #734 — fenêtres de calendrier dans le fuseau de la communauté : bornes exactes, clés
 * stables, changement d'heure (25/10/2026, journée de 25 h), semaine ISO et passage d'année.
 */
class BoardPeriodTest {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    private static BoardPeriod.Window window(String key, String start, String end) {
        return new BoardPeriod.Window(key, Instant.parse(start), Instant.parse(end));
    }

    @Test
    void aDayStartsAtLocalMidnightAndTheDstDayLasts25Hours() {
        assertEquals(window("2026-10-23", "2026-10-22T22:00:00Z", "2026-10-23T22:00:00Z"),
                BoardPeriod.DAILY.current(Instant.parse("2026-10-23T10:00:00Z"), BRUSSELS));
        assertEquals(window("2026-10-25", "2026-10-24T22:00:00Z", "2026-10-25T23:00:00Z"),
                BoardPeriod.DAILY.current(Instant.parse("2026-10-25T12:00:00Z"), BRUSSELS));
        assertEquals(window("2026-10-22", "2026-10-21T22:00:00Z", "2026-10-22T22:00:00Z"),
                BoardPeriod.DAILY.previous(Instant.parse("2026-10-23T10:00:00Z"), BRUSSELS));
    }

    @Test
    void justBeforeLocalMidnightIsStillTheSameDay() {
        assertEquals("2026-10-23", BoardPeriod.DAILY.current(Instant.parse("2026-10-23T21:59:59Z"), BRUSSELS).key());
        assertEquals("2026-10-24", BoardPeriod.DAILY.current(Instant.parse("2026-10-23T22:00:00Z"), BRUSSELS).key());
    }

    @Test
    void aWeekRunsFromMondayToMondayWithItsIsoKey() {
        assertEquals(window("2026-W43", "2026-10-18T22:00:00Z", "2026-10-25T23:00:00Z"),
                BoardPeriod.WEEKLY.current(Instant.parse("2026-10-23T10:00:00Z"), BRUSSELS));
        assertEquals("2026-W42", BoardPeriod.WEEKLY.previous(Instant.parse("2026-10-23T10:00:00Z"), BRUSSELS).key());
        assertEquals("2026-W53", BoardPeriod.WEEKLY.current(Instant.parse("2027-01-01T12:00:00Z"), BRUSSELS).key(),
                "le 1er janvier 2027 appartient à la semaine 53 de 2026");
    }

    @Test
    void aMonthIsTheCivilMonth() {
        assertEquals(window("2026-10", "2026-09-30T22:00:00Z", "2026-10-31T23:00:00Z"),
                BoardPeriod.MONTHLY.current(Instant.parse("2026-10-23T10:00:00Z"), BRUSSELS));
        assertEquals(window("2026-12", "2026-11-30T23:00:00Z", "2026-12-31T23:00:00Z"),
                BoardPeriod.MONTHLY.previous(Instant.parse("2027-01-05T10:00:00Z"), BRUSSELS));
    }

    @Test
    void theArchiveIdOfAWindowIsStable() {
        StatBoard board = new StatBoard("zombies_week", "Zombies", "combat", null, StatMetric.ENTITY_KILL,
                java.util.Map.of(), java.util.Map.of(), BoardPeriod.WEEKLY, null, null, 10, false);
        assertEquals("board_zombies_week_2026w43",
                board.periodFor(BoardPeriod.WEEKLY.current(Instant.parse("2026-10-23T10:00:00Z"), BRUSSELS)).id());
    }
}
