package com.lodygames.rpgquest.stats;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Un classement du catalogue (issue #734) : <strong>une entrée déclarative</strong> de
 * {@code statistics.yml} (ou du catalogue par défaut du JAR), jamais une classe Java. Une métrique
 * du catalogue {@link StatMetric}, un filtre de cibles facultatif (types, familles), une période.
 *
 * <p>Le calcul réutilise entièrement le moteur des saisons et événements (#54/#57) : chaque fenêtre
 * du classement est une {@link StatPeriod} synthétique ({@link #periodFor}), lue dans la source de la
 * métrique et figée à sa fin dans {@code stat_period_results} sous l'identifiant
 * {@code board_<id>_<clé>} — même requête, mêmes ex aequo, même idempotence.</p>
 *
 * @param category      thème public ({@code combat}, {@code recolte}…)
 * @param icon          icône du portail, ou {@code null} pour celle de la métrique
 * @param filter        filtre résolu (familles remplacées par leurs membres), lu par la requête
 * @param declared      filtre tel qu'écrit (familles comprises), seul publié
 * @param start         début des dates personnalisées ({@link BoardPeriod#CUSTOM}), sinon {@code null}
 * @param end           fin des dates personnalisées, sinon {@code null}
 * @param size          nombre de joueurs affichés
 * @param featured      mis en avant sur l'accueil du portail
 */
public record StatBoard(String id, String name, String category, String icon, String metric,
                        Map<String, Set<String>> filter, Map<String, List<String>> declared, BoardPeriod period,
                        Instant start, Instant end, int size, boolean featured) {

    /** Thèmes publics, mêmes identifiants que les filtres du portail. */
    public static final List<String> CATEGORIES =
            List.of("aventure", "combat", "recolte", "exploration", "communaute", "autres");

    public StatBoard {
        filter = Map.copyOf(filter);
        declared = Map.copyOf(declared);
    }

    public StatMetric statMetric() {
        return StatMetric.find(metric).orElseThrow();
    }

    /**
     * La période synthétique d'une fenêtre de ce classement, classée par plus grand total. Son
     * identifiant ne contient que {@code [a-z0-9_]} : il sert de clé d'archive.
     */
    public StatPeriod periodFor(BoardPeriod.Window window) {
        String key = window.key().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return new StatPeriod("board_" + id + "_" + key, StatScope.EVENT, name, window.start(), window.end(),
                List.of(metric), StatPeriod.State.PLANNED,
                new EventRules(EventRanking.HIGHEST_TOTAL, filter, 0L, List.of()));
    }

    /** Fenêtre en cours à {@code now} ; vide pour une saison absente ou des dates pas encore commencées. */
    public Optional<BoardPeriod.Window> current(Instant now, java.time.ZoneId zone, Optional<StatPeriod> season) {
        return switch (period) {
            case LIFETIME -> Optional.of(new BoardPeriod.Window("lifetime", Instant.EPOCH, BoardPeriod.OPEN_END));
            case DAILY, WEEKLY, MONTHLY -> Optional.of(period.current(now, zone));
            case SEASON -> season.map(s -> new BoardPeriod.Window(s.id(), s.start(), s.end()));
            case CUSTOM -> Optional.of(new BoardPeriod.Window("custom", start, end));
        };
    }

    /** Dernière fenêtre terminée d'un classement de calendrier, à figer ; vide sinon. */
    public Optional<BoardPeriod.Window> previous(Instant now, java.time.ZoneId zone) {
        return period.calendar() ? Optional.of(period.previous(now, zone)) : Optional.empty();
    }
}
