package com.lodygames.rpgquest.web.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Exécute une {@link AgentAction} reçue de PlugAdmin en appelant les <strong>services métier</strong>
 * du plugin via {@link AgentActions} — jamais une commande texte {@code /rpgadmin …}, jamais un
 * {@code dispatchCommand} (issue #51 + outillage Control Panel).
 *
 * <p>Discipline :</p>
 * <ul>
 *   <li>type absent de {@link AgentActionType} → {@link AgentActionOutcome#REJECTED} sans effet ;</li>
 *   <li>paramètres invalides → {@code REJECTED} ;</li>
 *   <li>cible/données introuvables ou précondition non remplie → {@code FAILED} lisible ;</li>
 *   <li>aucune exception ne remonte.</li>
 * </ul>
 *
 * <p>Toutes les opérations sont asynchrones ; les mutations sont replacées sur le thread principal
 * par {@link BukkitAgentActions}, jamais ici.</p>
 */
public final class AgentActionExecutor {

    /** Clés de variable acceptées (ex. {@code CLAIM_TIER_1}) — bornées, jamais du texte libre. */
    private static final Pattern VARIABLE_KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{1,128}");
    /** Id de quête / story / objet : namespace optionnel + clé, caractères sûrs uniquement. */
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-zA-Z0-9_.:\\-/]{1,128}");
    private static final Pattern STORY_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final int MAX_GIVE_AMOUNT = 64;

    private final PlayerDirectory players;
    private final PlayerVariables variables;
    private final AgentActions actions;

    public AgentActionExecutor(PlayerDirectory players, PlayerVariables variables, AgentActions actions) {
        this.players = players;
        this.variables = variables;
        this.actions = actions;
    }

    public CompletableFuture<AgentActionOutcome> execute(AgentAction action) {
        if (action == null || action.id() == null || action.id().isBlank()) {
            return done(AgentActionOutcome.rejected("<sans-id>", "Action sans identifiant."));
        }
        Optional<AgentActionType> type = AgentActionType.fromWire(action.type());
        if (type.isEmpty()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Type d'action non whitelisté : « " + safe(action.type()) + " »."));
        }
        try {
            return switch (type.get()) {
                case PLAYER_VARIABLE_GET -> playerVariableGet(action);
                case PLAYER_LIST -> playerList(action);
                case QUEST_LIST -> questList(action);
                case STORY_LIST -> storyList(action);
                case ITEM_LIST -> itemList(action);
                case NPC_LIST -> npcList(action);
                case QUEST_PLAYER_STATUS -> questPlayerStatus(action);
                case STORY_PLAYER_STATUS -> storyPlayerStatus(action);
                case PLAYER_RESETNEW_PREVIEW -> resetPreview(action);
                case PLAYER_ITEM_GIVE -> itemGive(action);
                case QUEST_START -> questStart(action);
                case QUEST_COMPLETE -> questMutation(action, AgentActionType.QUEST_COMPLETE);
                case QUEST_RESET -> questMutation(action, AgentActionType.QUEST_RESET);
                case STORY_ADVANCE -> storyMutation(action, AgentActionType.STORY_ADVANCE);
                case STORY_COMPLETE -> storyMutation(action, AgentActionType.STORY_COMPLETE);
                case PLAYER_VARIABLE_SET -> variableSet(action);
                case PLAYER_RESETNEW_CONFIRM -> resetConfirm(action);
            };
        } catch (RuntimeException e) {
            return done(AgentActionOutcome.failed(action.id(), "Échec interne : " + e.getClass().getSimpleName()));
        }
    }

    // ---- player.variable.get ------------------------------------------------------------

    private CompletableFuture<AgentActionOutcome> playerVariableGet(AgentAction action) {
        String key = firstNonBlank(action.param("key"), action.param("variable"));
        if (key == null || !VARIABLE_KEY.matcher(key).matches()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « key » manquant ou invalide (attendu : " + VARIABLE_KEY.pattern() + ")."));
        }
        return withPlayer(action, (uuid, name) -> variables.get(uuid, key).thenApply(opt -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("player_uuid", uuid.toString());
            details.put("player_name", name);
            details.put("key", key);
            details.put("present", opt.isPresent());
            String value = opt.orElse(null);
            String message = opt.isPresent()
                    ? name + " : " + key + " = " + value
                    : name + " : " + key + " absente (équivaut à non définie).";
            return AgentActionOutcome.success(action.id(), value, message, details);
        }));
    }

    // ---- Lectures sans joueur ---------------------------------------------------------

    private CompletableFuture<AgentActionOutcome> playerList(AgentAction action) {
        return actions.onlinePlayers().thenApply(list -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.PlayerSummary p : list) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", p.uuid());
                row.put("name", p.name());
                row.put("world", p.world());
                row.put("x", p.x());
                row.put("y", p.y());
                row.put("z", p.z());
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("players", rows);
            return AgentActionOutcome.success(action.id(), String.valueOf(list.size()),
                    list.size() + " joueur(s) connecté(s).", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> questList(AgentAction action) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.QuestSummary q : actions.questDefinitions()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", q.id());
                row.put("title", q.title());
                row.put("category", q.category());
                row.put("repeatable", q.repeatable());
                row.put("prerequisites", q.prerequisites());
                // #75 : PNJ donneur, seulement s'il est déclaré (giver: optionnel dans le YAML).
                if (q.giverId() != null && !q.giverId().isBlank()) {
                    row.put("giverId", q.giverId());
                    if (q.giverName() != null && !q.giverName().isBlank()) {
                        row.put("giverName", q.giverName());
                    }
                }
                List<Map<String, Object>> steps = new ArrayList<>();
                for (AgentActions.QuestStepSummary s : q.steps()) {
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("id", s.id());
                    step.put("objectives", s.objectives()); // legacy (chaînes) — compat agent déployé
                    step.put("objectiveDetails", objectiveRows(s.objectiveDetails())); // #78 : structuré
                    steps.add(step);
                }
                row.put("steps", steps);
                row.put("rewards", q.rewards()); // legacy (chaînes) — compat agent déployé
                row.put("rewardDetails", rewardRows(q.rewardDetails())); // #78 : structuré
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("quests", rows);
            return done(AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " quête(s) chargée(s).", details));
        } catch (RuntimeException e) {
            return done(AgentActionOutcome.failed(action.id(), "Échec : " + e.getClass().getSimpleName()));
        }
    }

    /** Objectifs structurés (#78) en lignes JSON sérialisables (Map imbriquées, jamais un record). */
    private static List<Map<String, Object>> objectiveRows(List<AgentActions.ObjectiveSummary> objectives) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentActions.ObjectiveSummary o : objectives) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", o.kind());
            m.put("target", o.target());
            m.put("amount", o.amount());
            m.put("raw", o.raw());
            out.add(m);
        }
        return out;
    }

    /** Récompenses structurées (#78) en lignes JSON — {@code command} jamais tronquée. */
    private static List<Map<String, Object>> rewardRows(List<AgentActions.RewardSummary> rewards) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentActions.RewardSummary r : rewards) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", r.kind());
            m.put("amount", r.amount());
            m.put("target", r.target());
            m.put("value", r.value());
            m.put("command", r.command());
            m.put("raw", r.raw());
            out.add(m);
        }
        return out;
    }

    private CompletableFuture<AgentActionOutcome> storyList(AgentAction action) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.StorySummary s : actions.storyDefinitions()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", s.id());
                row.put("title", s.title());
                row.put("stepQuestIds", s.stepQuestIds());
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("stories", rows);
            return done(AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " story(s) chargée(s).", details));
        } catch (RuntimeException e) {
            return done(AgentActionOutcome.failed(action.id(), "Échec : " + e.getClass().getSimpleName()));
        }
    }

    private CompletableFuture<AgentActionOutcome> itemList(AgentAction action) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.ItemSummary i : actions.itemDefinitions()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", i.id());
                row.put("displayName", i.displayName());
                row.put("type", i.type());
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("items", rows);
            return done(AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " objet(s) personnalisé(s).", details));
        } catch (RuntimeException e) {
            return done(AgentActionOutcome.failed(action.id(), "Échec : " + e.getClass().getSimpleName()));
        }
    }

    private CompletableFuture<AgentActionOutcome> npcList(AgentAction action) {
        return actions.npcDefinitions().thenApply(view -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.NpcSummary n : view.npcs()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", n.id());
                row.put("displayName", n.displayName());
                row.put("citizensNumericId", n.citizensNumericId());
                row.put("bindingCount", n.bindingCount());
                row.put("bound", n.bound());
                row.put("hasDialogue", n.hasDialogue());
                row.put("dialogueId", n.dialogueId());
                row.put("dialogueNodes", n.dialogueNodes());
                row.put("dialogueChoices", n.dialogueChoices());
                row.put("dialogueStartsQuests", n.dialogueStartsQuests());
                row.put("questsGiven", n.questsGiven());
                row.put("questsReferenced", n.questsReferenced());
                row.put("sources", n.sources());
                List<Map<String, Object>> warnings = new ArrayList<>();
                for (AgentActions.NpcWarning w : n.warnings()) {
                    Map<String, Object> wm = new LinkedHashMap<>();
                    wm.put("code", w.code());
                    wm.put("severity", w.severity());
                    wm.put("message", w.message());
                    warnings.add(wm);
                }
                row.put("warnings", warnings);
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("npcs", rows);
            details.put("canonicalIds", view.canonicalIds());
            details.put("citizensAvailable", view.citizensAvailable());
            details.put("total", view.total());
            details.put("bound", view.bound());
            details.put("unbound", view.unbound());
            details.put("withWarnings", view.withWarnings());
            return AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " PNJ RPGQuest (" + view.withWarnings() + " avec avertissement).", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    // ---- Lectures avec joueur -------------------------------------------------------

    private CompletableFuture<AgentActionOutcome> questPlayerStatus(AgentAction action) {
        return withPlayer(action, (uuid, name) -> actions.questStatus(uuid).thenApply(list -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.QuestPlayerState q : list) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("questId", q.questId());
                row.put("title", q.title());
                row.put("state", q.state());
                row.put("currentStepId", q.currentStepId());
                List<Map<String, Object>> objectives = new ArrayList<>();
                for (AgentActions.ObjectiveState o : q.objectives()) {
                    Map<String, Object> obj = new LinkedHashMap<>();
                    obj.put("description", o.description());
                    obj.put("current", o.current());
                    obj.put("required", o.required());
                    objectives.add(obj);
                }
                row.put("objectives", objectives);
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("player_uuid", uuid.toString());
            details.put("player_name", name);
            details.put("quests", rows);
            return AgentActionOutcome.success(action.id(), name, rows.size() + " quête(s) pour " + name + ".", details);
        }));
    }

    private CompletableFuture<AgentActionOutcome> storyPlayerStatus(AgentAction action) {
        return withPlayer(action, (uuid, name) -> actions.storyStatus(uuid).thenApply(list -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.StoryPlayerState s : list) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("storyId", s.storyId());
                row.put("title", s.title());
                row.put("state", s.state());
                row.put("currentStep", s.currentStep());
                row.put("totalSteps", s.totalSteps());
                row.put("currentQuestId", s.currentQuestId());
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("player_uuid", uuid.toString());
            details.put("player_name", name);
            details.put("stories", rows);
            return AgentActionOutcome.success(action.id(), name, rows.size() + " story(s) pour " + name + ".", details);
        }));
    }

    private CompletableFuture<AgentActionOutcome> resetPreview(AgentAction action) {
        return withPlayer(action, (uuid, name) -> actions.resetPreview(uuid).thenApply(preview -> {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (AgentActions.ResetPreviewLine l : preview.lines()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", l.label());
                row.put("count", l.count());
                row.put("detail", l.detail());
                lines.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("player_uuid", uuid.toString());
            details.put("player_name", name);
            details.put("online", preview.online());
            details.put("lines", lines);
            return AgentActionOutcome.success(action.id(), name,
                    "Aperçu du reset « nouveau joueur » de " + name + " (aucune écriture).", details);
        }));
    }

    // ---- Mutations ----------------------------------------------------------------

    private CompletableFuture<AgentActionOutcome> itemGive(AgentAction action) {
        String itemId = firstNonBlank(action.param("item_id"), action.param("item"));
        if (itemId == null || !RESOURCE_ID.matcher(itemId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « item_id » manquant ou invalide."));
        }
        int amount = parseAmount(action.param("amount"), 1);
        if (amount < 1 || amount > MAX_GIVE_AMOUNT) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « amount » hors bornes (1 à " + MAX_GIVE_AMOUNT + ")."));
        }
        return withPlayer(action, (uuid, name) -> actions.giveItem(uuid, itemId, amount).thenApply(r -> toOutcome(action, r)));
    }

    private CompletableFuture<AgentActionOutcome> questStart(AgentAction action) {
        String questId = firstNonBlank(action.param("quest_id"), action.param("quest"));
        if (questId == null || !RESOURCE_ID.matcher(questId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « quest_id » manquant ou invalide."));
        }
        boolean force = isTrue(action.param("force"));
        return withPlayer(action, (uuid, name) ->
                actions.questStart(uuid, questId, force).thenApply(r -> toOutcome(action, r)));
    }

    private CompletableFuture<AgentActionOutcome> questMutation(AgentAction action, AgentActionType type) {
        String questId = firstNonBlank(action.param("quest_id"), action.param("quest"));
        if (questId == null || !RESOURCE_ID.matcher(questId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « quest_id » manquant ou invalide."));
        }
        if (type == AgentActionType.QUEST_RESET) {
            // quest reset fonctionne hors ligne (clé UUID).
            return withResolvedUuid(action, (uuid, name) ->
                    actions.questReset(uuid, questId).thenApply(r -> toOutcome(action, r)));
        }
        return withPlayer(action, (uuid, name) ->
                actions.questComplete(uuid, questId).thenApply(r -> toOutcome(action, r)));
    }

    private CompletableFuture<AgentActionOutcome> storyMutation(AgentAction action, AgentActionType type) {
        String storyId = firstNonBlank(action.param("story_id"), action.param("story"));
        if (storyId == null || !STORY_ID.matcher(storyId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « story_id » manquant ou invalide."));
        }
        return withPlayer(action, (uuid, name) -> {
            CompletableFuture<AgentActions.MutationResult> future = type == AgentActionType.STORY_ADVANCE
                    ? actions.storyAdvance(uuid, storyId)
                    : actions.storyComplete(uuid, storyId);
            return future.thenApply(r -> toOutcome(action, r));
        });
    }

    private CompletableFuture<AgentActionOutcome> variableSet(AgentAction action) {
        String key = firstNonBlank(action.param("key"), action.param("variable"));
        String value = action.param("value");
        if (key == null || !VARIABLE_KEY.matcher(key).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « key » manquant ou invalide."));
        }
        if (value == null || value.length() > 256) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « value » manquant ou trop long."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.variableSet(uuid, key, value).thenApply(r -> toOutcome(action, r)));
    }

    private CompletableFuture<AgentActionOutcome> resetConfirm(AgentAction action) {
        if (!isTrue(action.param("confirm"))) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Reset « nouveau joueur » : paramètre « confirm=true » obligatoire (garde-fou)."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.resetConfirm(uuid, name).thenApply(r -> toOutcome(action, r)));
    }

    // ---- Résolution joueur + helpers --------------------------------------------

    private interface WithPlayer {
        CompletableFuture<AgentActionOutcome> run(UUID uuid, String name);
    }

    /** Résout la cible (en ligne ou déjà connectée par le passé) puis exécute {@code body}. */
    private CompletableFuture<AgentActionOutcome> withResolvedUuid(AgentAction action, WithPlayer body) {
        String playerRef = firstNonBlank(action.param("player_uuid"), action.param("player"), action.param("name"));
        if (playerRef == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « player » ou « player_uuid » manquant."));
        }
        return players.resolve(playerRef).thenCompose(resolved -> {
            if (resolved.isEmpty()) {
                return done(AgentActionOutcome.failed(action.id(),
                        "Joueur inconnu (jamais connecté) : « " + safe(playerRef) + " »."));
            }
            return body.run(resolved.get().uuid(), resolved.get().name());
        }).exceptionally(error -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(error)));
    }

    /** Comme {@link #withResolvedUuid} — même signature, nom explicite pour les actions « joueur en ligne requis ». */
    private CompletableFuture<AgentActionOutcome> withPlayer(AgentAction action, WithPlayer body) {
        return withResolvedUuid(action, body);
    }

    private AgentActionOutcome toOutcome(AgentAction action, AgentActions.MutationResult r) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", r.code());
        details.put("effects", r.effects());
        if (r.ok()) {
            return AgentActionOutcome.success(action.id(), r.code(), r.message(), details);
        }
        // Précondition métier non remplie (offline, déjà terminé, id inconnu…) : échec lisible.
        return new AgentActionOutcome(action.id(), AgentActionOutcome.FAILED, r.code(), r.message(),
                details, java.time.Instant.now());
    }

    // ---- utilitaires -------------------------------------------------------------------

    private static CompletableFuture<AgentActionOutcome> done(AgentActionOutcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    private static int parseAmount(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isTrue(String raw) {
        return raw != null && ("true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim())
                || "yes".equalsIgnoreCase(raw.trim()) || "force".equalsIgnoreCase(raw.trim()));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String safe(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) + "…" : trimmed;
    }

    private static String rootName(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName();
    }
}
