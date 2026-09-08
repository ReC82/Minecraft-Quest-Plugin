package com.lodygames.rpgquest.panel.agent;

import com.lodygames.rpgquest.panel.authz.Permission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Liste blanche <strong>côté Control Panel</strong> des actions agent (miroir de l'énumération
 * {@code AgentActionType} du plugin) : permission requise, nature (lecture / mutation), besoin d'un
 * joueur cible, et validation des paramètres <strong>avant</strong> toute création d'action.
 *
 * <p>Défense en profondeur : les mêmes bornes sont re-vérifiées par l'agent puis par le service
 * métier. Aucune action « console libre » n'existe ici — un type inconnu est refusé.</p>
 */
public final class AgentActionCatalog {

    private static final Pattern PLAYER_REF = Pattern.compile("[A-Za-z0-9_\\-]{1,40}|[0-9a-fA-F\\-]{36}");
    private static final Pattern VARIABLE_KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{1,128}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-zA-Z0-9_.:\\-/]{1,128}");
    private static final Pattern STORY_ID = Pattern.compile("[a-z0-9_-]{1,64}");

    /** Suggestions de clés de variables pour les listes déroulantes (jamais imposées). */
    public static final List<String> KNOWN_VARIABLE_KEYS = List.of(
            "CLAIM_TIER_1", "tutorial_started", "crystal_hunt_started", "RUNE_RAPPEL_GRANTED");

    /**
     * @param type        nom du fil (ex. {@code quest.start})
     * @param permission  permission requise
     * @param mutation    {@code true} = effet de bord (confirmation obligatoire côté panel)
     * @param needsPlayer {@code true} = paramètre {@code player} obligatoire
     * @param label       libellé humain court
     */
    public record Spec(String type, Permission permission, boolean mutation, boolean needsPlayer, String label) {
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    private static void add(String type, Permission p, boolean mutation, boolean needsPlayer, String label) {
        SPECS.put(type, new Spec(type, p, mutation, needsPlayer, label));
    }

    static {
        // Lectures
        add("player.list", Permission.PLAYERS_READ, false, false, "Rafraîchir les joueurs connectés");
        add("player.variable.get", Permission.ACTION_VARIABLE_GET, false, true, "Lire une variable joueur");
        add("player.resetnew.preview", Permission.PLAYERS_READ, false, true, "Aperçu du reset « nouveau joueur »");
        add("quest.list", Permission.CONTENT_READ, false, false, "Rafraîchir le catalogue de quêtes");
        add("quest.player.status", Permission.PLAYERS_READ, false, true, "État des quêtes d'un joueur");
        add("story.list", Permission.CONTENT_READ, false, false, "Rafraîchir le catalogue de stories");
        add("story.player.status", Permission.PLAYERS_READ, false, true, "État des stories d'un joueur");
        add("item.list", Permission.CONTENT_READ, false, false, "Rafraîchir la liste des objets");
        add("npc.list", Permission.NPC_READ, false, false, "Rafraîchir le catalogue des PNJ");
        // Mutations
        add("player.item.give", Permission.ACTION_ITEM_GIVE, true, true, "Donner un objet");
        add("player.variable.set", Permission.ACTION_VARIABLE_SET, true, true, "Écrire une variable (debug)");
        add("player.resetnew.confirm", Permission.ACTION_PLAYER_RESET, true, true, "Reset « nouveau joueur »");
        add("quest.start", Permission.ACTION_QUEST, true, true, "Démarrer une quête");
        add("quest.complete", Permission.ACTION_QUEST, true, true, "Compléter une quête");
        add("quest.reset", Permission.ACTION_QUEST, true, true, "Réinitialiser une quête");
        add("story.advance", Permission.ACTION_STORY, true, true, "Avancer une story");
        add("story.complete", Permission.ACTION_STORY, true, true, "Compléter une story");
    }

    private AgentActionCatalog() {
    }

    public static Optional<Spec> spec(String type) {
        return Optional.ofNullable(SPECS.get(type == null ? "" : type.trim()));
    }

    public static boolean isWhitelisted(String type) {
        return SPECS.containsKey(type == null ? "" : type.trim());
    }

    /** Résultat de validation : soit {@code params} (prêts à créer l'action), soit {@code error}. */
    public record Validation(Map<String, String> params, String error) {
        static Validation ok(Map<String, String> params) {
            return new Validation(params, null);
        }

        static Validation fail(String error) {
            return new Validation(null, error);
        }

        public boolean valid() {
            return error == null;
        }
    }

    /**
     * Valide {@code form} pour {@code type} et produit les paramètres normalisés à transmettre à
     * l'agent. Les mutations exigent {@code confirm=true} dans le formulaire.
     */
    public static Validation validate(String type, Map<String, String> form) {
        Optional<Spec> maybe = spec(type);
        if (maybe.isEmpty()) {
            return Validation.fail("Type d'action non autorisé : " + safe(type));
        }
        Spec spec = maybe.get();
        Map<String, String> params = new LinkedHashMap<>();

        if (spec.needsPlayer()) {
            String player = trim(form.get("player"));
            if (player.isEmpty() || !PLAYER_REF.matcher(player).matches()) {
                return Validation.fail("Nom / UUID de joueur manquant ou invalide.");
            }
            params.put("player", player);
        }
        if (spec.mutation() && !"true".equals(trim(form.get("confirm")))) {
            return Validation.fail("Confirmation obligatoire pour « " + spec.label() + " ».");
        }

        switch (type) {
            case "player.variable.get" -> {
                String key = orDefault(trim(form.get("key")), "CLAIM_TIER_1");
                if (!VARIABLE_KEY.matcher(key).matches()) {
                    return Validation.fail("Clé de variable invalide.");
                }
                params.put("key", key);
            }
            case "player.variable.set" -> {
                String key = trim(form.get("key"));
                String value = form.getOrDefault("value", "");
                if (!VARIABLE_KEY.matcher(key).matches()) {
                    return Validation.fail("Clé de variable invalide.");
                }
                if (value.isEmpty() || value.length() > 256) {
                    return Validation.fail("Valeur manquante ou trop longue (max 256).");
                }
                params.put("key", key);
                params.put("value", value);
            }
            case "player.item.give" -> {
                String itemId = trim(form.get("item_id"));
                if (!RESOURCE_ID.matcher(itemId).matches()) {
                    return Validation.fail("Identifiant d'objet manquant ou invalide.");
                }
                int amount;
                try {
                    amount = Integer.parseInt(orDefault(trim(form.get("amount")), "1"));
                } catch (NumberFormatException e) {
                    return Validation.fail("Quantité invalide.");
                }
                if (amount < 1 || amount > 64) {
                    return Validation.fail("Quantité hors bornes (1 à 64).");
                }
                params.put("item_id", itemId);
                params.put("amount", Integer.toString(amount));
            }
            case "quest.start" -> {
                String questId = trim(form.get("quest_id"));
                if (!RESOURCE_ID.matcher(questId).matches()) {
                    return Validation.fail("Identifiant de quête manquant ou invalide.");
                }
                params.put("quest_id", questId);
                if ("true".equals(trim(form.get("force")))) {
                    params.put("force", "true");
                }
            }
            case "quest.complete", "quest.reset" -> {
                String questId = trim(form.get("quest_id"));
                if (!RESOURCE_ID.matcher(questId).matches()) {
                    return Validation.fail("Identifiant de quête manquant ou invalide.");
                }
                params.put("quest_id", questId);
            }
            case "story.advance", "story.complete" -> {
                String storyId = trim(form.get("story_id")).toLowerCase(java.util.Locale.ROOT);
                if (!STORY_ID.matcher(storyId).matches()) {
                    return Validation.fail("Identifiant de story manquant ou invalide.");
                }
                params.put("story_id", storyId);
            }
            case "player.resetnew.confirm" -> params.put("confirm", "true");
            default -> {
                // player.list / *.player.status / *.list / player.resetnew.preview : pas de paramètre
                // supplémentaire au-delà de « player ».
            }
        }
        return Validation.ok(params);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String safe(String raw) {
        if (raw == null) {
            return "—";
        }
        String t = raw.strip();
        return t.length() > 48 ? t.substring(0, 48) + "…" : t;
    }
}
