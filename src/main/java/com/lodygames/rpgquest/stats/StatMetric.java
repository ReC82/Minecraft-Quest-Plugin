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
 * <p>Issue #734 : chaque métrique horodatée déclare sa {@link Source} — la table, la colonne de
 * date et la clé à lire. Les découvertes (waypoints, villes, pierres de voyage) et les stories se
 * lisent ainsi directement dans leurs tables existantes, avec leur vraie date de découverte : aucune
 * copie, aucun historique reconstitué. Une métrique de plus = une entrée de {@link #buildCatalog()}.</p>
 *
 * @param xpSkill  piste d'XP lue dans {@code xp_grants} pour les fenêtres ; {@code null} sinon
 * @param activity action lue dans {@code stat_activity_log} ; {@code null} sinon
 * @param filters  clé de filtre accepté → colonne de la source ({@code target}/{@code variant})
 * @param source   où lire la métrique sur une fenêtre ; {@code null} pour une métrique sans journal
 */
public record StatMetric(String id, Aggregation aggregation, SortOrder order, Set<StatScope> scopes,
                         SkillType xpSkill, String activity, Map<String, String> filters, Source source) {

    /**
     * Lecture d'une métrique horodatée (#734). Tous les champs sont des constantes du code, jamais
     * une valeur venue d'un fichier : ils sont concaténés dans le SQL, les valeurs de filtre restent
     * des paramètres.
     *
     * @param table      table lue (alias {@code g} dans les requêtes)
     * @param keyColumn  colonne qui distingue la métrique dans une table partagée ({@code skill},
     *                   {@code action}) ; {@code null} si la table ne porte qu'une métrique
     * @param key        valeur attendue dans {@code keyColumn}
     * @param timeColumn horodatage ISO-8601 UTC ({@code Instant#toString()})
     * @param amount     expression SQL de la quantité ({@code g.amount} ou {@code 1} pour une découverte)
     * @param condition  condition SQL constante supplémentaire ({@code g.state = 'COMPLETED'}) ou {@code null}
     */
    public record Source(String table, String keyColumn, String key, String timeColumn, String amount,
                         String condition) {

        /** Vrai pour le journal d'XP, seul à porter des raisons d'octroi exclues ({@code admin_grant}). */
        public boolean xp() {
            return "xp_grants".equals(table);
        }
    }

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
    /** Bloc cassé (#734) : cible = matériau. Blocs posés par un joueur, casses refusées et créatif exclus. */
    public static final String BLOCK_BREAK = "BLOCK_BREAK";
    /** Animal élevé par un joueur (#734) : cible = type du petit. Naissances sans éleveur joueur exclues. */
    public static final String ANIMAL_BRED = "ANIMAL_BRED";
    /** Quête terminée pendant la fenêtre (#734) : une ligne par complétion, quêtes répétables comprises. */
    public static final String QUEST_COMPLETION = "QUEST_COMPLETION";
    /** Waypoints découverts (#734), lus dans {@code waypoint_discoveries} : revenir au même ne compte pas. */
    public static final String WAYPOINT_DISCOVERED = "WAYPOINT_DISCOVERED";
    /** Villes découvertes (#734), lues dans {@code city_discoveries}. */
    public static final String CITY_DISCOVERED = "CITY_DISCOVERED";
    /** Pierres de voyage découvertes (#734), lues dans {@code waystone_discoveries}. */
    public static final String WAYSTONE_DISCOVERED = "WAYSTONE_DISCOVERED";
    /** Stories terminées (#734) : dernier état {@code COMPLETED} de {@code story_progress}, daté de sa fin. */
    public static final String STORY_COMPLETED = "STORY_COMPLETED";
    private static final String XP_PREFIX = "RPG_XP_";

    private static final Map<String, StatMetric> CATALOG = buildCatalog();

    public StatMetric {
        scopes = Set.copyOf(scopes);
        filters = Map.copyOf(filters);
    }

    /** Métrique d'XP (journal {@code xp_grants}) ou sans journal (quêtes, {@code xpSkill} nul). */
    public StatMetric(String id, Aggregation aggregation, SortOrder order, Set<StatScope> scopes, SkillType xpSkill) {
        this(id, aggregation, order, scopes, xpSkill, null, Map.of(), xpSkill == null ? null
                : new Source("xp_grants", "skill", xpSkill.name(), "created_at", "g.amount", null));
    }

    /** Métrique du journal d'activité générique {@code stat_activity_log} (#57). */
    static StatMetric activity(String id, Map<String, String> filters) {
        return new StatMetric(id, Aggregation.SUM, SortOrder.DESC, EnumSet.of(StatScope.SEASON, StatScope.EVENT),
                null, id, filters, new Source("stat_activity_log", "action", id, "created_at", "g.amount", null));
    }

    /** Métrique lue dans une table de découvertes existante (#734) : une ligne = une découverte. */
    static StatMetric discovery(String id, String table) {
        return new StatMetric(id, Aggregation.SUM, SortOrder.DESC, EnumSet.of(StatScope.SEASON, StatScope.EVENT),
                null, null, Map.of(), new Source(table, null, null, "discovered_at", "1", null));
    }

    /** Vrai si la métrique a un journal horodaté, donc se calcule sur une fenêtre. */
    public boolean windowed() {
        return source != null;
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
        catalog.put(ENTITY_KILL, activity(ENTITY_KILL,
                Map.of("entity", "target", "entity-family", "target", "special-mob", "variant")));
        catalog.put(FISH_CAUGHT, activity(FISH_CAUGHT, Map.of("item", "target")));
        catalog.put(BLOCK_BREAK, activity(BLOCK_BREAK, Map.of("block", "target", "block-family", "target")));
        catalog.put(ANIMAL_BRED, activity(ANIMAL_BRED, Map.of("entity", "target")));
        catalog.put(QUEST_COMPLETION, activity(QUEST_COMPLETION, Map.of("quest", "target")));
        catalog.put(WAYPOINT_DISCOVERED, discovery(WAYPOINT_DISCOVERED, "waypoint_discoveries"));
        catalog.put(CITY_DISCOVERED, discovery(CITY_DISCOVERED, "city_discoveries"));
        catalog.put(WAYSTONE_DISCOVERED, discovery(WAYSTONE_DISCOVERED, "waystone_discoveries"));
        catalog.put(STORY_COMPLETED, new StatMetric(STORY_COMPLETED, Aggregation.SUM, SortOrder.DESC,
                EnumSet.of(StatScope.SEASON, StatScope.EVENT), null, null, Map.of(),
                new Source("story_progress", null, null, "updated_at", "1", "g.state = 'COMPLETED'")));
        return catalog;
    }

    /**
     * Mesures demandées par le catalogue (#734) que le plugin <strong>ne mesure pas encore</strong>,
     * avec la raison montrée sur le portail (« Métrique indisponible ») : jamais une valeur
     * reconstituée. Une mesure quitte cette liste le jour où elle a une {@link Source}.
     */
    public static final Map<String, String> PLANNED = planned();

    private static Map<String, String> planned() {
        Map<String, String> planned = new LinkedHashMap<>();
        planned.put("DISTANCE_TRAVELED", "Distance parcourue (marche, nage, vol) : pas encore mesurée.");
        planned.put("PLAY_TIME", "Temps de jeu et jours actifs : pas encore mesurés.");
        planned.put("ITEM_CRAFTED", "Objets fabriqués et cuissons : pas encore journalisés pour les classements.");
        planned.put("ANIMAL_SHEARED", "Tonte : pas encore mesurée (l'élevage, lui, est suivi).");
        planned.put("BIOME_VISITED", "Biomes visités : seuls les waypoints découverts (un par zone de biome) sont suivis.");
        planned.put("STRUCTURE_VISITED", "Structures visitées : aucun événement fiable pour l'instant.");
        planned.put("ACHIEVEMENT", "Succès et défis : aucun système de succès RPGQuest pour l'instant.");
        planned.put("RPG_LEVEL", "Niveau RPG : classé par l'XP RPG gagnée, qui le détermine.");
        planned.put("COMMUNITY_CONTRIBUTION", "Contributions aux quêtes et constructions communautaires : pas encore livrées (#247).");
        planned.put("ECONOMY", "Économie : jamais publiée (aucun solde ni échange individuel).");
        return java.util.Collections.unmodifiableMap(planned);
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
