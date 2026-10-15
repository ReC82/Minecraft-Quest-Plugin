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
 * <p>Issue #57 : les métriques d'activité ({@link #ENTITY_KILL}, {@link #FISH_CAUGHT}) se lisent
 * dans le journal générique {@code stat_activity_log}, alimenté par un seul listener pour tous les
 * concours. Elles acceptent un filtre de cible ({@code entity: [ZOMBIE]}) ; elles n'existent qu'à
 * partir de la mise en service du journal, donc seulement sur une saison ou un événement.</p>
 *
 * @param xpSkill  piste d'XP lue dans {@code xp_grants} pour les fenêtres ; {@code null} sinon
 * @param activity action lue dans {@code stat_activity_log} ; {@code null} sinon
 * @param filters  clé de filtre accepté → colonne du journal d'activité ({@code target}/{@code variant})
 */
public record StatMetric(String id, Aggregation aggregation, SortOrder order, Set<StatScope> scopes,
                         SkillType xpSkill, String activity, Map<String, String> filters) {

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
    /** Mob tué par un joueur (issue #57) : cible = type d'entité, variante = mob spécial RPGQuest. */
    public static final String ENTITY_KILL = "ENTITY_KILL";
    /** Prise de pêche (issue #57) : cible = matériau de l'objet pêché. */
    public static final String FISH_CAUGHT = "FISH_CAUGHT";
    private static final String XP_PREFIX = "RPG_XP_";

    private static final Map<String, StatMetric> CATALOG = buildCatalog();

    public StatMetric {
        scopes = Set.copyOf(scopes);
        filters = Map.copyOf(filters);
    }

    /** Métrique sans journal d'activité (XP, quêtes). */
    public StatMetric(String id, Aggregation aggregation, SortOrder order, Set<StatScope> scopes, SkillType xpSkill) {
        this(id, aggregation, order, scopes, xpSkill, null, Map.of());
    }

    /** Vrai si la métrique a un journal horodaté, donc se calcule sur une fenêtre. */
    public boolean windowed() {
        return xpSkill != null || activity != null;
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
        catalog.put(ENTITY_KILL, new StatMetric(ENTITY_KILL, Aggregation.SUM, SortOrder.DESC,
                EnumSet.of(StatScope.SEASON, StatScope.EVENT), null, ENTITY_KILL,
                Map.of("entity", "target", "special-mob", "variant")));
        catalog.put(FISH_CAUGHT, new StatMetric(FISH_CAUGHT, Aggregation.SUM, SortOrder.DESC,
                EnumSet.of(StatScope.SEASON, StatScope.EVENT), null, FISH_CAUGHT, Map.of("item", "target")));
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
        descriptor.put("filters", filters.keySet().stream().sorted().toList());
        return descriptor;
    }
}
