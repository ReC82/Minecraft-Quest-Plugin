package com.lodygames.rpgquest.content.pack;

import java.util.List;
import java.util.Map;

/**
 * Entrée « quête » d'un content pack (issue #108) — vocabulaire déclaratif <strong>identique au
 * schéma YAML des quêtes RPGQuest</strong> (le contrat public stable, versionné par
 * {@code schemaVersion}), sans dépendance Bukkit et sans champ runtime. Produit par
 * {@code ContentPackMapper} depuis une {@code QuestDefinition}, consommable tel quel par
 * {@code QuestDefinitionParser} (round-trip visé pour l'import #109).
 *
 * <ul>
 *   <li>{@code id} : identifiant métier stable, forme {@code namespace:clé} (jamais un chemin).</li>
 *   <li>{@code prerequisites} : ids de quêtes ({@code namespace:clé}) — relations préservées.</li>
 *   <li>{@code giver} : id de PNJ logique, ou {@code null}.</li>
 *   <li>{@code variables} : table {@code clé -> valeur} (chaînes), jamais des variables joueur.</li>
 * </ul>
 */
public record QuestPackEntry(
        String id,
        PackText title,
        PackText description,
        String category,
        String icon,
        boolean repeatable,
        boolean secret,
        String giver,
        List<String> prerequisites,
        List<Step> steps,
        List<Reward> rewards,
        Map<String, String> variables) {

    public QuestPackEntry {
        prerequisites = List.copyOf(prerequisites);
        steps = List.copyOf(steps);
        rewards = List.copyOf(rewards);
        variables = Map.copyOf(variables);
    }

    /** Étape ordonnée : un id + au moins un objectif. */
    public record Step(String id, List<Objective> objectives) {
        public Step {
            objectives = List.copyOf(objectives);
        }
    }

    /**
     * Objectif d'étape. {@code type} est un nom de
     * {@link com.lodygames.rpgquest.quest.model.ObjectiveType}. Les autres champs sont renseignés
     * selon le type (les nuls ne sont pas sérialisés) :
     * <ul>
     *   <li>{@code BREAK_BLOCK} / {@code PLACE_BLOCK} / {@code COLLECT_ITEM} / {@code CRAFT_ITEM} :
     *       {@code material} + {@code amount} ;</li>
     *   <li>{@code KILL_ENTITY} : {@code entity} + {@code amount} ;</li>
     *   <li>{@code TALK_TO_NPC} : {@code npc} ;</li>
     *   <li>{@code REACH_LOCATION} : {@code world} + {@code x}/{@code y}/{@code z} + {@code radius}.</li>
     * </ul>
     */
    public record Objective(String type, String material, String entity, String npc, String world,
                            Integer amount, Double x, Double y, Double z, Double radius) {

        public static Objective countable(String type, String material, int amount) {
            return new Objective(type, material, null, null, null, amount, null, null, null, null);
        }

        public static Objective kill(String entity, int amount) {
            return new Objective("KILL_ENTITY", null, entity, null, null, amount, null, null, null, null);
        }

        public static Objective talk(String npc) {
            return new Objective("TALK_TO_NPC", null, null, npc, null, null, null, null, null, null);
        }

        public static Objective reach(String world, double x, double y, double z, double radius) {
            return new Objective("REACH_LOCATION", null, null, null, world, null, x, y, z, radius);
        }
    }

    /**
     * Récompense. {@code type} est un nom de
     * {@link com.lodygames.rpgquest.quest.model.RewardType} :
     * <ul>
     *   <li>{@code EXPERIENCE} : {@code amount} ;</li>
     *   <li>{@code ITEM} : {@code material} + {@code amount} ;</li>
     *   <li>{@code VARIABLE} : {@code key} + {@code value} ;</li>
     *   <li>{@code COMMAND} : {@code command} (contenu éditorial, jamais un secret).</li>
     * </ul>
     */
    public record Reward(String type, Integer amount, String material, String key, String value, String command) {

        public static Reward experience(int amount) {
            return new Reward("EXPERIENCE", amount, null, null, null, null);
        }

        public static Reward item(String material, int amount) {
            return new Reward("ITEM", amount, material, null, null, null);
        }

        public static Reward variable(String key, String value) {
            return new Reward("VARIABLE", null, null, key, value, null);
        }

        public static Reward command(String command) {
            return new Reward("COMMAND", null, null, null, null, command);
        }
    }
}
