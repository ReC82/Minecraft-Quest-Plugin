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
 * @param description   phrase publique facultative sous le titre (#735), ou {@code null}
 * @param status        cycle de vie administré depuis PlugAdmin (#735) : seul {@link Status#ENABLED} est calculé
 * @param order         ordre d'affichage (croissant), puis ordre du fichier
 * @param visible       publié sur le site ; un classement actif mais caché est calculé et figé sans être montré
 * @param event         id de l'événement de {@code statistics.yml} suivi par {@link BoardPeriod#EVENT}, sinon {@code null}
 */
public record StatBoard(String id, String name, String category, String icon, String metric,
                        Map<String, Set<String>> filter, Map<String, List<String>> declared, BoardPeriod period,
                        Instant start, Instant end, int size, boolean featured, String description,
                        Status status, int order, boolean visible, String event) {

    /**
     * Cycle de vie d'un classement (#735). {@link #DRAFT} : en préparation, jamais calculé ;
     * {@link #ENABLED} : calculé, figé à la fin de chaque fenêtre, publié s'il est visible ;
     * {@link #PAUSED} : suspendu (ni calcul ni publication), réglages conservés ; {@link #ARCHIVED} :
     * retiré, ses résultats déjà figés restent en base.
     */
    public enum Status {
        DRAFT, ENABLED, PAUSED, ARCHIVED;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Status> parse(String text) {
            for (Status status : values()) {
                if (status.wireName().equals(text)) {
                    return Optional.of(status);
                }
            }
            return Optional.empty();
        }
    }

    /** Thèmes publics, mêmes identifiants que les filtres du portail. */
    public static final List<String> CATEGORIES =
            List.of("aventure", "combat", "recolte", "exploration", "communaute", "autres");

    public StatBoard {
        filter = Map.copyOf(filter);
        declared = Map.copyOf(declared);
    }

    /** Classement actif, visible, sans description ni événement (catalogue #734). */
    public StatBoard(String id, String name, String category, String icon, String metric,
                     Map<String, Set<String>> filter, Map<String, List<String>> declared, BoardPeriod period,
                     Instant start, Instant end, int size, boolean featured) {
        this(id, name, category, icon, metric, filter, declared, period, start, end, size, featured, null,
                Status.ENABLED, 0, true, null);
    }

    /** Vrai s'il est calculé et figé (actif), qu'il soit visible ou non. */
    public boolean active() {
        return status == Status.ENABLED;
    }

    /** Vrai s'il est montré sur le site : actif et visible. */
    public boolean published() {
        return active() && visible;
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

    /** Fenêtre en cours à {@code now} selon {@code definition} (saison montrée, événement suivi). */
    public Optional<BoardPeriod.Window> current(Instant now, StatisticsDefinition definition) {
        if (period == BoardPeriod.EVENT) {
            return definition.find(event).filter(e -> e.state() == StatPeriod.State.PLANNED)
                    .map(e -> new BoardPeriod.Window(e.id(), e.start(), e.end()));
        }
        return current(now, definition.zone(), definition.currentSeason(now));
    }

    /** Fenêtre en cours à {@code now} ; vide pour une saison absente ou des dates pas encore commencées. */
    public Optional<BoardPeriod.Window> current(Instant now, java.time.ZoneId zone, Optional<StatPeriod> season) {
        return switch (period) {
            case LIFETIME -> Optional.of(new BoardPeriod.Window("lifetime", Instant.EPOCH, BoardPeriod.OPEN_END));
            case DAILY, WEEKLY, MONTHLY -> Optional.of(period.current(now, zone));
            case SEASON -> season.map(s -> new BoardPeriod.Window(s.id(), s.start(), s.end()));
            case CUSTOM -> Optional.of(new BoardPeriod.Window("custom", start, end));
            case EVENT -> Optional.empty();
        };
    }

    /** Dernière fenêtre terminée d'un classement de calendrier, à figer ; vide sinon. */
    public Optional<BoardPeriod.Window> previous(Instant now, java.time.ZoneId zone) {
        return period.calendar() ? Optional.of(period.previous(now, zone)) : Optional.empty();
    }
}
