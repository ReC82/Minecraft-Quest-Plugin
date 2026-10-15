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
 */
public record StatisticsDefinition(List<StatPeriod> periods, Map<UUID, Set<String>> exclusions,
                                   Set<String> excludedXpReasons) {

    /** Exclusion d'un joueur de toutes les portées, {@code lifetime} compris. */
    public static final String ALL_SCOPES = "*";
    /** Raison écrite par {@code /skills admin grant} : jamais comptée dans une saison ou un événement. */
    public static final Set<String> DEFAULT_EXCLUDED_XP_REASONS = Set.of("admin_grant");

    public static final StatisticsDefinition EMPTY =
            new StatisticsDefinition(List.of(), Map.of(), DEFAULT_EXCLUDED_XP_REASONS);

    public StatisticsDefinition {
        periods = List.copyOf(periods);
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

    public List<StatPeriod> events() {
        return periods.stream().filter(period -> period.scope() == StatScope.EVENT)
                .sorted(Comparator.comparing(StatPeriod::start).thenComparing(StatPeriod::id)).toList();
    }

    /**
     * La saison à montrer : celle en cours, sinon la dernière terminée, sinon la prochaine. Les
     * saisons ne se chevauchent jamais (validé au chargement), le choix est donc unique.
     */
    public Optional<StatPeriod> currentSeason(Instant now) {
        List<StatPeriod> seasons = new ArrayList<>(periods.stream()
                .filter(period -> period.scope() == StatScope.SEASON).toList());
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
