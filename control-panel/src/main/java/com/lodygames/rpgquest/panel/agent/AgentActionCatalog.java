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
    private static final Pattern NPC_ID = Pattern.compile("[a-z0-9._-]{1,64}");
    private static final Pattern DIALOGUE_REF = Pattern.compile("[a-z0-9._-]{1,64}(?::[a-z0-9._/-]{1,128})?");
    private static final Pattern DIALOGUE_NODE_ID = Pattern.compile("[a-z0-9_][a-z0-9_-]{0,63}");
    private static final int MAX_DIALOGUE_TEXT = 512;
    private static final int MAX_DIALOGUE_CHOICE_INDEX = 199;
    private static final Pattern NPC_ROLE = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Familles de contenu exportables (issue #108) — miroir de {@code ContentFamily} côté plugin. */
    private static final java.util.Set<String> CONTENT_FAMILIES =
            java.util.Set.of("all", "quests", "stories", "dialogues", "npcs");
    private static final int MAX_EXPORT_IDS = 500;
    private static final Pattern WORLD_NAME = Pattern.compile("[A-Za-z0-9_./-]{1,64}");
    /** Bornes de sécurité de position miroir de {@code CitizensSpawnPlanner} côté plugin (#81 phase 2). */
    private static final double HORIZONTAL_LIMIT = 29_999_984.0;
    private static final double Y_MIN = -2048.0;
    private static final double Y_MAX = 2048.0;

    /** Suggestions de clés de variables pour les listes déroulantes (jamais imposées). */
    public static final List<String> KNOWN_VARIABLE_KEYS = List.of(
            "CLAIM_TIER_1", "tutorial_started", "crystal_hunt_started", "RUNE_RAPPEL_GRANTED");

    /**
     * Couleurs MiniMessage proposées par la palette du formulaire de dialogue (issue #118) : l'ordre
     * est l'ordre d'affichage. Le panel enrobe le texte avec {@code <couleur>…</couleur>} — jamais de
     * couleur hors de cette liste, jamais de balise si l'utilisateur a déjà écrit du MiniMessage.
     */
    public static final List<String> PALETTE_COLORS = List.of(
            "white", "gray", "yellow", "gold", "green", "dark_green", "aqua", "dark_aqua",
            "blue", "dark_blue", "red", "dark_red", "light_purple", "dark_purple");

    /**
     * @param type         nom du fil (ex. {@code quest.start})
     * @param permission   permission requise
     * @param mutation     {@code true} = effet de bord
     * @param needsPlayer  {@code true} = paramètre {@code player} obligatoire
     * @param sensitive    {@code true} = action réellement sensible / difficilement réversible
     *                     (bannissement, reset, spawn d'entité, suppression…) → confirmation
     *                     explicite obligatoire. {@code false} = édition de contenu normale et
     *                     réversible → aucune case à cocher cachée (issues #111 / #113 / #118).
     * @param label        libellé humain court
     * @param refreshTypes relevés {@code *.list} dont l'instantané du Control Panel devient périmé
     *                     quand cette action réussit — ré-enfilés automatiquement (issues #112 /
     *                     #115 / #116 / #119 / #120). Jamais de refresh ad hoc par écran.
     */
    public record Spec(String type, Permission permission, boolean mutation, boolean needsPlayer,
                       boolean sensitive, String label, List<String> refreshTypes) {
        public Spec {
            refreshTypes = refreshTypes == null ? List.of() : List.copyOf(refreshTypes);
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    /** Lecture ou mutation « classique » : une mutation non annotée reste sensible (comportement historique). */
    private static void add(String type, Permission p, boolean mutation, boolean needsPlayer, String label) {
        SPECS.put(type, new Spec(type, p, mutation, needsPlayer, mutation, label, List.of()));
    }

    /**
     * Édition de contenu réversible (création / modification d'une définition, d'un nœud, d'un
     * choix, d'une liaison logique) : pas de confirmation cachée, mais invalidation des catalogues
     * cités dès le succès.
     */
    private static void addContentWrite(String type, Permission p, String label, String... refresh) {
        SPECS.put(type, new Spec(type, p, true, false, false, label, List.of(refresh)));
    }

    /** Mutation sensible qui invalide malgré tout des catalogues (spawn d'entité, bannissement…). */
    private static void addSensitiveWrite(String type, Permission p, boolean needsPlayer, String label,
                                          String... refresh) {
        SPECS.put(type, new Spec(type, p, true, needsPlayer, true, label, List.of(refresh)));
    }

    static {
        // Lectures
        add("player.list", Permission.PLAYERS_READ, false, false, "Rafraîchir les joueurs connectés");
        add("player.catalog", Permission.PLAYERS_READ, false, false, "Rafraîchir l'annuaire des joueurs");
        add("player.variable.get", Permission.ACTION_VARIABLE_GET, false, true, "Lire une variable joueur");
        add("player.resetnew.preview", Permission.PLAYERS_READ, false, true, "Aperçu du reset « nouveau joueur »");
        add("quest.list", Permission.CONTENT_READ, false, false, "Rafraîchir le catalogue de quêtes");
        add("quest.player.status", Permission.PLAYERS_READ, false, true, "État des quêtes d'un joueur");
        add("story.list", Permission.CONTENT_READ, false, false, "Rafraîchir le catalogue de stories");
        add("story.player.status", Permission.PLAYERS_READ, false, true, "État des stories d'un joueur");
        add("item.list", Permission.CONTENT_READ, false, false, "Rafraîchir la liste des objets");
        add("npc.list", Permission.NPC_READ, false, false, "Rafraîchir le catalogue des PNJ");
        add("npc.citizens.list", Permission.NPC_READ, false, false, "Rafraîchir les PNJ Citizens");
        add("dialogue.list", Permission.DIALOGUE_READ, false, false, "Rafraîchir le catalogue des dialogues");
        // Export versionné du contenu déclaratif (issue #108) — lecture seule, aucun effet de bord,
        // aucun catalogue à réenfiler.
        add("content.export", Permission.CONTENT_EXPORT, false, false, "Exporter le contenu (pack versionné)");
        // Écritures de contenu (V2 déclarative des PNJ) — réversibles, jamais de YAML brut. Chaque
        // succès ré-enfile le(s) relevé(s) de catalogue impacté(s) pour que la vue métier se
        // réconcilie sans « Rafraîchir catalogue + F5 ».
        addContentWrite("npc.definition.create", Permission.NPC_WRITE, "Créer une définition PNJ", "npc.list");
        addContentWrite("npc.definition.update", Permission.NPC_WRITE, "Modifier une définition PNJ", "npc.list");
        addContentWrite("quest.giver.set", Permission.QUEST_GIVER_WRITE, "Attribuer une quête à un PNJ",
                "npc.list", "quest.list");
        addContentWrite("npc.citizens.link", Permission.NPC_BIND_WRITE, "Lier un PNJ Citizens existant",
                "npc.list", "npc.citizens.list");
        addSensitiveWrite("npc.citizens.create", Permission.NPC_SPAWN_WRITE, false, "Créer le PNJ Citizens",
                "npc.list", "npc.citizens.list");
        addContentWrite("dialogue.definition.create", Permission.DIALOGUE_WRITE, "Créer un dialogue (squelette)",
                "dialogue.list");
        addContentWrite("dialogue.node.create", Permission.DIALOGUE_WRITE, "Ajouter un nœud", "dialogue.list");
        addContentWrite("dialogue.node.update", Permission.DIALOGUE_WRITE, "Modifier un nœud", "dialogue.list");
        addContentWrite("dialogue.choice.add", Permission.DIALOGUE_WRITE, "Ajouter un choix", "dialogue.list");
        addContentWrite("dialogue.choice.update", Permission.DIALOGUE_WRITE, "Modifier un choix", "dialogue.list");
        addSensitiveWrite("dialogue.choice.delete", Permission.DIALOGUE_WRITE, false, "Supprimer un choix",
                "dialogue.list");
        // Mutations
        add("player.item.give", Permission.ACTION_ITEM_GIVE, true, true, "Donner un objet");
        add("player.variable.set", Permission.ACTION_VARIABLE_SET, true, true, "Écrire une variable (debug)");
        add("player.resetnew.confirm", Permission.ACTION_PLAYER_RESET, true, true, "Reset « nouveau joueur »");
        addSensitiveWrite("player.ban", Permission.PLAYER_MODERATE, true, "Bannir un joueur", "player.catalog");
        addSensitiveWrite("player.unban", Permission.PLAYER_MODERATE, true, "Débannir un joueur", "player.catalog");
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
        if (spec.mutation() && spec.sensitive() && !"true".equals(trim(form.get("confirm")))) {
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
            case "npc.definition.create", "npc.definition.update" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide (minuscules, « . _ - »).");
                }
                String displayName = trim(form.get("display_name"));
                if (displayName.isEmpty() || displayName.length() > 128 || displayName.indexOf('\n') >= 0) {
                    return Validation.fail("Nom affiché manquant, trop long, ou multi-ligne.");
                }
                String dialogueId = trim(form.get("dialogue_id")).toLowerCase(java.util.Locale.ROOT);
                if (!dialogueId.isEmpty() && !DIALOGUE_REF.matcher(dialogueId).matches()) {
                    return Validation.fail("Dialogue invalide (« namespace:clé » ou une clé simple).");
                }
                String role = trim(form.get("role")).toLowerCase(java.util.Locale.ROOT);
                if (!role.isEmpty() && !NPC_ROLE.matcher(role).matches()) {
                    return Validation.fail("Rôle invalide (minuscules, « _ - », max 32).");
                }
                params.put("npc_id", npcId);
                params.put("display_name", displayName);
                if (!dialogueId.isEmpty()) {
                    params.put("dialogue_id", dialogueId);
                }
                if (!role.isEmpty()) {
                    params.put("role", role);
                }
                params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
            }
            case "quest.giver.set" -> {
                String questId = trim(form.get("quest_id"));
                if (!RESOURCE_ID.matcher(questId).matches()) {
                    return Validation.fail("Identifiant de quête manquant ou invalide.");
                }
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                params.put("quest_id", questId);
                params.put("npc_id", npcId);
            }
            case "npc.citizens.link" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                int citizensId;
                try {
                    citizensId = Integer.parseInt(trim(form.get("citizens_id")));
                } catch (NumberFormatException e) {
                    return Validation.fail("Identifiant Citizens manquant ou invalide.");
                }
                if (citizensId < 1 || citizensId > 10_000_000) {
                    return Validation.fail("Identifiant Citizens hors bornes.");
                }
                params.put("npc_id", npcId);
                params.put("citizens_id", Integer.toString(citizensId));
            }
            case "npc.citizens.create" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                String world = trim(form.get("world"));
                if (!WORLD_NAME.matcher(world).matches()) {
                    return Validation.fail("Nom de monde manquant ou invalide.");
                }
                Double x = finite(form.get("x"));
                Double y = finite(form.get("y"));
                Double z = finite(form.get("z"));
                if (x == null || y == null || z == null) {
                    return Validation.fail("Coordonnées X / Y / Z manquantes ou non numériques.");
                }
                if (Math.abs(x) > HORIZONTAL_LIMIT || Math.abs(z) > HORIZONTAL_LIMIT) {
                    return Validation.fail("X / Z hors du bord de monde (±" + (long) HORIZONTAL_LIMIT + ").");
                }
                if (y < Y_MIN || y > Y_MAX) {
                    return Validation.fail("Y hors bornes de sécurité (" + (long) Y_MIN + " à " + (long) Y_MAX + ").");
                }
                Double yaw = form.getOrDefault("yaw", "").isBlank() ? Double.valueOf(0.0) : finite(form.get("yaw"));
                Double pitch = form.getOrDefault("pitch", "").isBlank() ? Double.valueOf(0.0) : finite(form.get("pitch"));
                if (yaw == null || pitch == null) {
                    return Validation.fail("Orientation yaw / pitch non numérique.");
                }
                if (pitch < -90.0 || pitch > 90.0) {
                    return Validation.fail("Pitch hors bornes (-90 à 90).");
                }
                params.put("npc_id", npcId);
                params.put("world", world);
                params.put("x", trimNumber(x));
                params.put("y", trimNumber(y));
                params.put("z", trimNumber(z));
                params.put("yaw", trimNumber(yaw));
                params.put("pitch", trimNumber(pitch));
            }
            case "dialogue.definition.create" -> {
                String key = trim(form.get("key")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(key).matches()) {
                    return Validation.fail("Clé de dialogue manquante ou invalide (minuscules, « . _ - »).");
                }
                String speaker = trim(form.get("speaker"));
                if (speaker.isEmpty() || speaker.length() > 128 || speaker.indexOf('\n') >= 0) {
                    return Validation.fail("Locuteur manquant, trop long, ou multi-ligne.");
                }
                String text = trim(form.get("text"));
                if (text.isEmpty() || text.length() > 512 || text.indexOf('\n') >= 0) {
                    return Validation.fail("Texte du nœud manquant, trop long (max 512), ou multi-ligne.");
                }
                // Palette de couleurs (issue #118) : on n'enrobe que si l'utilisateur n'a pas déjà
                // saisi du MiniMessage — son texte avancé reste intact.
                String color = trim(form.get("text_color")).toLowerCase(java.util.Locale.ROOT);
                if (!color.isEmpty()) {
                    if (!PALETTE_COLORS.contains(color)) {
                        return Validation.fail("Couleur de texte non reconnue.");
                    }
                    if (text.indexOf('<') < 0) {
                        text = "<" + color + ">" + text + "</" + color + ">";
                        if (text.length() > 512) {
                            return Validation.fail("Texte trop long une fois la couleur appliquée (max 512).");
                        }
                    }
                }
                params.put("key", key);
                params.put("speaker", speaker);
                params.put("text", text);
            }
            case "dialogue.node.create", "dialogue.node.update" -> {
                String dialogueId = normalizeDialogueId(trim(form.get("dialogue_id")));
                if (dialogueId == null) {
                    return Validation.fail("Identifiant de dialogue manquant ou invalide (« namespace:clé » ou une clé simple).");
                }
                String nodeId = trim(form.get("node_id")).toLowerCase(java.util.Locale.ROOT);
                if (!DIALOGUE_NODE_ID.matcher(nodeId).matches()) {
                    return Validation.fail("Identifiant de nœud manquant ou invalide (minuscules, chiffres, « _ - », max 64).");
                }
                String speaker = trim(form.get("speaker"));
                if (speaker.isEmpty() || speaker.length() > 128 || speaker.indexOf('\n') >= 0) {
                    return Validation.fail("Locuteur manquant, trop long, ou multi-ligne.");
                }
                String text = trim(form.get("text"));
                if (text.isEmpty() || text.length() > MAX_DIALOGUE_TEXT || text.indexOf('\n') >= 0) {
                    return Validation.fail("Texte du nœud manquant, trop long (max " + MAX_DIALOGUE_TEXT + "), ou multi-ligne.");
                }
                params.put("dialogue_id", dialogueId);
                params.put("node_id", nodeId);
                params.put("speaker", speaker);
                params.put("text", text);
            }
            case "dialogue.choice.add", "dialogue.choice.update" -> {
                String dialogueId = normalizeDialogueId(trim(form.get("dialogue_id")));
                if (dialogueId == null) {
                    return Validation.fail("Identifiant de dialogue manquant ou invalide.");
                }
                String nodeId = trim(form.get("node_id")).toLowerCase(java.util.Locale.ROOT);
                if (!DIALOGUE_NODE_ID.matcher(nodeId).matches()) {
                    return Validation.fail("Nœud source manquant ou invalide.");
                }
                String choiceText = trim(form.get("choice_text"));
                if (choiceText.isEmpty() || choiceText.length() > MAX_DIALOGUE_TEXT || choiceText.indexOf('\n') >= 0) {
                    return Validation.fail("Texte du choix manquant, trop long (max " + MAX_DIALOGUE_TEXT + "), ou multi-ligne.");
                }
                boolean close = "true".equals(trim(form.get("close")));
                String next = trim(form.get("next_node_id")).toLowerCase(java.util.Locale.ROOT);
                if (close) {
                    if (!next.isEmpty()) {
                        return Validation.fail("Un choix « fermeture » ne cible pas de nœud — laisser « nœud cible » vide.");
                    }
                } else {
                    if (!DIALOGUE_NODE_ID.matcher(next).matches()) {
                        return Validation.fail("Choisir un nœud cible OU cocher « termine le dialogue ».");
                    }
                }
                params.put("dialogue_id", dialogueId);
                params.put("node_id", nodeId);
                params.put("choice_text", choiceText);
                if (close) {
                    params.put("close", "true");
                } else {
                    params.put("next_node_id", next);
                }
                if ("dialogue.choice.update".equals(type)) {
                    Integer idx = parseIndex(trim(form.get("choice_index")));
                    if (idx == null) {
                        return Validation.fail("Index de choix manquant ou hors bornes.");
                    }
                    params.put("choice_index", idx.toString());
                }
            }
            case "dialogue.choice.delete" -> {
                String dialogueId = normalizeDialogueId(trim(form.get("dialogue_id")));
                if (dialogueId == null) {
                    return Validation.fail("Identifiant de dialogue manquant ou invalide.");
                }
                String nodeId = trim(form.get("node_id")).toLowerCase(java.util.Locale.ROOT);
                if (!DIALOGUE_NODE_ID.matcher(nodeId).matches()) {
                    return Validation.fail("Nœud source manquant ou invalide.");
                }
                Integer idx = parseIndex(trim(form.get("choice_index")));
                if (idx == null) {
                    return Validation.fail("Index de choix manquant ou hors bornes.");
                }
                params.put("dialogue_id", dialogueId);
                params.put("node_id", nodeId);
                params.put("choice_index", idx.toString());
            }
            case "content.export" -> {
                String family = orDefault(trim(form.get("family")).toLowerCase(java.util.Locale.ROOT), "all");
                if (!CONTENT_FAMILIES.contains(family)) {
                    return Validation.fail("Famille de contenu inconnue (all, quests, stories, dialogues, npcs).");
                }
                params.put("family", family);
                String rawIds = trim(form.get("ids"));
                if (!rawIds.isEmpty()) {
                    if ("all".equals(family)) {
                        return Validation.fail("Une sélection d'identifiants n'est possible que pour une famille précise.");
                    }
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    for (String piece : rawIds.split("[,\\s]+")) {
                        String t = piece.trim();
                        if (t.isEmpty()) {
                            continue;
                        }
                        if (!RESOURCE_ID.matcher(t).matches()) {
                            return Validation.fail("Identifiant à exporter invalide : « " + safe(t) + " ».");
                        }
                        ids.add(t);
                        if (ids.size() > MAX_EXPORT_IDS) {
                            return Validation.fail("Trop d'identifiants (max " + MAX_EXPORT_IDS + ") — exporter la famille entière.");
                        }
                    }
                    if (!ids.isEmpty()) {
                        params.put("ids", String.join(",", ids));
                    }
                }
            }
            case "player.resetnew.confirm" -> params.put("confirm", "true");
            case "player.ban" -> {
                String reason = trim(form.get("reason"));
                if (reason.isEmpty()) {
                    return Validation.fail("Une raison de bannissement est obligatoire.");
                }
                if (reason.length() > 256 || reason.indexOf('\n') >= 0) {
                    return Validation.fail("Raison trop longue (max 256) ou multi-ligne.");
                }
                params.put("reason", reason);
            }
            case "player.catalog" -> {
                String limit = trim(form.get("limit"));
                if (!limit.isEmpty()) {
                    int n;
                    try {
                        n = Integer.parseInt(limit);
                    } catch (NumberFormatException e) {
                        return Validation.fail("Limite invalide.");
                    }
                    if (n < 1 || n > 20_000) {
                        return Validation.fail("Limite hors bornes (1 à 20000).");
                    }
                    params.put("limit", Integer.toString(n));
                }
            }
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

    /** {@code namespace:clé} minuscule, ou {@code null} si invalide. Une clé simple reçoit {@code rpgquest:}. */
    private static String normalizeDialogueId(String raw) {
        String value = trim(raw).toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty() || !DIALOGUE_REF.matcher(value).matches()) {
            return null;
        }
        return value.contains(":") ? value : "rpgquest:" + value;
    }

    private static Integer parseIndex(String raw) {
        try {
            int v = Integer.parseInt(raw);
            return (v < 0 || v > MAX_DIALOGUE_CHOICE_INDEX) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parse un décimal fini, ou {@code null} (vide / non numérique / NaN / Infinity). */
    private static Double finite(String value) {
        String t = trim(value);
        if (t.isEmpty()) {
            return null;
        }
        try {
            double v = Double.parseDouble(t);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sérialise un décimal : entier si rond (« 64 »), sinon décimal court (« 125.5 »). */
    private static String trimNumber(double v) {
        return v == Math.rint(v) && Double.isFinite(v) ? Long.toString((long) v) : Double.toString(v);
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
