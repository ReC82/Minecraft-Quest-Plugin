package com.lodygames.rpgquest.stats;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/**
 * Période d'un classement du catalogue (issue #734). Les périodes de calendrier
 * ({@link #DAILY}, {@link #WEEKLY}, {@link #MONTHLY}) tournent seules dans le fuseau de
 * {@code statistics.yml} (Europe/Brussels par défaut) : une fenêtre {@code [début, fin)} par jour,
 * semaine ISO (lundi → lundi) ou mois civil, identifiée par une clé stable ({@code 2026-10-23},
 * {@code 2026-W43}, {@code 2026-10}). {@link #SEASON} suit la saison montrée de {@code statistics.yml},
 * {@link #CUSTOM} des dates fixes, {@link #LIFETIME} tout le journal de la métrique.
 */
public enum BoardPeriod {
    LIFETIME,
    DAILY,
    WEEKLY,
    MONTHLY,
    SEASON,
    CUSTOM;

    /** Fin « ouverte » d'une fenêtre {@link #LIFETIME} : bien après toute date de jeu. */
    static final Instant OPEN_END = Instant.parse("9999-01-01T00:00:00Z");

    /** Une fenêtre {@code [start, end)} et sa clé (qui identifie aussi son archive). */
    public record Window(String key, Instant start, Instant end) {
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<BoardPeriod> parse(String text) {
        for (BoardPeriod period : values()) {
            if (period.wireName().equals(text)) {
                return Optional.of(period);
            }
        }
        return Optional.empty();
    }

    /** Vrai pour les périodes qui tournent toutes seules (jour, semaine, mois). */
    public boolean calendar() {
        return this == DAILY || this == WEEKLY || this == MONTHLY;
    }

    /** Fenêtre de calendrier qui contient {@code now} ; uniquement pour {@link #calendar()}. */
    public Window current(Instant now, ZoneId zone) {
        return windowStarting(firstDay(now.atZone(zone).toLocalDate()), zone);
    }

    /** Fenêtre de calendrier qui précède celle de {@code now} (la dernière terminée). */
    public Window previous(Instant now, ZoneId zone) {
        LocalDate first = firstDay(now.atZone(zone).toLocalDate());
        return windowStarting(switch (this) {
            case DAILY -> first.minusDays(1);
            case WEEKLY -> first.minusWeeks(1);
            case MONTHLY -> first.minusMonths(1);
            default -> throw new IllegalStateException("période sans calendrier : " + this);
        }, zone);
    }

    private LocalDate firstDay(LocalDate day) {
        return switch (this) {
            case DAILY -> day;
            case WEEKLY -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> day.withDayOfMonth(1);
            default -> throw new IllegalStateException("période sans calendrier : " + this);
        };
    }

    private Window windowStarting(LocalDate first, ZoneId zone) {
        LocalDate next = switch (this) {
            case DAILY -> first.plusDays(1);
            case WEEKLY -> first.plusWeeks(1);
            default -> first.plusMonths(1);
        };
        String key = switch (this) {
            case DAILY -> first.toString();
            case WEEKLY -> String.format(Locale.ROOT, "%d-W%02d", first.get(IsoFields.WEEK_BASED_YEAR),
                    first.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            default -> String.format(Locale.ROOT, "%d-%02d", first.getYear(), first.getMonthValue());
        };
        return new Window(key, first.atStartOfDay(zone).toInstant(), next.atStartOfDay(zone).toInstant());
    }
}
