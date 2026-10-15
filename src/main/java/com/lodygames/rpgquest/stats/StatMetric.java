package com.lodygames.rpgquest.stats;

import com.lodygames.rpgquest.progression.model.SkillType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Une métrique publiable dans un classement (issue #54) : identifiant stable, manière de combiner
 * les valeurs, sens du classement et portées réellement calculables.
 *
 * <p>Le catalogue ne contient que des métriques adossées à une <strong>source de vérité
 * existante</strong>. Une portée n'est déclarée que si la source permet de la calculer exactement :
 * l'XP RPG est un journal horodaté et idempotent ({@code xp_grants}), donc lisible sur n'importe
 * quelle fenêtre ; les quêtes terminées ne gardent que leur dernier état ({@code quest_progress}),
 * donc seulement {@code lifetime}. Ajouter une métrique = une entrée ici et sa requête, sans
 * migration de schéma.</p>
 *
 * @param xpSkill piste d'XP lue dans {@code xp_grants} pour les fenêtres ; {@code null} si la
 *                métrique n'a pas de journal horodaté
 */
public record StatMetric(String id, Aggregation aggregation, SortOrder order, Set<StatScope> scopes,
                         SkillType xpSkill) {

    /** Comment les valeurs d'un joueur se combinent sur une portée. */
    public enum Aggregation {
        /** Somme (XP gagnée, quêtes terminées…). */
        SUM,
        /** Meilleur score — prévu pour les mini-jeux, aucune métrique ne l'utilise encore. */
        MAX
    }

    /** Sens du classement : la plus grande valeur en tête, ou la plus petite (un chrono). */
    public enum SortOrder {
        DESC,
        ASC
    }

    public static final String QUESTS_COMPLETED = "QUESTS_COMPLETED";
    private static final String XP_PREFIX = "RPG_XP_";

    private static final Map<String, StatMetric> CATALOG = buildCatalog();

    public StatMetric {
        scopes = Set.copyOf(scopes);
    }

    public boolean supports(StatScope scope) {
        return scopes.contains(scope);
    }

    /** Toutes les métriques connues, dans un ordre stable. */
    public static List<StatMetric> catalog() {
        return List.copyOf(CATALOG.values());
    }

    public static Optional<StatMetric> find(String id) {
        return Optional.ofNullable(CATALOG.get(id));
    }

    /** Identifiant de la métrique d'XP d'une piste ({@code RPG_XP_COMBAT}…). */
    public static String xpMetricId(SkillType skill) {
        return XP_PREFIX + skill.name();
    }

    private static Map<String, StatMetric> buildCatalog() {
        Map<String, StatMetric> catalog = new LinkedHashMap<>();
        for (SkillType skill : SkillType.values()) {
            catalog.put(xpMetricId(skill), new StatMetric(xpMetricId(skill), Aggregation.SUM, SortOrder.DESC,
                    EnumSet.allOf(StatScope.class), skill));
        }
        catalog.put(QUESTS_COMPLETED, new StatMetric(QUESTS_COMPLETED, Aggregation.SUM, SortOrder.DESC,
                EnumSet.of(StatScope.LIFETIME), null));
        return catalog;
    }

    /** Description publiée dans l'instantané : le contrat que le portail lit pour afficher un classement. */
    public Map<String, Object> describe() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("id", id);
        descriptor.put("aggregation", aggregation.name());
        descriptor.put("order", order.name());
        List<String> wireScopes = new ArrayList<>();
        for (StatScope scope : StatScope.values()) {
            if (scopes.contains(scope)) {
                wireScopes.add(scope.wireName());
            }
        }
        descriptor.put("scopes", wireScopes);
        return descriptor;
    }
}
