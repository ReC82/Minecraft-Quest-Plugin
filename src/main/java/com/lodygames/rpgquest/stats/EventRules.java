package com.lodygames.rpgquest.stats;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Règles propres à un événement (issue #57), au-dessus de la fenêtre et des métriques d'une
 * {@link StatPeriod}. Un nouveau concours = une entrée de {@code statistics.yml}, jamais un listener :
 * la métrique dit ce qui est compté, le filtre restreint les cibles, le classement dit comment
 * départager.
 *
 * @param ranking règle de classement
 * @param filter  clé de filtre ({@code entity}, {@code special-mob}, {@code item}) → valeurs acceptées ;
 *                vide = toutes les cibles
 * @param goal    objectif commun ({@link EventRanking#COMMUNITY_GOAL}), 0 sinon
 * @param rewards récompenses déclaratives, jamais livrées par ce moteur
 */
public record EventRules(EventRanking ranking, Map<String, Set<String>> filter, long goal, List<EventReward> rewards) {

    /** Règles d'une saison, ou d'un événement sans précision : le plus grand total, sans filtre. */
    public static final EventRules DEFAULT = new EventRules(EventRanking.HIGHEST_TOTAL, Map.of(), 0L, List.of());

    public EventRules {
        filter = Map.copyOf(filter);
        rewards = List.copyOf(rewards);
    }

    public boolean communityGoal() {
        return ranking == EventRanking.COMMUNITY_GOAL;
    }
}
