package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.content.pack.ContentFamily;
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
    /** Id de PNJ logique : fragment de clé RPGQuest, minuscules uniquement. */
    private static final Pattern NPC_ID = Pattern.compile("[a-z0-9._-]{1,64}");
    private static final Pattern DIALOGUE_REF = Pattern.compile("[a-z0-9._-]{1,64}(?::[a-z0-9._/-]{1,128})?");
    /** Id de nœud de dialogue : minuscules / chiffres / « _ - », borné. */
    private static final Pattern DIALOGUE_NODE_ID = Pattern.compile("[a-z0-9_][a-z0-9_-]{0,63}");
    private static final int MAX_DIALOGUE_CHOICE_INDEX = 199;
    private static final Pattern NPC_ROLE = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Nom de monde : jamais un chemin, jamais une commande — caractères sûrs, longueur bornée. */
    private static final Pattern WORLD_NAME = Pattern.compile("[A-Za-z0-9_./-]{1,64}");
    private static final int MAX_GIVE_AMOUNT = 64;
    private static final int MAX_DISPLAY_NAME = 128;
    private static final int MAX_DIALOGUE_TEXT = 512;
    /**
     * Taille maximale d'un content pack transporté dans un résultat d'action (issue #108). Sous le
     * plafond de 64 Kio de {@code POST /agent/v1/actions/{id}/result}, marge pour l'enveloppe JSON.
     */
    private static final int MAX_EXPORT_BYTES = 56 * 1024;

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
                case PLAYER_CATALOG -> playerCatalog(action);
                case QUEST_LIST -> questList(action);
                case STORY_LIST -> storyList(action);
                case ITEM_LIST -> itemList(action);
                case NPC_LIST -> npcList(action);
                case CONTENT_EXPORT -> contentExport(action);
                case NPC_CITIZENS_LIST -> npcCitizensList(action);
                case NPC_CITIZENS_LINK -> npcCitizensLink(action);
                case NPC_CITIZENS_CREATE -> npcCitizensCreate(action);
                case DIALOGUE_LIST -> dialogueList(action);
                case DIALOGUE_DEFINITION_CREATE -> dialogueDefinitionCreate(action);
                case DIALOGUE_NODE_CREATE -> dialogueNodeWrite(action, true);
                case DIALOGUE_NODE_UPDATE -> dialogueNodeWrite(action, false);
                case DIALOGUE_CHOICE_ADD -> dialogueChoiceAdd(action);
                case DIALOGUE_CHOICE_UPDATE -> dialogueChoiceUpdate(action);
                case DIALOGUE_CHOICE_DELETE -> dialogueChoiceDelete(action);
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
                case PLAYER_BAN -> playerBan(action);
                case PLAYER_UNBAN -> playerUnban(action);
                case NPC_DEFINITION_CREATE -> npcDefinitionWrite(action, true);
                case NPC_DEFINITION_UPDATE -> npcDefinitionWrite(action, false);
                case QUEST_GIVER_SET -> questGiverSet(action);
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

    /** {@code player.catalog} (#96) : annuaire complet (en ligne + hors ligne déjà venus). */
    private CompletableFuture<AgentActionOutcome> playerCatalog(AgentAction action) {
        int limit = parseAmount(firstNonBlank(action.param("limit"), action.param("max")), 0);
        // Cap de sécurité : une demande sans limite (ou <= 0) est bornée à 5000 pour ne jamais
        // renvoyer un payload démesuré sur un serveur à forte population — l'agent pose alors
        // truncated=true et PlugAdmin affiche un avertissement.
        int bounded = limit <= 0 ? 5_000 : Math.min(limit, 20_000);
        return actions.playerCatalog(bounded).thenApply(list -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            int online = 0;
            int banned = 0;
            for (AgentActions.PlayerCatalogEntry p : list) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", p.uuid());
                row.put("name", p.name());
                row.put("online", p.online());
                row.put("hasPlayedBefore", p.hasPlayedBefore());
                row.put("firstPlayed", p.firstPlayed());
                row.put("lastSeen", p.lastSeen());
                row.put("banned", p.banned());
                if (p.banReason() != null) {
                    row.put("banReason", p.banReason());
                }
                if (p.online()) {
                    online++;
                    row.put("world", p.world());
                    row.put("x", p.x());
                    row.put("y", p.y());
                    row.put("z", p.z());
                }
                if (p.banned()) {
                    banned++;
                }
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("players", rows);
            details.put("total", rows.size());
            details.put("online", online);
            details.put("offline", rows.size() - online);
            details.put("banned", banned);
            details.put("truncated", bounded > 0 && rows.size() >= bounded);
            return AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " joueur(s) connus (" + online + " en ligne, " + banned + " banni(s)).", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /** {@code player.ban} (#96) : bannit un joueur en ligne ou hors ligne. Raison obligatoire. */
    private CompletableFuture<AgentActionOutcome> playerBan(AgentAction action) {
        String reason = trimOrNull(firstNonBlank(action.param("reason"), action.param("motif")));
        if (reason == null || reason.length() > 256) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « reason » manquant ou trop long (max 256)."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.banPlayer(uuid, name, reason).thenApply(r -> toOutcome(action, r)));
    }

    /** {@code player.unban} (#96) : lève le bannissement d'un joueur. */
    private CompletableFuture<AgentActionOutcome> playerUnban(AgentAction action) {
        return withResolvedUuid(action, (uuid, name) ->
                actions.unbanPlayer(uuid, name).thenApply(r -> toOutcome(action, r)));
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

    /**
     * {@code content.export} (issue #108) — construit un content pack versionné. Lecture seule.
     * Paramètres : {@code family} ({@code all} par défaut, ou {@code quests|stories|dialogues|npcs})
     * et {@code ids} (liste séparée par des virgules, uniquement pour une famille précise). Le pack
     * est renvoyé sous {@code details.pack} ; borné à {@value #MAX_EXPORT_BYTES} octets pour rester
     * sous le plafond de 64 Kio du dépôt de résultat d'action (au-delà : échec lisible, exporter par
     * famille ou par élément — découpage/compression = évolution future, cf. #110).
     */
    private CompletableFuture<AgentActionOutcome> contentExport(AgentAction action) {
        String family = firstNonBlank(action.param("family"), action.param("scope"), "all")
                .trim().toLowerCase(java.util.Locale.ROOT);
        boolean known = "all".equals(family) || ContentFamily.fromWire(family).isPresent();
        if (!known) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « family » inconnu : « " + safe(family) + " » (attendu : all, "
                            + String.join(", ", ContentFamily.allWire()) + ")."));
        }

        List<String> ids = new ArrayList<>();
        String rawIds = firstNonBlank(action.param("ids"), action.param("id"));
        if (rawIds != null && !rawIds.isBlank()) {
            if ("all".equals(family)) {
                return done(AgentActionOutcome.rejected(action.id(),
                        "« ids » n'est pas accepté avec « family=all »."));
            }
            for (String piece : rawIds.split(",")) {
                String t = piece.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (!RESOURCE_ID.matcher(t).matches()) {
                    return done(AgentActionOutcome.rejected(action.id(),
                            "« ids » contient un identifiant invalide : « " + safe(t) + " »."));
                }
                ids.add(t);
                if (ids.size() > 500) {
                    return done(AgentActionOutcome.rejected(action.id(),
                            "Trop d'identifiants dans « ids » (max 500) — exporter la famille entière."));
                }
            }
        }

        try {
            AgentActions.ContentExportResult result = actions.exportContent(family, ids);
            if (!result.ok()) {
                return done(AgentActionOutcome.failed(action.id(), result.message()));
            }
            byte[] bytes = result.yaml().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > MAX_EXPORT_BYTES) {
                return done(AgentActionOutcome.failed(action.id(),
                        "Pack trop volumineux pour le transport actuel (" + bytes.length + " o > "
                                + MAX_EXPORT_BYTES + " o). Exporter par famille ou par élément."));
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("format", result.format());
            details.put("schemaVersion", result.schemaVersion());
            details.put("family", family);
            details.put("elements", result.elements());
            details.put("counts", result.counts());
            details.put("bytes", bytes.length);
            details.put("pack", result.yaml());
            return done(AgentActionOutcome.success(action.id(),
                    result.format() + " v" + result.schemaVersion(),
                    result.elements() + " élément(s) exporté(s) (" + bytes.length + " o).", details));
        } catch (RuntimeException e) {
            return done(AgentActionOutcome.failed(action.id(),
                    "Export impossible : " + e.getClass().getSimpleName()));
        }
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
                row.put("logicalDefinitionPresent", n.logicalDefinitionPresent());
                row.put("citizensBindingPresent", n.citizensBindingPresent());
                row.put("citizensNumericId", n.citizensNumericId());
                row.put("bindingCount", n.bindingCount());
                row.put("enabled", n.enabled());
                row.put("description", n.description());
                row.put("role", n.role());
                row.put("definedDialogueId", n.definedDialogueId());
                row.put("hasDialogue", n.hasDialogue());
                row.put("dialogueId", n.dialogueId());
                row.put("dialogueNodes", n.dialogueNodes());
                row.put("dialogueChoices", n.dialogueChoices());
                row.put("dialogueStartsQuests", n.dialogueStartsQuests());
                row.put("questsGiven", n.questsGiven());
                row.put("questsReferenced", n.questsReferenced());
                row.put("sources", n.sources());
                row.put("state", n.state());
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
            details.put("definedIds", view.definedIds());
            details.put("citizensAvailable", view.citizensAvailable());
            details.put("total", view.total());
            details.put("withDefinition", view.withDefinition());
            details.put("withoutDefinition", view.withoutDefinition());
            details.put("bound", view.bound());
            details.put("withWarnings", view.withWarnings());
            return AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " PNJ RPGQuest (" + view.withWarnings() + " avec avertissement).", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> npcCitizensList(AgentAction action) {
        return actions.citizensRoster().thenApply(view -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.CitizensNpcSummary c : view.citizens()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("numericId", c.numericId());
                row.put("uuid", c.uuid());
                row.put("name", c.name());
                row.put("linkedNpcId", c.linkedNpcId());
                row.put("availableForBinding", c.availableForBinding());
                row.put("spawned", c.spawned());
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("citizens", rows);
            details.put("citizensAvailable", view.citizensAvailable());
            details.put("total", view.total());
            details.put("available", view.available());
            details.put("linked", view.linked());
            return AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " PNJ Citizens (" + view.available() + " libre(s)).", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> npcCitizensLink(AgentAction action) {
        String npcId = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        String rawCitizens = firstNonBlank(action.param("citizens_id"), action.param("citizens"));
        Integer citizensId = parsePositiveInt(rawCitizens);
        if (citizensId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « citizens_id » manquant ou invalide (entier positif)."));
        }
        return actions.citizensLink(npcId, citizensId).thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 && value <= 10_000_000 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code npc.citizens.create} (#81 phase 2) : paramètres métier stricts, aucun spawn si un contrôle échoue. */
    private CompletableFuture<AgentActionOutcome> npcCitizensCreate(AgentAction action) {
        String npcId = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        String world = firstNonBlank(action.param("world"));
        if (world == null || !WORLD_NAME.matcher(world).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « world » manquant ou invalide."));
        }
        Double x = parseFinite(action.param("x"));
        Double y = parseFinite(action.param("y"));
        Double z = parseFinite(action.param("z"));
        if (x == null || y == null || z == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètres « x » / « y » / « z » manquants ou non finis."));
        }
        Double yaw = action.param("yaw") == null || action.param("yaw").isBlank() ? 0.0 : parseFinite(action.param("yaw"));
        Double pitch = action.param("pitch") == null || action.param("pitch").isBlank()
                ? 0.0 : parseFinite(action.param("pitch"));
        if (yaw == null || pitch == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « yaw » / « pitch » non fini."));
        }
        return actions.citizensCreate(npcId, world, x, y, z, yaw.floatValue(), pitch.floatValue())
                .thenApply(r -> {
                    // AgentActionOutcome copie les détails via Map.copyOf -> aucune valeur null.
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("code", r.code());
                    details.put("npc_id", r.npcId());
                    details.put("citizens_id", r.citizensNumericId() == null ? -1 : r.citizensNumericId());
                    details.put("world", world);
                    details.put("rolled_back", r.rolledBack());
                    details.put("effects", r.effects());
                    if (r.ok()) {
                        return AgentActionOutcome.success(action.id(),
                                r.citizensNumericId() == null ? r.code() : String.valueOf(r.citizensNumericId()),
                                r.message(), details);
                    }
                    return new AgentActionOutcome(action.id(), AgentActionOutcome.FAILED, r.code(), r.message(),
                            details, java.time.Instant.now());
                })
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private static Double parseFinite(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- dialogue.list / dialogue.definition.create (V1 /dialogues) ---------------------------

    private CompletableFuture<AgentActionOutcome> dialogueList(AgentAction action) {
        return actions.dialogueDefinitions().thenApply(view -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.DialogueSummary d : view.dialogues()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", d.id());
                row.put("key", d.key());
                row.put("startNodeId", d.startNodeId());
                row.put("linkedNpcIds", d.linkedNpcIds());
                row.put("nodeCount", d.nodeCount());
                row.put("choiceCount", d.choiceCount());
                row.put("referencedQuestIds", d.referencedQuestIds());
                row.put("startsQuestIds", d.startsQuestIds());
                row.put("warnings", warningMaps(d.warnings()));
                List<Map<String, Object>> nodes = new ArrayList<>();
                for (AgentActions.DialogueNodeSummary n : d.nodes()) {
                    Map<String, Object> nm = new LinkedHashMap<>();
                    nm.put("id", n.id());
                    nm.put("speaker", n.speaker());
                    nm.put("text", n.text());
                    nm.put("start", n.start());
                    nm.put("reachable", n.reachable());
                    List<Map<String, Object>> choices = new ArrayList<>();
                    for (AgentActions.DialogueChoiceSummary c : n.choices()) {
                        Map<String, Object> cm = new LinkedHashMap<>();
                        cm.put("text", c.text());
                        cm.put("nextNodeId", c.nextNodeId() == null ? "" : c.nextNodeId());
                        cm.put("actions", actionMaps(c.actions()));
                        cm.put("conditions", conditionMaps(c.conditions()));
                        choices.add(cm);
                    }
                    nm.put("choices", choices);
                    nodes.add(nm);
                }
                row.put("nodes", nodes);
                rows.add(row);
            }
            List<Map<String, Object>> loadIssues = new ArrayList<>();
            for (AgentActions.DialogueLoadIssueSummary li : view.loadIssues()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("file", li.file());
                m.put("message", li.message());
                loadIssues.add(m);
            }
            List<Map<String, Object>> missing = new ArrayList<>();
            for (AgentActions.DialogueMissingDeclared m : view.declaredButMissing()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("npcId", m.npcId());
                mm.put("dialogueId", m.dialogueId());
                missing.add(mm);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("dialogues", rows);
            details.put("loadIssues", loadIssues);
            details.put("declaredButMissing", missing);
            details.put("total", view.total());
            details.put("withWarnings", view.withWarnings());
            details.put("nodeTotal", view.nodeTotal());
            return AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " dialogue(s) (" + view.withWarnings() + " avec avertissement, "
                            + view.loadIssues().size() + " fichier(s) rejeté(s)).", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private static List<Map<String, Object>> warningMaps(List<AgentActions.DialogueWarning> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentActions.DialogueWarning w : warnings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", w.code());
            m.put("severity", w.severity());
            m.put("message", w.message());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> actionMaps(List<AgentActions.DialogueActionSummary> actions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentActions.DialogueActionSummary a : actions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", a.kind());
            m.put("target", a.target() == null ? "" : a.target());
            m.put("value", a.value() == null ? "" : a.value());
            m.put("raw", a.raw());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> conditionMaps(List<AgentActions.DialogueConditionSummary> conditions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentActions.DialogueConditionSummary c : conditions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c.kind());
            m.put("target", c.target() == null ? "" : c.target());
            m.put("value", c.value() == null ? "" : c.value());
            m.put("raw", c.raw());
            m.put("negated", c.negated());
            out.add(m);
        }
        return out;
    }

    private CompletableFuture<AgentActionOutcome> dialogueDefinitionCreate(AgentAction action) {
        String key = firstNonBlank(action.param("key"), action.param("id"), action.param("npc_id"));
        if (key == null || !NPC_ID.matcher(key.toLowerCase(java.util.Locale.ROOT)).matches()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « key » manquant ou invalide (minuscules, « . _ - »)."));
        }
        String speaker = trimOrNull(action.param("speaker"));
        if (speaker == null || speaker.length() > MAX_DISPLAY_NAME || speaker.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « speaker » manquant, trop long, ou multi-ligne."));
        }
        String text = trimOrNull(action.param("text"));
        if (text == null || text.length() > MAX_DIALOGUE_TEXT || text.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « text » manquant, trop long (max " + MAX_DIALOGUE_TEXT + "), ou multi-ligne."));
        }
        return actions.dialogueDefinitionCreate(key.toLowerCase(java.util.Locale.ROOT), speaker, text)
                .thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    // ---- dialogue.node.* / dialogue.choice.* (éditeur guidé — issue #82 phase 1) --------------

    private CompletableFuture<AgentActionOutcome> dialogueNodeWrite(AgentAction action, boolean create) {
        String dialogueId = dialogueId(action);
        if (dialogueId == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « dialogue_id » manquant ou invalide."));
        }
        String nodeId = trimOrNull(action.param("node_id"));
        if (nodeId == null || !DIALOGUE_NODE_ID.matcher(nodeId.toLowerCase(java.util.Locale.ROOT)).matches()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « node_id » manquant ou invalide (minuscules, chiffres, « _ - », max 64)."));
        }
        String speaker = trimOrNull(action.param("speaker"));
        if (speaker == null || speaker.length() > MAX_DISPLAY_NAME || speaker.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « speaker » manquant, trop long, ou multi-ligne."));
        }
        String text = trimOrNull(action.param("text"));
        if (text == null || text.length() > MAX_DIALOGUE_TEXT || text.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « text » manquant, trop long (max " + MAX_DIALOGUE_TEXT + "), ou multi-ligne."));
        }
        String id = nodeId.toLowerCase(java.util.Locale.ROOT);
        CompletableFuture<AgentActions.MutationResult> future = create
                ? actions.dialogueNodeCreate(dialogueId, id, speaker, text)
                : actions.dialogueNodeUpdate(dialogueId, id, speaker, text);
        return future.thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> dialogueChoiceAdd(AgentAction action) {
        String dialogueId = dialogueId(action);
        if (dialogueId == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « dialogue_id » manquant ou invalide."));
        }
        String nodeId = dialogueNodeRef(action.param("node_id"));
        if (nodeId == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « node_id » manquant ou invalide."));
        }
        String choiceText = trimOrNull(action.param("choice_text"));
        if (choiceText == null || choiceText.length() > MAX_DIALOGUE_TEXT || choiceText.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « choice_text » manquant, trop long (max " + MAX_DIALOGUE_TEXT + "), ou multi-ligne."));
        }
        boolean close = isTrue(action.param("close"));
        String next = close ? null : dialogueNodeRef(action.param("next_node_id"));
        if (!close && next == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Un choix simple redirige vers un nœud (« next_node_id ») OU ferme le dialogue (« close=true »)."));
        }
        return actions.dialogueChoiceAdd(dialogueId, nodeId, choiceText, next, close)
                .thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> dialogueChoiceUpdate(AgentAction action) {
        String dialogueId = dialogueId(action);
        if (dialogueId == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « dialogue_id » manquant ou invalide."));
        }
        String nodeId = dialogueNodeRef(action.param("node_id"));
        if (nodeId == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « node_id » manquant ou invalide."));
        }
        int index = parseAmount(action.param("choice_index"), -1);
        if (index < 0 || index > MAX_DIALOGUE_CHOICE_INDEX) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « choice_index » manquant ou hors bornes."));
        }
        String choiceText = trimOrNull(action.param("choice_text"));
        if (choiceText == null || choiceText.length() > MAX_DIALOGUE_TEXT || choiceText.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « choice_text » manquant, trop long (max " + MAX_DIALOGUE_TEXT + "), ou multi-ligne."));
        }
        boolean close = isTrue(action.param("close"));
        String next = close ? null : dialogueNodeRef(action.param("next_node_id"));
        if (!close && next == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Un choix simple redirige vers un nœud (« next_node_id ») OU ferme le dialogue (« close=true »)."));
        }
        return actions.dialogueChoiceUpdate(dialogueId, nodeId, index, choiceText, next, close)
                .thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> dialogueChoiceDelete(AgentAction action) {
        String dialogueId = dialogueId(action);
        if (dialogueId == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « dialogue_id » manquant ou invalide."));
        }
        String nodeId = dialogueNodeRef(action.param("node_id"));
        if (nodeId == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « node_id » manquant ou invalide."));
        }
        int index = parseAmount(action.param("choice_index"), -1);
        if (index < 0 || index > MAX_DIALOGUE_CHOICE_INDEX) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « choice_index » manquant ou hors bornes."));
        }
        return actions.dialogueChoiceDelete(dialogueId, nodeId, index)
                .thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /** {@code dialogue_id} normalisé en {@code namespace:key} minuscule, ou {@code null} si invalide. */
    private static String dialogueId(AgentAction action) {
        String raw = firstNonBlank(action.param("dialogue_id"), action.param("dialogue"), action.param("id"));
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!DIALOGUE_REF.matcher(value).matches()) {
            return null;
        }
        return value.contains(":") ? value : "rpgquest:" + value;
    }

    /** Référence de nœud existant (jamais créé ici) : minuscules, borné, ou {@code null}. */
    private static String dialogueNodeRef(String raw) {
        String value = trimOrNull(raw);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(java.util.Locale.ROOT);
        return DIALOGUE_NODE_ID.matcher(value).matches() ? value : null;
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

    // ---- Écritures de contenu PNJ (V2 déclarative) -----------------------------------

    private CompletableFuture<AgentActionOutcome> npcDefinitionWrite(AgentAction action, boolean create) {
        String id = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (id == null || !NPC_ID.matcher(id).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        String displayName = trimOrNull(action.param("display_name"));
        if (displayName == null || displayName.length() > MAX_DISPLAY_NAME || displayName.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « display_name » manquant, trop long, ou multi-ligne."));
        }
        String dialogueId = trimOrNull(action.param("dialogue_id"));
        if (dialogueId != null && !DIALOGUE_REF.matcher(dialogueId.toLowerCase(java.util.Locale.ROOT)).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « dialogue_id » invalide."));
        }
        String role = trimOrNull(action.param("role"));
        if (role != null && !NPC_ROLE.matcher(role.toLowerCase(java.util.Locale.ROOT)).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « role » invalide."));
        }
        boolean enabled = !"false".equalsIgnoreCase(trimOrNull(action.param("enabled")));
        CompletableFuture<AgentActions.MutationResult> future = create
                ? actions.npcDefinitionCreate(id, displayName, dialogueId, role, enabled)
                : actions.npcDefinitionUpdate(id, displayName, dialogueId, role, enabled);
        return future.thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> questGiverSet(AgentAction action) {
        String questId = firstNonBlank(action.param("quest_id"), action.param("quest"));
        if (questId == null || !RESOURCE_ID.matcher(questId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « quest_id » manquant ou invalide."));
        }
        String npcId = firstNonBlank(action.param("npc_id"), action.param("giver"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        return actions.questGiverSet(questId, npcId).thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private static String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
