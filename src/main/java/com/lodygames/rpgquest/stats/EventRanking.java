package com.lodygames.rpgquest.stats;

/**
 * Règle de classement d'un événement (issue #57). Elle fixe la manière de combiner les actions d'un
 * joueur sur la fenêtre et le sens du classement ; la métrique, elle, ne dit que ce qui est compté.
 */
public enum EventRanking {
    /** Le plus grand total en tête (« tuer le plus de zombies »). Règle par défaut. */
    HIGHEST_TOTAL(StatMetric.Aggregation.SUM, StatMetric.SortOrder.DESC),
    /**
     * Le plus petit total en tête (un chrono). N'a de sens que pour une métrique déclarée croissante
     * ({@link StatMetric.SortOrder#ASC}) : un joueur absent aurait sinon 0 et gagnerait.
     */
    LOWEST_TOTAL(StatMetric.Aggregation.SUM, StatMetric.SortOrder.ASC),
    /** La meilleure action unique (le plus gros octroi d'XP), pas la somme. */
    BEST_SINGLE(StatMetric.Aggregation.MAX, StatMetric.SortOrder.DESC),
    /** Objectif commun : le total de tous les participants contre {@code goal}, avec la liste des contributeurs. */
    COMMUNITY_GOAL(StatMetric.Aggregation.SUM, StatMetric.SortOrder.DESC);

    private final StatMetric.Aggregation aggregation;
    private final StatMetric.SortOrder order;

    EventRanking(StatMetric.Aggregation aggregation, StatMetric.SortOrder order) {
        this.aggregation = aggregation;
        this.order = order;
    }

    public StatMetric.Aggregation aggregation() {
        return aggregation;
    }

    public StatMetric.SortOrder order() {
        return order;
    }
}
