package com.lodygames.rpgquest.quest.periodic;

import com.lodygames.rpgquest.config.PeriodicQuestConfig;
import com.lodygames.rpgquest.quest.model.QuestPeriod;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * Issue #15 — découpe le temps en périodes quotidiennes et hebdomadaires, dans le fuseau configuré.
 *
 * <p>Une période est identifiée par la <strong>date locale de son début</strong> ({@code 2026-10-17}) :
 * avant l'heure de réinitialisation, on est encore dans la période de la veille. La clé est la seule
 * chose persistée — elle reste valable si l'heure de réinitialisation change ensuite (au pire une
 * période raccourcie, jamais deux attributions pour une même clé).</p>
 *
 * <p>Changement d'heure : {@link ZonedDateTime#of} décale une heure locale inexistante (passage à
 * l'heure d'été) vers l'avant et garde la première occurrence d'une heure dupliquée : chaque
 * réinitialisation a lieu exactement une fois.</p>
 */
public final class PeriodCalendar {

    private final ZoneId zone;
    private final LocalTime resetTime;
    private final DayOfWeek weeklyResetDay;

    public PeriodCalendar(ZoneId zone, LocalTime resetTime, DayOfWeek weeklyResetDay) {
        this.zone = zone;
        this.resetTime = resetTime;
        this.weeklyResetDay = weeklyResetDay;
    }

    public static PeriodCalendar of(PeriodicQuestConfig config) {
        return new PeriodCalendar(config.zone(), config.resetTime(), config.weeklyResetDay());
    }

    /** Date locale de début de la période contenant {@code instant}. */
    public LocalDate periodStart(QuestPeriod period, Instant instant) {
        LocalDate day = gameDay(instant);
        return period == QuestPeriod.DAILY ? day : day.with(TemporalAdjusters.previousOrSame(weeklyResetDay));
    }

    /** Clé persistée de la période contenant {@code instant} (date ISO de son début). */
    public String periodKey(QuestPeriod period, Instant instant) {
        return periodStart(period, instant).toString();
    }

    /** Instant de la prochaine réinitialisation strictement après {@code instant}. */
    public Instant nextReset(QuestPeriod period, Instant instant) {
        LocalDate start = periodStart(period, instant);
        LocalDate next = period == QuestPeriod.DAILY ? start.plusDays(1) : start.plusWeeks(1);
        return resetInstant(next);
    }

    /** Jour « de jeu » : la date locale, reculée d'un jour avant l'heure de réinitialisation. */
    private LocalDate gameDay(Instant instant) {
        ZonedDateTime local = instant.atZone(zone);
        LocalDate date = local.toLocalDate();
        return instant.isBefore(resetInstant(date)) ? date.minusDays(1) : date;
    }

    private Instant resetInstant(LocalDate date) {
        return ZonedDateTime.of(date, resetTime, zone).toInstant();
    }
}
