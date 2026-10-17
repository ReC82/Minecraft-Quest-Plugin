package com.lodygames.rpgquest.quest.periodic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lodygames.rpgquest.quest.model.QuestPeriod;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** Issue #15 — frontières des périodes quotidiennes/hebdomadaires, fuseau et changements d'heure. */
class PeriodCalendarTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private final PeriodCalendar calendar = new PeriodCalendar(PARIS, LocalTime.of(4, 0), DayOfWeek.MONDAY);

    private static Instant paris(String localDateTime) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(localDateTime), PARIS).toInstant();
    }

    @Test
    void beforeTheResetTimeTheDayIsStillYesterday() {
        assertEquals("2026-10-16", calendar.periodKey(QuestPeriod.DAILY, paris("2026-10-17T03:59:59")));
        assertEquals("2026-10-17", calendar.periodKey(QuestPeriod.DAILY, paris("2026-10-17T04:00:00")));
        assertEquals("2026-10-17", calendar.periodKey(QuestPeriod.DAILY, paris("2026-10-17T23:59:59")));
    }

    @Test
    void theWeekStartsOnTheConfiguredDayAtTheResetTime() {
        // Lundi 12/10/2026 04:00 → semaine du 12 ; dimanche 18 23:00 → toujours la même.
        assertEquals("2026-10-12", calendar.periodKey(QuestPeriod.WEEKLY, paris("2026-10-12T04:00:00")));
        assertEquals("2026-10-12", calendar.periodKey(QuestPeriod.WEEKLY, paris("2026-10-18T23:00:00")));
        // Lundi 19 avant 04:00 : encore la semaine du 12 ; à 04:00, celle du 19.
        assertEquals("2026-10-12", calendar.periodKey(QuestPeriod.WEEKLY, paris("2026-10-19T03:59:00")));
        assertEquals("2026-10-19", calendar.periodKey(QuestPeriod.WEEKLY, paris("2026-10-19T04:00:00")));
    }

    @Test
    void nextResetIsTheFollowingBoundary() {
        assertEquals(paris("2026-10-18T04:00:00"), calendar.nextReset(QuestPeriod.DAILY, paris("2026-10-17T12:00:00")));
        assertEquals(paris("2026-10-17T04:00:00"), calendar.nextReset(QuestPeriod.DAILY, paris("2026-10-17T03:00:00")));
        assertEquals(paris("2026-10-19T04:00:00"), calendar.nextReset(QuestPeriod.WEEKLY, paris("2026-10-17T12:00:00")));
    }

    @Test
    void daylightSavingChangesStillResetExactlyOncePerDay() {
        // Passage à l'heure d'hiver le 25/10/2026 (3:00 → 2:00) : 4:00 existe une seule fois.
        Instant before = paris("2026-10-25T03:30:00");
        assertEquals("2026-10-24", calendar.periodKey(QuestPeriod.DAILY, before));
        assertEquals(paris("2026-10-25T04:00:00"), calendar.nextReset(QuestPeriod.DAILY, before));
        // Une réinitialisation à 02:30 le jour du passage à l'heure d'été (29/03/2026, 2:00 → 3:00)
        // tombe dans le « trou » : elle est décalée à 03:30, jamais sautée.
        PeriodCalendar gap = new PeriodCalendar(PARIS, LocalTime.of(2, 30), DayOfWeek.MONDAY);
        assertEquals("2026-03-28", gap.periodKey(QuestPeriod.DAILY, paris("2026-03-29T03:29:00")));
        assertEquals("2026-03-29", gap.periodKey(QuestPeriod.DAILY, paris("2026-03-29T03:30:00")));
    }

    @Test
    void theZoneDecidesTheDayNotTheHost() {
        PeriodCalendar utc = new PeriodCalendar(ZoneId.of("UTC"), LocalTime.MIDNIGHT, DayOfWeek.MONDAY);
        Instant instant = Instant.parse("2026-10-17T23:30:00Z");
        assertEquals("2026-10-17", utc.periodKey(QuestPeriod.DAILY, instant));
        assertEquals("2026-10-18", new PeriodCalendar(PARIS, LocalTime.MIDNIGHT, DayOfWeek.MONDAY)
                .periodKey(QuestPeriod.DAILY, instant));
    }
}
