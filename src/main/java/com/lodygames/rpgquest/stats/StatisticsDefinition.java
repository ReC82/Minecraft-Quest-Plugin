package com.lodygames.rpgquest.stats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Contenu validé de {@code statistics.yml} (issue #54) : saisons, événements, exclusions de
 * modération et raisons d'XP qui ne comptent jamais dans un classement de période.
 *
 * @param exclusions joueur → portées exclues ({@link #ALL_SCOPES}, {@code lifetime} ou un id de période)
 * @param excludedXpReasons raisons d'octroi d'XP ignorées sur les fenêtres (outils admin de test)
 * @param boards     catalogue des classements (#734), dans l'ordre d'affichage
 * @param zone       fuseau des périodes de calendrier (jour, semaine, mois), Europe/Brussels par défaut
 */
public record StatisticsDefinition(List<StatPeriod> periods, Map<UUID, Set<String>> exclusions,
                                   Set<String> excludedXpReasons, List<StatBoard> boards, java.time.ZoneId zone) {

    /** Fuseau par défaut des classements de calendrier : celui de la communauté. */
    public static final java.time.ZoneId DEFAULT_ZONE = java.time.ZoneId.of("Europe/Brussels");

    /** Exclusion d'un joueur de toutes les portées, {@code lifetime} compris. */
    public static final String ALL_SCOPES = "*";
    /** Raison écrite par {@code /skills admin grant} : jamais comptée dans une saison ou un événement. */
    public static final Set<String> DEFAULT_EXCLUDED_XP_REASONS = Set.of("admin_grant");

    public static final StatisticsDefinition EMPTY =
            new StatisticsDefinition(List.of(), Map.of(), DEFAULT_EXCLUDED_XP_REASONS);

    /** Définition sans catalogue de classements (saisons et événements seuls, #54/#57). */
    public StatisticsDefinition(List<StatPeriod> periods, Map<UUID, Set<String>> exclusions,
                                Set<String> excludedXpReasons) {
        this(periods, exclusions, excludedXpReasons, List.of(), DEFAULT_ZONE);
    }

    public StatisticsDefinition {
        periods = List.copyOf(periods);
        boards = List.copyOf(boards);
        exclusions = Map.copyOf(exclusions);
        excludedXpReasons = Set.copyOf(excludedXpReasons);
    }

    public Optional<StatPeriod> find(String periodId) {
        return periods.stream().filter(period -> period.id().equals(periodId)).findFirst();
    }

    /** Joueurs exclus de la portée {@code scopeId} ({@code lifetime} ou un id de période). */
    public Set<UUID> excludedFrom(String scopeId) {
        Set<UUID> excluded = new HashSet<>();
        for (Map.Entry<UUID, Set<String>> entry : exclusions.entrySet()) {
            if (entry.getValue().contains(ALL_SCOPES) || entry.getValue().contains(scopeId)) {
                excluded.add(entry.getKey());
            }
        }
        return excluded;
    }

    public Optional<StatBoard> board(String boardId) {
        return boards.stream().filter(board -> board.id().equals(boardId)).findFirst();
    }

    public List<StatPeriod> events() {
        return periods.stream().filter(period -> period.scope() == StatScope.EVENT)
                .sorted(Comparator.comparing(StatPeriod::start).thenComparing(StatPeriod::id)).toList();
    }

    /**
     * La saison à montrer : celle en cours, sinon la dernière terminée, sinon la prochaine. Les
     * saisons ne se chevauchent jamais (validé au chargement), le choix est donc unique. Une saison
     * en brouillon ou annulée n'est jamais montrée (#57).
     */
    public Optional<StatPeriod> currentSeason(Instant now) {
        List<StatPeriod> seasons = new ArrayList<>(periods.stream()
                .filter(period -> period.scope() == StatScope.SEASON && period.state() == StatPeriod.State.PLANNED)
                .toList());
        seasons.sort(Comparator.comparing(StatPeriod::start));
        Optional<StatPeriod> active = seasons.stream()
                .filter(season -> season.status(now) == StatPeriod.Status.ACTIVE).findFirst();
        if (active.isPresent()) {
            return active;
        }
        Optional<StatPeriod> lastEnded = seasons.stream()
                .filter(season -> season.status(now) == StatPeriod.Status.ENDED)
                .reduce((first, second) -> second);
        return lastEnded.isPresent() ? lastEnded : seasons.stream().findFirst();
    }
}
