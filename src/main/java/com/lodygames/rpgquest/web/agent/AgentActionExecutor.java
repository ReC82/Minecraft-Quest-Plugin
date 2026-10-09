package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.content.pack.ContentFamily;
import com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor;
import com.lodygames.rpgquest.dialogue.model.ActionType;
import com.lodygames.rpgquest.player.PlayerResetService.ResetScope;
import com.lodygames.rpgquest.quest.model.QuestState;
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
    /** Clé courte OU identifiant namespacé complet ({@code NamespacedKey#asString()}, ex.
     * {@code rpgquest:creeper_pig}) -- le « : » doit être accepté (même bornes que côté panel). */
    private static final Pattern MOB_ID = Pattern.compile("[a-z0-9._-]{1,64}(?::[a-z0-9._/-]{1,64})?");
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
                case BUILDING_SITE_LIST -> buildingSiteList(action);
                case BUILDING_SITE_RENAME -> buildingSiteRename(action);
                case BUILDING_SITE_DESCRIBE -> buildingSiteDescribe(action);
                case BUILDING_SITE_FACING -> buildingSiteFacing(action);
                case BUILDING_SITE_DELETE -> buildingSiteDelete(action);
                case BUILDING_DEFINITION_LIST -> buildingDefinitionList(action);
                case BUILDING_PLACEMENT_PREVIEW -> buildingPlacementPreview(action);
                case BUILDING_PLACEMENT_PLACE -> buildingPlacementPlace(action);
                case BUILDING_PLACEMENT_ROLLBACK -> buildingPlacementRollback(action);
                case BUILDING_PLACEMENT_RETARGET_PREVIEW -> buildingRetargetPreview(action);
                case BUILDING_PLACEMENT_REORIENT -> buildingReorient(action);
                case BUILDING_PLACEMENT_REPLACE -> buildingReplace(action);
                case BUILDING_PLACEMENT_HISTORY -> buildingHistory(action);
                case CONTENT_EXPORT -> contentExport(action);
                case NPC_CITIZENS_LIST -> npcCitizensList(action);
                case NPC_CITIZENS_LINK -> npcCitizensLink(action);
                case NPC_CITIZENS_UNLINK -> npcCitizensUnlink(action);
                case NPC_CITIZENS_DELETE -> npcCitizensDelete(action);
                case NPC_CITIZENS_CREATE -> npcCitizensCreate(action);
                case NPC_CITIZENS_PROVISION -> npcCitizensProvision(action);
                case NPC_CITIZENS_MOVE -> npcCitizensMove(action);
                case NPC_CITIZENS_RENAME -> npcCitizensRename(action);
                case NPC_CITIZENS_SKIN -> npcCitizensSkin(action);
                case NPC_CITIZENS_LOOKCLOSE -> npcCitizensLookClose(action);
                case NPC_CITIZENS_WANDER -> npcCitizensWander(action);
                case DIALOGUE_LIST -> dialogueList(action);
                case DIALOGUE_DEFINITION_CREATE -> dialogueDefinitionCreate(action);
                case DIALOGUE_NODE_CREATE -> dialogueNodeWrite(action, true);
                case DIALOGUE_NODE_UPDATE -> dialogueNodeWrite(action, false);
                case DIALOGUE_CHOICE_ADD -> dialogueChoiceAdd(action);
                case DIALOGUE_CHOICE_UPDATE -> dialogueChoiceUpdate(action);
                case DIALOGUE_CHOICE_DELETE -> dialogueChoiceDelete(action);
                case QUEST_PLAYER_STATUS -> questPlayerStatus(action);
                case STORY_PLAYER_STATUS -> storyPlayerStatus(action);
                case PLAYER_RESETNEW_PREVIEW -> resetPreview(action, ResetScope.PROGRESSION);
                case PLAYER_RESETFULL_PREVIEW -> resetPreview(action, ResetScope.NEW_PLAYER);
                case TRAVEL_CATALOG -> travelCatalog(action);
                case MOB_LIST -> mobList(action);
                case MOB_CATALOGS -> mobCatalogs(action);
                case ITEM_CATALOGS -> itemCatalogs(action);
                case CONTENT_DEFINITION_DELETE -> contentDefinitionDelete(action);
                case MOB_DEFINITION_CREATE -> mobDefinitionWrite(action, true);
                case MOB_DEFINITION_UPDATE -> mobDefinitionWrite(action, false);
                case MOB_DEFINITION_TOGGLE -> mobDefinitionToggle(action);
                case MOB_SPAWN_SETTINGS_SET -> mobSpawnSettingsSet(action);
                case MOB_TEST_SPAWN -> mobTestSpawn(action);
                case MOB_TEST_CLEAR -> mobTestClear(action);
                case SERVER_ANNOUNCE -> serverAnnounce(action);
                case SERVER_LOGS_TAIL -> serverLogsTail(action);
                case CONTENT_RELOAD_PREVIEW -> contentReload(action, false);
                case CONTENT_DEV_STATE -> contentDevState(action);
                case CONTENT_DEV_READ -> contentDevRead(action);
                case CONTENT_PUBLISH -> contentPublish(action);
                case CONTENT_PUBLISH_ROLLBACK -> contentPublishRollback(action);
                case CONTENT_RELOAD -> contentReload(action, true);
                case PLAYER_OP -> playerOperator(action, true);
                case PLAYER_DEOP -> playerOperator(action, false);
                case PLAYER_SEND_HUB -> playerSendHub(action);
                case PLAYER_KICK -> playerKick(action);
                case PLAYER_WHITELIST_ADD -> playerWhitelist(action, true);
                case PLAYER_WHITELIST_REMOVE -> playerWhitelist(action, false);
                case ECONOMY_BALANCE -> economyBalance(action);
                case ECONOMY_CREDIT -> economyAdjust(action, true);
                case ECONOMY_DEBIT -> economyAdjust(action, false);
                case ECONOMY_DEBTS -> questRewardDebts(action);
                case ECONOMY_DEBT_RETRY -> questRewardRetry(action);
                case ECONOMY_DEBT_SETTLE -> questRewardSettle(action);
                case MC_RIGHTS_READ -> mcRightsRead(action);
                case MC_GROUP_SYNC -> mcGroupSync(action);
                case MC_GROUP_DELETE -> mcGroupDelete(action);
                case MC_RIGHTS_SYNC -> mcRightsSync(action);
                case PLAYER_ITEM_GIVE -> itemGive(action);
                case QUEST_START -> questStart(action);
                case QUEST_COMPLETE -> questMutation(action, AgentActionType.QUEST_COMPLETE);
                case QUEST_RESET -> questMutation(action, AgentActionType.QUEST_RESET);
                case STORY_ADVANCE -> storyMutation(action, AgentActionType.STORY_ADVANCE);
                case STORY_COMPLETE -> storyMutation(action, AgentActionType.STORY_COMPLETE);
                case PLAYER_VARIABLE_SET -> variableSet(action);
                case PLAYER_RESETNEW_CONFIRM -> resetConfirm(action, ResetScope.PROGRESSION);
                case PLAYER_RESETFULL_CONFIRM -> resetConfirm(action, ResetScope.NEW_PLAYER);
                case PLAYER_BAN -> playerBan(action);
                case PLAYER_UNBAN -> playerUnban(action);
                case NPC_DEFINITION_CREATE -> npcDefinitionWrite(action, true);
                case NPC_DEFINITION_UPDATE -> npcDefinitionWrite(action, false);
                case NPC_DEFINITION_DELETE -> npcDefinitionDelete(action);
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
                // Issue #210 : état RÉEL relu du serveur, pour que la fiche joueur affiche le
                // statut OP tel qu'il est — y compris changé en jeu ou par un autre outil.
                row.put("op", p.op());
                row.put("whitelisted", p.whitelisted());
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
            // Issue #123 : jamais omis quand il est présent — le panel ne peut pas deviner le PNJ
            // destinataire d'une remise, et un libellé sans lui serait ambigu.
            m.put("npc", o.npc());
            // Issue #185 : la portée (mondes) et la règle de comptage font partie de ce que le
            // joueur doit faire — les omettre produirait un libellé incomplet côté panel.
            m.put("worlds", o.worlds());
            m.put("countMode", o.countMode());
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

    private CompletableFuture<AgentActionOutcome> travelCatalog(AgentAction action) {
        return actions.travelCatalog().thenApply(view -> {
            List<Map<String, Object>> waypointRows = new ArrayList<>();
            for (AgentActions.WaypointSummary w : view.waypoints()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", w.id());
                row.put("displayName", w.displayName());
                row.put("world", w.world());
                row.put("biomeKey", w.biomeKey());
                row.put("biomeInstance", w.biomeInstance());
                row.put("x", w.x());
                row.put("y", w.y());
                row.put("z", w.z());
                row.put("active", w.active());
                row.put("modelVersion", w.modelVersion());
                row.put("pairedBeaconId", w.pairedBeaconId());
                waypointRows.add(row);
            }
            List<Map<String, Object>> beaconRows = new ArrayList<>();
            for (AgentActions.BeaconSummary b : view.beacons()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", b.id());
                row.put("world", b.world());
                row.put("x", b.x());
                row.put("y", b.y());
                row.put("z", b.z());
                row.put("active", b.active());
                row.put("modelVersion", b.modelVersion());
                row.put("autoGenerated", b.autoGenerated());
                row.put("biomeInstance", b.biomeInstance());
                row.put("pairedWaypointId", b.pairedWaypointId());
                beaconRows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("waypoints", waypointRows);
            details.put("beacons", beaconRows);
            details.put("waypointCount", view.waypointCount());
            details.put("beaconCount", view.beaconCount());
            details.put("autoGeneratedBeaconCount", view.autoGeneratedBeaconCount());
            // Issue #156 : chaque instance Hub sans borne est exposée avec son état d'appariement,
            // pour que « il manque des bornes » devienne un diagnostic actionnable.
            List<Map<String, Object>> unpairedRows = new ArrayList<>();
            for (AgentActions.UnpairedHubInstance u : view.unpairedHubInstances()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("waypointId", u.waypointId());
                row.put("biomeInstance", u.biomeInstance());
                row.put("biomeKey", u.biomeKey());
                row.put("x", u.x());
                row.put("z", u.z());
                row.put("attempts", u.attempts());
                row.put("nextRetryEpochMs", u.nextRetryEpochMs());
                row.put("inProgress", u.inProgress());
                row.put("nearestBeaconDistance", u.nearestBeaconDistance());
                unpairedRows.add(row);
            }
            details.put("unpairedHubInstances", unpairedRows);
            details.put("generatedAtEpochMs", view.generatedAtEpochMs());
            return AgentActionOutcome.success(action.id(), String.valueOf(waypointRows.size()),
                    view.waypointCount() + " waypoint(s), " + view.beaconCount() + " borne(s) ("
                            + unpairedRows.size() + " sans borne appariée dans le Hub).",
                    details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    // ---- Mobs spéciaux / boss (issue #169, lot 1) ---------------------------------------------

    /** {@code mob.catalogs} (#172/#196) : lecture pure des registres de la version installée. */
    private CompletableFuture<AgentActionOutcome> mobCatalogs(AgentAction action) {
        return actions.mobCatalogs().thenApply(view -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("entityTypes", view.entityTypes());
            details.put("particles", view.particles());
            details.put("colorableParticles", view.colorableParticles());
            details.put("sounds", view.sounds());
            details.put("biomes", view.biomes());
            details.put("worlds", view.worlds());
            details.put("wildWorld", view.wildWorld());
            String summary = view.entityTypes().size() + " entités, " + view.particles().size()
                    + " particules, " + view.sounds().size() + " sons, " + view.biomes().size() + " biomes";
            return AgentActionOutcome.success(action.id(), String.valueOf(view.entityTypes().size()),
                    summary, details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * {@code item.catalogs} (#196) : catalogue complet des matériaux de la version installée.
     *
     * <p>Le résumé cite la version Minecraft réelle : c'est ce qui permet de vérifier d'un coup
     * d'œil que le panel propose bien les objets de <em>ce</em> serveur, et non ceux d'une autre
     * version.</p>
     */
    private CompletableFuture<AgentActionOutcome> itemCatalogs(AgentAction action) {
        return actions.itemCatalogs().thenApply(view -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("items", view.items());
            details.put("blocksWithoutItem", view.blocksWithoutItem());
            details.put("minecraftVersion", view.minecraftVersion());
            details.put("legacyExcluded", view.legacyExcluded());
            String summary = view.items().size() + " objets utilisables (icône et récompense), "
                    + view.blocksWithoutItem().size() + " blocs sans forme d'objet, "
                    + view.legacyExcluded() + " formes historiques écartées — Minecraft "
                    + view.minecraftVersion();
            return AgentActionOutcome.success(action.id(), String.valueOf(view.items().size()),
                    summary, details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * {@code content.definition.delete} (#194) : supprime la définition d'une quête ou d'une story
     * sur le serveur, avec sauvegarde, puis relit les définitions.
     *
     * <p>Le type de contenu est validé contre une liste fermée : aucun chemin, aucun nom de
     * dossier ne vient du panel. L'identifiant est validé par le même motif que les autres
     * actions de contenu.</p>
     */
    private CompletableFuture<AgentActionOutcome> contentDefinitionDelete(AgentAction action) {
        String kind = action.param("kind");
        if (!"quests".equals(kind) && !"stories".equals(kind)) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « kind » attendu : « quests » ou « stories »."));
        }
        String id = firstNonBlank(action.param("id"), action.param("content_id"));
        if (id == null || !RESOURCE_ID.matcher(id).matches()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide."));
        }
        return actions.contentDefinitionDelete(kind, id).thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> mobList(AgentAction action) {
        return actions.mobDefinitions().thenApply(view -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.MobProfileSummary p : view.profiles()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", p.id());
                row.put("category", p.category());
                row.put("enabled", p.enabled());
                row.put("entityType", p.entityType());
                row.put("displayName", p.displayName());
                row.put("spawnChance", p.spawnChance());
                row.put("worlds", p.worlds());
                row.put("biomes", p.biomes());
                row.put("zones", p.zones());
                row.put("health", p.health());
                row.put("damage", p.damage());
                row.put("speed", p.speed());
                row.put("armor", p.armor());
                row.put("knockbackResistance", p.knockbackResistance());
                row.put("scale", p.scale());
                row.put("creeperExplosionRadius", p.creeperExplosionRadius());
                row.put("particle", p.particle());
                row.put("sound", p.sound());
                row.put("xpReward", p.xpReward());
                row.put("maxPopulation", p.maxPopulation());
                row.put("alivePopulation", p.alivePopulation());
                row.put("enragedHealthFraction", p.enragedHealthFraction());
                row.put("enragedSpeedMultiplier", p.enragedSpeedMultiplier());
                row.put("enragedDamageMultiplier", p.enragedDamageMultiplier());
                row.put("summonEntityType", p.summonEntityType());
                row.put("summonAmount", p.summonAmount());
                row.put("summonChance", p.summonChance());
                row.put("summonCooldownSeconds", p.summonCooldownSeconds());
                row.put("summonMaxAlive", p.summonMaxAlive());
                row.put("abilitiesSummary", p.abilitiesSummary());
                rows.add(row);
            }
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("enabled", view.spawnSettings().enabled());
            settings.put("chance", view.spawnSettings().chance());
            settings.put("maxSimultaneousSpecial", view.spawnSettings().maxSimultaneousSpecial());

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("profiles", rows);
            details.put("spawnSettings", settings);
            details.put("hasIssues", view.hasIssues());
            details.put("issues", view.issues());
            return AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " profil(s) de mob spécial/boss.", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> mobDefinitionWrite(AgentAction action, boolean create) {
        String id = firstNonBlank(action.param("mob_id"), action.param("id"));
        if (id == null || !MOB_ID.matcher(id).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « mob_id » manquant ou invalide."));
        }
        String category = firstNonBlank(action.param("category"));
        if (category == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « category » manquant (SPECIAL ou BOSS)."));
        }
        String entityType = firstNonBlank(action.param("entity_type"));
        if (entityType == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « entity_type » manquant."));
        }
        String displayName = trimOrNull(action.param("display_name"));
        if (displayName == null || displayName.length() > MAX_DISPLAY_NAME || displayName.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « display_name » manquant, trop long, ou multi-ligne."));
        }
        Double spawnChance = parseDouble(action.param("spawn_chance"));
        if (spawnChance == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « spawn_chance » manquant ou invalide."));
        }
        boolean enabled = !"false".equalsIgnoreCase(trimOrNull(action.param("enabled")));

        CompletableFuture<AgentActions.MutationResult> future = (create ? actions.mobDefinitionCreate(
                id, category, enabled, entityType, displayName, spawnChance,
                splitList(action.param("worlds")), splitList(action.param("biomes")), splitList(action.param("zones")),
                parseDouble(action.param("health")), parseDouble(action.param("damage")),
                parseDouble(action.param("speed")), parseDouble(action.param("armor")),
                parseDouble(action.param("knockback_resistance")), parseDouble(action.param("scale")),
                parseDouble(action.param("creeper_explosion_radius")),
                trimOrNull(action.param("particle")), trimOrNull(action.param("sound")),
                parseOptionalInt(action.param("xp_reward")), parseOptionalInt(action.param("max_population")),
                parseDouble(action.param("enraged_health_fraction")), parseDouble(action.param("enraged_speed_multiplier")),
                parseDouble(action.param("enraged_damage_multiplier")), trimOrNull(action.param("summon_entity_type")),
                parseOptionalInt(action.param("summon_amount")), parseDouble(action.param("summon_chance")),
                parseOptionalInt(action.param("summon_cooldown_seconds")), parseOptionalInt(action.param("summon_max_alive")))
                : actions.mobDefinitionUpdate(
                id, category, enabled, entityType, displayName, spawnChance,
                splitList(action.param("worlds")), splitList(action.param("biomes")), splitList(action.param("zones")),
                parseDouble(action.param("health")), parseDouble(action.param("damage")),
                parseDouble(action.param("speed")), parseDouble(action.param("armor")),
                parseDouble(action.param("knockback_resistance")), parseDouble(action.param("scale")),
                parseDouble(action.param("creeper_explosion_radius")),
                trimOrNull(action.param("particle")), trimOrNull(action.param("sound")),
                parseOptionalInt(action.param("xp_reward")), parseOptionalInt(action.param("max_population")),
                parseDouble(action.param("enraged_health_fraction")), parseDouble(action.param("enraged_speed_multiplier")),
                parseDouble(action.param("enraged_damage_multiplier")), trimOrNull(action.param("summon_entity_type")),
                parseOptionalInt(action.param("summon_amount")), parseDouble(action.param("summon_chance")),
                parseOptionalInt(action.param("summon_cooldown_seconds")), parseOptionalInt(action.param("summon_max_alive"))));
        return future.thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> mobDefinitionToggle(AgentAction action) {
        String id = firstNonBlank(action.param("mob_id"), action.param("id"));
        if (id == null || !MOB_ID.matcher(id).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « mob_id » manquant ou invalide."));
        }
        boolean enabled = isTrue(action.param("enabled"));
        return actions.mobDefinitionToggle(id, enabled).thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> mobSpawnSettingsSet(AgentAction action) {
        boolean enabled = !"false".equalsIgnoreCase(trimOrNull(action.param("enabled")));
        Double chance = parseDouble(action.param("chance"));
        if (chance == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « chance » manquant ou invalide."));
        }
        Integer max = parseOptionalInt(action.param("max_simultaneous_special"));
        return actions.mobSpawnSettingsSet(enabled, chance, max).thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> mobTestSpawn(AgentAction action) {
        String definitionId = firstNonBlank(action.param("mob_id"), action.param("id"));
        if (definitionId == null || !MOB_ID.matcher(definitionId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « mob_id » manquant ou invalide."));
        }
        String player = firstNonBlank(action.param("player"), action.param("player_name"));
        if (player == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « player » manquant."));
        }
        return actions.mobTestSpawn(definitionId, player).thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> mobTestClear(AgentAction action) {
        return actions.mobTestClear().thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    // ---- server.announce / server.logs.tail (issue #95) ----------------------------------

    private static final int MAX_ANNOUNCE_CHARS = 200;
    private static final java.util.Set<String> ANNOUNCE_CHANNELS =
            java.util.Set.of("chat", "actionbar", "title");
    private static final int MAX_LOG_TAIL = 500;

    /**
     * {@code server.announce} (#95). Revalide <strong>intégralement</strong> ce que le panel a déjà
     * validé : le navigateur n'est pas une source de confiance, et l'agent est la dernière barrière
     * avant le serveur. Le refus du « / » initial est explicite — un administrateur qui tape
     * « /say … » attend une commande, et diffuser littéralement « /say … » serait pire que refuser.
     */
    private CompletableFuture<AgentActionOutcome> serverAnnounce(AgentAction action) {
        String message = action.param("message");
        if (message == null || message.isBlank() || message.length() > MAX_ANNOUNCE_CHARS) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Annonce vide ou trop longue (max " + MAX_ANNOUNCE_CHARS + " caractères)."));
        }
        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(), "Annonce multi-ligne refusée."));
        }
        if (message.startsWith("/")) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Une annonce est un texte, pas une commande : « / » initial refusé."));
        }
        String rawChannel = action.param("channel");
        String channel = rawChannel == null || rawChannel.isBlank()
                ? "chat" : rawChannel.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ANNOUNCE_CHANNELS.contains(channel)) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Canal d'annonce inconnu : « " + safe(rawChannel) + " » (chat, actionbar, title)."));
        }
        return actions.announce(message, channel).thenApply(result -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", result.code());
            details.put("channel", result.channel());
            details.put("recipients", result.recipients());
            details.put("online", result.online());
            if (!result.ok()) {
                return AgentActionOutcome.failed(action.id(), result.message());
            }
            return AgentActionOutcome.success(action.id(), String.valueOf(result.recipients()),
                    result.message(), details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /** {@code server.logs.tail} (#95) — lecture bornée du tampon de console. Aucun effet de bord. */
    private CompletableFuture<AgentActionOutcome> serverLogsTail(AgentAction action) {
        long after = parseLongParam(action.param("after"), 0L);
        if (after < 0) {
            return done(AgentActionOutcome.rejected(action.id(), "Curseur de console invalide."));
        }
        long limit = parseLongParam(action.param("limit"), 200L);
        if (limit < 1 || limit > MAX_LOG_TAIL) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Nombre de lignes hors bornes (1 à " + MAX_LOG_TAIL + ")."));
        }
        return actions.serverLogs(after, (int) limit).thenApply(view -> {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (AgentActions.ServerLogLine line : view.lines()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("seq", line.sequence());
                item.put("at", line.epochMillis());
                item.put("level", line.level());
                item.put("source", line.source());
                item.put("message", line.message());
                lines.add(item);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("lines", lines);
            details.put("firstSequence", view.firstSequence());
            details.put("lastSequence", view.lastSequence());
            details.put("dropped", view.dropped());
            details.put("capacity", view.capacity());
            details.put("gap", view.gap());
            if (view.limitation() != null) {
                details.put("limitation", view.limitation());
            }
            String summary = view.limitation() != null
                    ? view.limitation()
                    : view.lines().size() + " ligne(s) de console, curseur " + view.lastSequence();
            return AgentActionOutcome.success(action.id(), String.valueOf(view.lastSequence()),
                    summary, details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    // ---- content.reload / content.reload.preview (issue #131) ----------------------------

    /**
     * Les familles viennent du panel en CSV. Un jeton inconnu est <strong>rejeté</strong> plutôt
     * qu'ignoré : interpréter partiellement une demande de rechargement serait pire que la refuser,
     * car l'administrateur croirait avoir rechargé plus que ce qui l'a été.
     */
    private CompletableFuture<AgentActionOutcome> contentReload(AgentAction action, boolean apply) {
        String rawFamilies = firstNonBlank(action.param("families"), action.param("family"));
        if (rawFamilies == null || rawFamilies.isBlank()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « families » manquant (ex. « quests,dialogues »)."));
        }
        List<String> families = new ArrayList<>();
        for (String token : rawFamilies.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                families.add(trimmed);
            }
        }
        if (families.isEmpty()) {
            return done(AgentActionOutcome.rejected(action.id(), "Aucune famille de contenu demandée."));
        }
        if (families.size() > 12) {
            return done(AgentActionOutcome.rejected(action.id(), "Trop de familles demandées."));
        }
        return actions.contentReload(families, apply).thenApply(view -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("applied", view.applied());
            details.put("code", view.code());
            details.put("runtimeHash", view.runtimeHash());
            details.put("durationMillis", view.durationMillis());
            details.put("restartRequired", view.restartRequired());
            details.put("referenceErrors", view.referenceErrors());
            details.put("suggestedFamilies", view.suggestedFamilies());
            List<Map<String, Object>> familyDetails = new ArrayList<>();
            for (AgentActions.ContentReloadFamilyView family : view.families()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("family", family.family());
                item.put("label", family.label());
                item.put("loaded", family.loaded());
                item.put("issues", family.issues());
                item.put("messages", family.messages());
                item.put("ids", family.ids());
                familyDetails.add(item);
            }
            details.put("families", familyDetails);
            // Un refus de contenu n'est PAS un succès : le panel doit pouvoir distinguer
            // « rien appliqué parce qu'invalide » de « appliqué ».
            boolean ok = switch (view.code()) {
                case "APPLIED", "PREVIEW_OK" -> true;
                default -> false;
            };
            if (!ok) {
                return AgentActionOutcome.failed(action.id(), view.message());
            }
            return AgentActionOutcome.success(action.id(), view.runtimeHash(), view.message(), details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    // ---- Administration de joueur (issue #210) -------------------------------------------

    private static final int MAX_REASON = 200;

    /**
     * {@code player.op} / {@code player.deop}. La <strong>raison est obligatoire</strong> : élever
     * un compte au rang d'opérateur sans trace de motif rend l'audit inexploitable.
     *
     * <p>Réutilise le résolveur d'identité commun : la cible est toujours un UUID, jamais un pseudo
     * interprété à l'exécution.</p>
     */
    private CompletableFuture<AgentActionOutcome> playerOperator(AgentAction action, boolean op) {
        String reason = trimOrNull(firstNonBlank(action.param("reason"), action.param("motif")));
        if (reason == null || reason.length() > MAX_REASON) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Une raison est obligatoire pour modifier le statut OP (max " + MAX_REASON + ")."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.setOperator(uuid, name, op).thenApply(r -> toOutcome(action, r)));
    }

    /** {@code player.send.hub} — renvoi d'un joueur connecté à une position sûre du Hub. */
    private CompletableFuture<AgentActionOutcome> playerSendHub(AgentAction action) {
        return withResolvedUuid(action, (uuid, name) ->
                actions.sendToHub(uuid, name).thenApply(r -> toOutcome(action, r)));
    }

    /** {@code player.kick} — raison obligatoire, traitée comme du texte. */
    private CompletableFuture<AgentActionOutcome> playerKick(AgentAction action) {
        String reason = trimOrNull(firstNonBlank(action.param("reason"), action.param("motif")));
        if (reason == null || reason.length() > MAX_REASON || reason.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Raison manquante, trop longue (max " + MAX_REASON + ") ou multi-ligne."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.kickPlayer(uuid, name, reason).thenApply(r -> toOutcome(action, r)));
    }

    /** {@code player.whitelist.add} / {@code player.whitelist.remove}. */
    private CompletableFuture<AgentActionOutcome> playerWhitelist(AgentAction action, boolean add) {
        return withResolvedUuid(action, (uuid, name) ->
                actions.setWhitelisted(uuid, name, add).thenApply(r -> toOutcome(action, r)));
    }

    // ---- Monnaie (issue #140) -------------------------------------------------------------

    /** Plafond d'une opération unique — garde-fou de saisie, pas une règle d'équilibrage. */
    private static final long MAX_ECONOMY_AMOUNT = 1_000_000L;

    /** {@code economy.balance} — solde réel et journal récent. Lecture seule. */
    // ---- Pont vers les droits Minecraft (issue #200) -------------------------------------------

    private CompletableFuture<AgentActionOutcome> mcRightsRead(AgentAction action) {
        return withResolvedUuid(action, (uuid, name) ->
                actions.mcRightsRead(uuid).thenApply(view -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("bridgeAvailable", view.bridgeAvailable());
                    details.put("reason", view.reason());
                    details.put("effective", view.effective());
                    if (!view.ok()) {
                        return new AgentActionOutcome(action.id(), AgentActionOutcome.FAILED,
                                "READ_FAILED", name + " : " + view.message(), details,
                                java.time.Instant.now());
                    }
                    return AgentActionOutcome.success(action.id(),
                            view.bridgeAvailable() ? "AVAILABLE" : "UNAVAILABLE",
                            name + " : " + view.message(), details);
                }));
    }

    /**
     * Lecture des couples {@code nœud}/{@code monde} du formulaire. Un nom distinct par droit
     * ({@code node0}, {@code world0}, {@code node1}…) : {@code Http.formBody} côté panel ne conserve
     * qu'une valeur par clé, donc des champs homonymes perdraient tout sauf un.
     */
    private static List<AgentActions.McNodeSpec> readNodeSpecs(AgentAction action) {
        List<AgentActions.McNodeSpec> out = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String node = trimOrEmpty(action.param("node" + i));
            if (node.isEmpty()) {
                continue;
            }
            out.add(new AgentActions.McNodeSpec(node, trimOrEmpty(action.param("world" + i))));
        }
        return out;
    }

    private CompletableFuture<AgentActionOutcome> mcGroupSync(AgentAction action) {
        String groupId = trimOrEmpty(action.param("group"));
        if (groupId.isEmpty()) {
            return done(AgentActionOutcome.rejected(action.id(), "Identifiant de groupe manquant."));
        }
        String displayName = trimOrEmpty(action.param("label"));
        return actions.mcGroupSync(groupId, displayName, readNodeSpecs(action))
                .thenApply(view -> toSyncOutcome(action, view, "groupe " + groupId));
    }

    private CompletableFuture<AgentActionOutcome> mcGroupDelete(AgentAction action) {
        String groupId = trimOrEmpty(action.param("group"));
        if (groupId.isEmpty()) {
            return done(AgentActionOutcome.rejected(action.id(), "Identifiant de groupe manquant."));
        }
        return actions.mcGroupDelete(groupId)
                .thenApply(view -> toSyncOutcome(action, view, "groupe " + groupId));
    }

    private CompletableFuture<AgentActionOutcome> mcRightsSync(AgentAction action) {
        List<String> groupIds = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String groupId = trimOrEmpty(action.param("group" + i));
            if (!groupId.isEmpty()) {
                groupIds.add(groupId);
            }
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.mcRightsSync(uuid, groupIds).thenApply(view -> toSyncOutcome(action, view, name)));
    }

    private static AgentActionOutcome toSyncOutcome(AgentAction action, AgentActions.McSyncView view,
                                                     String target) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("added", view.added());
        details.put("removed", view.removed());
        details.put("unchanged", view.unchanged());
        details.put("preserved", view.preserved());
        if (!view.ok()) {
            // Une synchronisation qui n'a rien appliqué n'est PAS un succès : sinon un
            // administrateur croirait qu'un builder a ses droits alors qu'il ne les a pas.
            return new AgentActionOutcome(action.id(), AgentActionOutcome.FAILED, "SYNC_FAILED",
                    target + " : " + view.message(), details, java.time.Instant.now());
        }
        String code = view.added().isEmpty() && view.removed().isEmpty() ? "UNCHANGED" : "APPLIED";
        return AgentActionOutcome.success(action.id(), code, target + " : " + view.message(), details);
    }

    // ---- Récompenses monétaires restées dues (issue #16, second lot) ---------------------------

    private CompletableFuture<AgentActionOutcome> questRewardDebts(AgentAction action) {
        long limit = parseLongParam(action.param("limit"), 20L);
        if (limit < 1 || limit > 100) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Nombre de récompenses dues hors bornes (1 à 100)."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.questRewardDebts(uuid, (int) limit).thenApply(view -> {
                    if (!view.ok()) {
                        return AgentActionOutcome.failed(action.id(), view.message());
                    }
                    Map<String, Object> details = new LinkedHashMap<>();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    long total = 0;
                    for (AgentActions.QuestRewardDebtView debt : view.debts()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("grantId", debt.grantId());
                        row.put("questId", debt.questId());
                        row.put("questTitle", debt.questTitle());
                        row.put("occurrence", debt.occurrence());
                        row.put("rewardIndex", debt.rewardIndex());
                        row.put("amount", debt.amount());
                        row.put("attempts", debt.attempts());
                        row.put("lastError", debt.lastError());
                        rows.add(row);
                        total += debt.amount();
                    }
                    details.put("debts", rows);
                    details.put("total", total);
                    return AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                            name + " : " + view.message(), details);
                }));
    }

    private CompletableFuture<AgentActionOutcome> questRewardRetry(AgentAction action) {
        String grantId = trimOrEmpty(action.param("grant"));
        if (grantId.isEmpty()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Identifiant de récompense due manquant."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.retryQuestRewardDebt(uuid, grantId).thenApply(view -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("code", view.code());
                    details.put("amount", view.amount());
                    details.put("balanceAfter", view.balanceAfter());
                    // Une reprise qui ne paie pas n'est PAS un succès : la distinction compte, sinon
                    // l'audit laisserait croire que le joueur a été crédité.
                    if (view.ok()) {
                        return AgentActionOutcome.success(action.id(), view.code(),
                                name + " : " + view.message(), details);
                    }
                    return new AgentActionOutcome(action.id(), AgentActionOutcome.FAILED, view.code(),
                            name + " : " + view.message(), details, java.time.Instant.now());
                }));
    }

    private CompletableFuture<AgentActionOutcome> questRewardSettle(AgentAction action) {
        String grantId = trimOrEmpty(action.param("grant"));
        if (grantId.isEmpty()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Identifiant de récompense due manquant."));
        }
        String reason = trimOrEmpty(action.param("reason"));
        if (reason.isEmpty()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Raison obligatoire : elle est conservée avec la récompense réglée."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.settleQuestRewardDebt(uuid, grantId, reason)
                        .thenApply(result -> toOutcome(action, result)));
    }

    private CompletableFuture<AgentActionOutcome> economyBalance(AgentAction action) {
        long historyLimit = parseLongParam(action.param("history"), 20L);
        if (historyLimit < 1 || historyLimit > 100) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Nombre de lignes de journal hors bornes (1 à 100)."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.economyBalance(uuid, (int) historyLimit).thenApply(view -> {
                    if (!view.ok()) {
                        return AgentActionOutcome.failed(action.id(), view.message());
                    }
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("balance", view.balance());
                    List<Map<String, Object>> history = new ArrayList<>();
                    for (AgentActions.EconomyLedgerView entry : view.history()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("type", entry.type());
                        item.put("amount", entry.amount());
                        item.put("context", entry.context());
                        item.put("at", entry.at());
                        history.add(item);
                    }
                    details.put("history", history);
                    return AgentActionOutcome.success(action.id(), String.valueOf(view.balance()),
                            name + " : solde " + view.balance() + " (" + history.size()
                                    + " transaction(s) récente(s))", details);
                }));
    }

    /**
     * {@code economy.credit} / {@code economy.debit}. Raison <strong>obligatoire</strong> : elle
     * est enregistrée dans le journal, sans quoi une création administrative serait indiscernable
     * d'un gain de jeu quelques mois plus tard.
     */
    private CompletableFuture<AgentActionOutcome> economyAdjust(AgentAction action, boolean credit) {
        long amount = parseLongParam(action.param("amount"), -1L);
        if (amount <= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Montant manquant ou non strictement positif."));
        }
        if (amount > MAX_ECONOMY_AMOUNT) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Montant trop élevé en une seule opération (maximum " + MAX_ECONOMY_AMOUNT + ")."));
        }
        String reason = trimOrNull(firstNonBlank(action.param("reason"), action.param("motif")));
        if (reason == null || reason.length() > 200 || reason.indexOf('\n') >= 0) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Raison manquante, trop longue (max 200) ou multi-ligne."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.economyAdjust(uuid, name, amount, credit, reason).thenApply(view -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("code", view.code());
                    details.put("balanceBefore", view.balanceBefore());
                    details.put("balanceAfter", view.balanceAfter());
                    if (!view.ok()) {
                        // Fonds insuffisants est un refus MÉTIER : signalé comme échec, avec le
                        // solde réel, jamais comme un succès silencieux.
                        return AgentActionOutcome.failed(action.id(), view.message());
                    }
                    return AgentActionOutcome.success(action.id(),
                            String.valueOf(view.balanceAfter()), view.message(), details);
                }));
    }

    /** Entier long de paramètre : {@code -1} marque explicitement une valeur non numérique. */
    private static long parseLongParam(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseOptionalInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String piece : raw.split(",")) {
            if (!piece.isBlank()) {
                out.add(piece.trim());
            }
        }
        return out;
    }

    // ---- Emplacements de construction (issue #213) ---------------------------------------------

    /** Identifiant d'emplacement : exactement la forme que le serveur attribue. */
    private static final java.util.regex.Pattern BUILD_SITE_ID =
            java.util.regex.Pattern.compile("buildsite_[0-9]{1,12}");

    private CompletableFuture<AgentActionOutcome> buildingSiteList(AgentAction action) {
        return actions.buildingSites().thenApply(view -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.BuildingSiteSummary s : view.sites()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", s.id());
                row.put("name", s.name());
                row.put("description", s.description());
                row.put("world", s.world());
                row.put("x", s.x());
                row.put("y", s.y());
                row.put("z", s.z());
                row.put("facing", s.facing());
                row.put("status", s.status());
                row.put("createdBy", s.createdBy());
                row.put("createdAt", s.createdAt());
                row.put("worldLoaded", s.worldLoaded());
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sites", rows);
            details.put("worlds", view.worlds());
            details.put("total", view.total());
            return AgentActionOutcome.success(action.id(), String.valueOf(view.total()),
                    view.total() + " emplacement(s) de construction.", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> buildingSiteRename(AgentAction action) {
        String id = buildSiteId(action);
        if (id == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        String name = trimOrNull(action.param("name"));
        if (name == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « name » manquant : un emplacement garde toujours un libellé."));
        }
        if (name.length() > BUILD_SITE_NAME_MAX) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Nom trop long (" + BUILD_SITE_NAME_MAX + " caractères au plus)."));
        }
        return actions.buildingSiteRename(id, name)
                .thenApply(r -> mutationOutcome(action, r, "id", id))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * {@code building.site.describe}. Une description <strong>vide est une valeur valide</strong> :
     * c'est le geste « effacer la note », et refuser le paramètre absent reviendrait à rendre
     * l'effacement impossible.
     */
    private CompletableFuture<AgentActionOutcome> buildingSiteDescribe(AgentAction action) {
        String id = buildSiteId(action);
        if (id == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        String description = action.param("description") == null ? "" : action.param("description").trim();
        if (description.length() > BUILD_SITE_DESCRIPTION_MAX) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Description trop longue (" + BUILD_SITE_DESCRIPTION_MAX
                            + " caractères au plus)."));
        }
        return actions.buildingSiteDescribe(id, description)
                .thenApply(r -> mutationOutcome(action, r, "id", id))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> buildingSiteFacing(AgentAction action) {
        String id = buildSiteId(action);
        if (id == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        String facing = trimOrNull(action.param("facing"));
        if (facing == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « facing » manquant — attendu NORTH, EAST, SOUTH ou WEST."));
        }
        return actions.buildingSiteFacing(id, facing)
                .thenApply(r -> mutationOutcome(action, r, "id", id))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> buildingSiteDelete(AgentAction action) {
        String id = buildSiteId(action);
        if (id == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        return actions.buildingSiteDelete(id)
                .thenApply(r -> mutationOutcome(action, r, "id", id))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> buildingDefinitionList(AgentAction action) {
        return actions.buildingLibrary().thenApply(view -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.BuildingDefinitionSummary b : view.buildings()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", b.id());
                row.put("name", b.name());
                row.put("description", b.description());
                row.put("sizeX", b.sizeX());
                row.put("sizeY", b.sizeY());
                row.put("sizeZ", b.sizeZ());
                row.put("anchorX", b.anchorX());
                row.put("anchorY", b.anchorY());
                row.put("anchorZ", b.anchorZ());
                row.put("front", b.front());
                row.put("materials", b.materials());
                row.put("schematic", b.schematic());
                row.put("schematicPresent", b.schematicPresent());
                row.put("version", b.version());
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("buildings", rows);
            details.put("problems", view.problems());
            details.put("engineAvailable", view.engineAvailable());
            details.put("engineReason", view.engineReason());
            return AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " bâtiment(s) en bibliothèque.", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * {@code building.placement.preview}. N'écrit rien, donc n'est jamais « refusée » au sens d'une
     * mutation : un aperçu impossible est un aperçu qui porte ses motifs de refus.
     */
    private CompletableFuture<AgentActionOutcome> buildingPlacementPreview(AgentAction action) {
        String siteId = buildSiteId(action);
        if (siteId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        String buildingId = buildingId(action);
        if (buildingId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « building » manquant ou invalide."));
        }
        return actions.buildingPlacementPreview(siteId, buildingId).thenApply(view -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("placeable", view.placeable());
            details.put("siteId", view.siteId());
            details.put("siteName", view.siteName());
            details.put("siteFacing", view.siteFacing());
            details.put("world", view.world());
            details.put("anchorX", view.anchorX());
            details.put("anchorY", view.anchorY());
            details.put("anchorZ", view.anchorZ());
            details.put("buildingId", view.buildingId());
            details.put("buildingName", view.buildingName());
            details.put("sizeX", view.sizeX());
            details.put("sizeY", view.sizeY());
            details.put("sizeZ", view.sizeZ());
            details.put("front", view.front());
            details.put("rotation", view.rotation());
            details.put("minX", view.minX());
            details.put("minY", view.minY());
            details.put("minZ", view.minZ());
            details.put("maxX", view.maxX());
            details.put("maxY", view.maxY());
            details.put("maxZ", view.maxZ());
            details.put("blockCount", view.blockCount());
            details.put("nonAirBlocks", view.nonAirBlocks());
            details.put("refusals", view.refusals());
            details.put("warnings", view.warnings());
            String summary = view.placeable()
                    ? "Posable : " + view.buildingName() + " tourné de " + view.rotation()
                            + "°, emprise " + view.minX() + ".." + view.maxX() + " / "
                            + view.minY() + ".." + view.maxY() + " / "
                            + view.minZ() + ".." + view.maxZ() + "."
                    : "Non posable : " + (view.refusals().isEmpty()
                            ? "motif non précisé." : view.refusals().get(0));
            return AgentActionOutcome.success(action.id(), view.placeable() ? "ok" : "ko",
                    summary, details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> buildingPlacementPlace(AgentAction action) {
        String siteId = buildSiteId(action);
        if (siteId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        String buildingId = buildingId(action);
        if (buildingId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « building » manquant ou invalide."));
        }
        // La confirmation est exigée ICI aussi, et pas seulement par le panel : coller écrase des
        // blocs du monde, donc la dernière barrière doit être du côté qui écrit.
        if (!"true".equals(trimOrNull(action.param("confirm")))) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Confirmation manquante : « confirm=true » est exigé pour écrire dans le "
                            + "monde."));
        }
        String placedBy = trimOrNull(action.param("placed_by"));
        return actions.buildingPlacementPlace(siteId, buildingId,
                        placedBy == null ? "panel" : placedBy)
                .thenApply(r -> mutationOutcome(action, r, "id", siteId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> buildingPlacementRollback(AgentAction action) {
        String siteId = buildSiteId(action);
        if (siteId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        if (!"true".equals(trimOrNull(action.param("confirm")))) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Confirmation manquante : « confirm=true » est exigé pour restaurer la zone."));
        }
        return actions.buildingPlacementRollback(siteId)
                .thenApply(r -> mutationOutcome(action, r, "id", siteId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    // ---- Réorienter, remplacer, journal (issue #234) --------------------------------------------

    private CompletableFuture<AgentActionOutcome> buildingRetargetPreview(AgentAction action) {
        String siteId = buildSiteId(action);
        if (siteId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        String facing = facingParam(action);
        if (facing == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « facing » manquant ou invalide (NORTH, EAST, SOUTH ou WEST)."));
        }
        // « building » est FACULTATIF : absent, on réoriente le bâtiment déjà posé.
        String buildingId = buildingId(action);
        return actions.buildingRetargetPreview(siteId, buildingId == null ? "" : buildingId, facing)
                .thenApply(view -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("applicable", view.applicable());
                    details.put("operation", view.operation());
                    details.put("site_id", view.siteId());
                    details.put("site_facing", view.siteFacing());
                    details.put("current_building_id", view.currentBuildingId());
                    details.put("current_building_name", view.currentBuildingName());
                    details.put("current_rotation", view.currentRotation());
                    details.put("current_footprint", view.currentFootprint());
                    details.put("current_block_count", view.currentBlockCount());
                    details.put("target_building_id", view.targetBuildingId());
                    details.put("target_building_name", view.targetBuildingName());
                    details.put("target_rotation", view.targetRotation());
                    details.put("target_footprint", view.targetFootprint());
                    details.put("target_block_count", view.targetBlockCount());
                    details.put("target_size", view.targetSizeX() + " × " + view.targetSizeZ()
                            + " × " + view.targetSizeY());
                    details.put("target_non_air", view.targetNonAirBlocks());
                    details.put("overlapping", view.overlapping());
                    details.put("restore_source", view.restoreSource());
                    details.put("restorable", view.restorable());
                    // L'orientation DEMANDÉE, renvoyée telle quelle : le formulaire de confirmation
                    // doit pouvoir la renvoyer sans que le navigateur ait à la déduire d'une
                    // rotation en degrés.
                    details.put("requested_facing", facing);
                    details.put("refusals", view.refusals());
                    details.put("warnings", view.warnings());
                    // Le jeton voyage avec l'aperçu : c'est lui qui rend la confirmation sûre.
                    details.put("token", view.token());
                    return AgentActionOutcome.success(action.id(), view.operation(),
                            view.applicable()
                                    ? "Aperçu calculé : " + view.currentFootprint() + " → "
                                            + view.targetFootprint()
                                    : "Aperçu refusé : " + (view.refusals().isEmpty()
                                            ? "motif non précisé" : view.refusals().get(0)),
                            details);
                })
                .exceptionally(err -> AgentActionOutcome.failed(action.id(),
                        "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> buildingReorient(AgentAction action) {
        String siteId = buildSiteId(action);
        if (siteId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        String facing = facingParam(action);
        if (facing == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « facing » manquant ou invalide (NORTH, EAST, SOUTH ou WEST)."));
        }
        if (!"true".equals(trimOrNull(action.param("confirm")))) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Confirmation manquante : « confirm=true » est exigé pour réorienter."));
        }
        return actions.buildingReorient(siteId, facing, actorOf(action),
                        trimOrNull(action.param("token")))
                .thenApply(r -> mutationOutcome(action, r, "id", siteId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(),
                        "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> buildingReplace(AgentAction action) {
        String siteId = buildSiteId(action);
        if (siteId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        String buildingId = buildingId(action);
        if (buildingId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « building » manquant ou invalide."));
        }
        String facing = facingParam(action);
        if (facing == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « facing » manquant ou invalide (NORTH, EAST, SOUTH ou WEST)."));
        }
        if (!"true".equals(trimOrNull(action.param("confirm")))) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Confirmation manquante : « confirm=true » est exigé pour remplacer."));
        }
        return actions.buildingReplace(siteId, buildingId, facing, actorOf(action),
                        trimOrNull(action.param("token")))
                .thenApply(r -> mutationOutcome(action, r, "id", siteId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(),
                        "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> buildingHistory(AgentAction action) {
        String siteId = buildSiteId(action);
        if (siteId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « id » manquant ou invalide (forme « buildsite_0001 »)."));
        }
        return actions.buildingHistory(siteId).thenApply(view -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var line : view.lines()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("operation", line.operation());
                row.put("operation_label", line.operationLabel());
                row.put("building_id", line.buildingId());
                row.put("building_version", line.buildingVersion());
                row.put("sha", line.shortSha());
                row.put("rotation", line.rotation());
                row.put("footprint", line.footprint());
                row.put("actor", line.actor());
                row.put("at", line.at());
                row.put("ok", line.ok());
                row.put("detail", line.detail());
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("site_id", view.siteId());
            details.put("lines", rows);
            return AgentActionOutcome.success(action.id(), view.siteId(),
                    rows.size() + " opération(s) enregistrée(s) pour " + view.siteId() + ".",
                    details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(),
                "Échec : " + rootName(err)));
    }

    /** Orientation demandée, validée contre la liste fermée des quatre points cardinaux. */
    private static String facingParam(AgentAction action) {
        String raw = firstNonBlank(action.param("facing"), action.param("orientation"));
        if (raw == null) {
            return null;
        }
        String clean = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (clean) {
            case "NORTH", "EAST", "SOUTH", "WEST" -> clean;
            default -> null;
        };
    }

    /** Qui agit, pour le journal. Le panel l'envoie ; à défaut, on l'écrit « panel ». */
    private static String actorOf(AgentAction action) {
        String raw = firstNonBlank(action.param("actor"), action.param("by"));
        return raw == null ? "panel" : raw.trim();
    }

    /** Identifiant de bâtiment de bibliothèque, même forme que le contenu du projet. */
    private static String buildingId(AgentAction action) {
        String raw = firstNonBlank(action.param("building"), action.param("building_id"));
        if (raw == null) {
            return null;
        }
        String clean = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return clean.matches("[a-z0-9][a-z0-9_]{0,63}") ? clean : null;
    }

    // ---- Publication de contenu vers DEV (issue #47) -------------------------------------------

    /** Taille maximale d'un YAML transporté. Large pour du contenu, fini par principe. */
    private static final int PUBLISH_YAML_MAX = 256 * 1024;

    private CompletableFuture<AgentActionOutcome> contentDevState(AgentAction action) {
        return actions.contentDevState().thenApply(view -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentActions.DevContentFile f : view.files()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", f.kind());
                row.put("slug", f.slug());
                row.put("sha256", f.sha256());
                row.put("bytes", f.bytes());
                rows.add(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("files", rows);
            details.put("runtimeIds", view.runtimeIds());
            details.put("runtimeHash", view.runtimeHash());
            return AgentActionOutcome.success(action.id(), String.valueOf(rows.size()),
                    rows.size() + " fichier(s) de contenu sur DEV.", details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> contentDevRead(AgentAction action) {
        String kind = trimOrNull(action.param("kind"));
        String slug = publishSlug(action);
        if (kind == null || slug == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètres « kind » et « id » obligatoires."));
        }
        return actions.contentDevRead(kind, slug).thenApply(view -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("kind", view.kind());
            details.put("slug", view.slug());
            details.put("present", view.present());
            details.put("tooLarge", view.tooLarge());
            details.put("sha256", view.sha256());
            details.put("text", view.text());
            String summary = !view.present() ? "Absent de DEV."
                    : view.tooLarge() ? "Présent sur DEV mais trop volumineux pour être lu."
                            : view.text().length() + " caractères lus depuis DEV.";
            return AgentActionOutcome.success(action.id(),
                    view.present() ? "present" : "absent", summary, details);
        }).exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> contentPublish(AgentAction action) {
        String kind = trimOrNull(action.param("kind"));
        String slug = publishSlug(action);
        if (kind == null || slug == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètres « kind » et « id » obligatoires (identifiant en minuscules, "
                            + "sans séparateur)."));
        }
        String yaml = action.param("yaml");
        if (yaml == null || yaml.isBlank()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « yaml » manquant : il n'y a rien à publier."));
        }
        if (yaml.length() > PUBLISH_YAML_MAX) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Contenu trop volumineux (" + PUBLISH_YAML_MAX + " caractères au plus)."));
        }
        // La confirmation est exigée ICI aussi : publier écrit un fichier sur le serveur et permute
        // le contenu chargé. La dernière barrière doit être du côté qui écrit.
        if (!"true".equals(trimOrNull(action.param("confirm")))) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Confirmation manquante : « confirm=true » est exigé pour publier."));
        }
        // Absent = « je n'ai pas regardé », ce qui désactive la détection de conflit. On exige donc
        // le champ, la chaîne vide signifiant explicitement « la ressource était absente de DEV ».
        String expectedDevSha = action.param("expected_dev_sha");
        if (expectedDevSha == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « expected_dev_sha » manquant : sans lui, une modification faite "
                            + "sur DEV depuis votre analyse serait écrasée en silence."));
        }
        return actions.contentPublish(kind, slug, yaml, expectedDevSha.trim(),
                        trimOrNull(action.param("expected_id")))
                .thenApply(view -> publishOutcome(action, view))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    private CompletableFuture<AgentActionOutcome> contentPublishRollback(AgentAction action) {
        String kind = trimOrNull(action.param("kind"));
        String slug = publishSlug(action);
        if (kind == null || slug == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètres « kind » et « id » obligatoires."));
        }
        if (!"true".equals(trimOrNull(action.param("confirm")))) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Confirmation manquante : « confirm=true » est exigé pour revenir en arrière."));
        }
        return actions.contentPublishRollback(kind, slug, trimOrNull(action.param("backup")),
                        trimOrNull(action.param("expected_id")))
                .thenApply(view -> publishOutcome(action, view))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * Traduit un compte rendu de publication en résultat d'action.
     *
     * <p><strong>Un refus n'est pas un échec technique</strong> : un conflit ou un identifiant
     * invalide sont des réponses légitimes du serveur, donc {@code REJECTED}. {@code FAILED} est
     * réservé à ce qui a réellement cassé.</p>
     */
    private AgentActionOutcome publishOutcome(AgentAction action,
                                              AgentActions.ContentPublishResultView view) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ok", view.ok());
        details.put("code", view.code());
        details.put("message", view.message());
        details.put("kind", view.kind());
        details.put("slug", view.slug());
        details.put("expectedId", view.expectedId());
        details.put("devShaBefore", view.devShaBefore());
        details.put("devShaAfter", view.devShaAfter());
        details.put("sourceSha", view.sourceSha());
        details.put("created", view.created());
        details.put("backupPath", view.backupPath());
        details.put("reloadApplied", view.reloadApplied());
        details.put("reloadCode", view.reloadCode());
        details.put("reloadMessage", view.reloadMessage());
        details.put("loadedCount", view.loadedCount());
        details.put("issueCount", view.issueCount());
        details.put("runtimeConfirmed", view.runtimeConfirmed());
        details.put("runtimeHash", view.runtimeHash());
        details.put("verifiedAt", view.verifiedAt());
        if (view.ok()) {
            return AgentActionOutcome.success(action.id(), view.code(), view.message(), details);
        }
        return AgentActionOutcome.rejectedWithDetails(action.id(), view.message(), details);
    }

    /** L'identifiant de ressource : même forme que le contenu du projet, sans séparateur. */
    private static String publishSlug(AgentAction action) {
        String raw = firstNonBlank(action.param("id"), action.param("slug"));
        if (raw == null) {
            return null;
        }
        String clean = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return clean.matches("[a-z0-9][a-z0-9_-]{0,63}") ? clean : null;
    }

    /** Bornes de saisie, miroir de {@code BuildingSite} — revérifiées ici, jamais supposées. */
    private static final int BUILD_SITE_NAME_MAX = 64;
    private static final int BUILD_SITE_DESCRIPTION_MAX = 500;

    private static String buildSiteId(AgentAction action) {
        String raw = firstNonBlank(action.param("id"), action.param("site_id"));
        if (raw == null) {
            return null;
        }
        String id = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return BUILD_SITE_ID.matcher(id).matches() ? id : null;
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
                row.put("questsDelivering", n.questsDelivering());
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
                // Localisation : copiée explicitement, comme le reste de la ligne. Oublier ces
                // clés ici faisait disparaître la position entre le registre Citizens (qui la
                // connaît) et le panel (qui affichait « position inconnue ») — le défaut constaté
                // sur Andy et Tania. Les clés absentes resteraient silencieusement nulles.
                row.put("world", c.world());
                row.put("x", c.x());
                row.put("y", c.y());
                row.put("z", c.z());
                row.put("yaw", c.yaw());
                row.put("pitch", c.pitch());
                row.put("liveLocation", c.liveLocation());
                row.put("shouldSpawn", c.shouldSpawn());
                row.put("chunkLoaded", c.chunkLoaded());
                // Comportements (issue #165). Copiés explicitement comme le reste de la ligne — la
                // localisation avait disparu exactement pour avoir été oubliée ici. Une valeur nulle
                // veut dire INCONNU (build Citizens qui n'expose pas le trait), jamais « désactivé ».
                row.put("lookCloseEnabled", c.lookCloseEnabled());
                row.put("lookCloseRange", c.lookCloseRange());
                row.put("wanderEnabled", c.wanderEnabled());
                row.put("wanderProvider", c.wanderProvider());
                row.put("wanderWaypoints", c.wanderWaypoints());
                row.put("wanderWorld", c.wanderWorld());
                row.put("wanderX", c.wanderX());
                row.put("wanderY", c.wanderY());
                row.put("wanderZ", c.wanderZ());
                row.put("wanderXRange", c.wanderXRange());
                row.put("wanderYRange", c.wanderYRange());
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

    /**
     * {@code npc.definition.delete} (#226) : supprime la définition logique SEULE.
     *
     * <p>Le paramètre {@code expect_dialogue} n'est pas une option de confort : il porte ce que
     * l'écran croyait vrai. S'il ne correspond plus, l'opération est refusée — une page périmée ne
     * doit pas pouvoir décider d'une suppression.</p>
     */
    private CompletableFuture<AgentActionOutcome> npcDefinitionDelete(AgentAction action) {
        String npcId = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        String expectDialogue = trimOrNull(action.param("expect_dialogue"));
        return actions.npcDefinitionDelete(npcId, expectDialogue == null ? "" : expectDialogue)
                .thenApply(r -> mutationOutcome(action, r, "npc_id", npcId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /** {@code npc.citizens.unlink} (#226) : retire la liaison, laisse vivre le PNJ Citizens. */
    private CompletableFuture<AgentActionOutcome> npcCitizensUnlink(AgentAction action) {
        String npcId = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        Integer citizensId = parsePositiveInt(firstNonBlank(action.param("citizens_id"),
                action.param("citizens")));
        if (citizensId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « citizens_id » manquant ou invalide (entier positif). Il porte la "
                            + "liaison que l'écran croyait vraie : sans lui, on pourrait délier la "
                            + "mauvaise."));
        }
        return actions.npcCitizensUnlink(npcId, citizensId)
                .thenApply(r -> mutationOutcome(action, r, "npc_id", npcId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * {@code npc.citizens.delete} (#226) : détruit le PNJ Citizens physique.
     *
     * <p>L'identifiant numérique est <strong>obligatoire</strong>, et il est confronté à la liaison
     * réelle avant toute destruction. C'est la protection demandée par le ticket contre « supprimer
     * le mauvais Citizens ».</p>
     */
    private CompletableFuture<AgentActionOutcome> npcCitizensDelete(AgentAction action) {
        String npcId = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        Integer citizensId = parsePositiveInt(firstNonBlank(action.param("citizens_id"),
                action.param("citizens")));
        if (citizensId == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « citizens_id » manquant ou invalide (entier positif) : une "
                            + "destruction de PNJ Citizens ne se fait jamais sans cible explicite."));
        }
        return actions.npcCitizensDelete(npcId, citizensId)
                .thenApply(r -> mutationOutcome(action, r, "npc_id", npcId))
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

    /** {@code npc.citizens.rename} (#165) : renomme en jeu, jamais l'id logique. */
    private CompletableFuture<AgentActionOutcome> npcCitizensRename(AgentAction action) {
        String npcId = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        String name = firstNonBlank(action.param("name"));
        if (name == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « name » manquant."));
        }
        return actions.citizensRename(npcId, name)
                .thenApply(r -> mutationOutcome(action, r, "npc_id", npcId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /** {@code npc.citizens.move} : déplace un PNJ existant, sans le recréer ni le faire apparaître. */
    private CompletableFuture<AgentActionOutcome> npcCitizensMove(AgentAction action) {
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
        Double yaw = action.param("yaw") == null || action.param("yaw").isBlank()
                ? 0.0 : parseFinite(action.param("yaw"));
        Double pitch = action.param("pitch") == null || action.param("pitch").isBlank()
                ? 0.0 : parseFinite(action.param("pitch"));
        if (yaw == null || pitch == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « yaw » / « pitch » non fini."));
        }
        return actions.citizensMove(npcId, world, x, y, z, yaw.floatValue(), pitch.floatValue())
                .thenApply(r -> mutationOutcome(action, r, "npc_id", npcId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * {@code npc.citizens.lookclose} (#165). {@code enabled} est un <strong>état</strong>, pas une
     * bascule : il n'y a volontairement aucune valeur « inverser ». Une action rejouée — double
     * clic, retry réseau, rejeu par le cache d'idempotence — aboutit donc au même état.
     */
    private CompletableFuture<AgentActionOutcome> npcCitizensLookClose(AgentAction action) {
        String npcId = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        Boolean enabled = parseExplicitBoolean(action.param("enabled"));
        if (enabled == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « enabled » manquant : il doit valoir « true » ou « false ». "
                            + "Aucune bascule implicite n'est acceptée."));
        }
        String rawRange = trimOrNull(action.param("range"));
        Double range = null;
        if (rawRange != null) {
            range = parseFinite(rawRange);
            if (range == null || range <= 0 || range > LOOKCLOSE_MAX_RANGE) {
                return done(AgentActionOutcome.rejected(action.id(),
                        "Paramètre « range » hors bornes (0 exclu à " + (int) LOOKCLOSE_MAX_RANGE + " blocs)."));
            }
        }
        return actions.citizensLookClose(npcId, enabled, range)
                .thenApply(r -> mutationOutcome(action, r, "npc_id", npcId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * {@code npc.citizens.wander} (#165). Même discipline d'état explicite que
     * {@link #npcCitizensLookClose}. L'ancre est optionnelle : absente, le plugin reprend la
     * position actuellement connue du PNJ — jamais une position inventée.
     */
    private CompletableFuture<AgentActionOutcome> npcCitizensWander(AgentAction action) {
        String npcId = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        Boolean enabled = parseExplicitBoolean(action.param("enabled"));
        if (enabled == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « enabled » manquant : il doit valoir « true » ou « false »."));
        }
        String world = trimOrNull(action.param("world"));
        if (world != null && !WORLD_NAME.matcher(world).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « world » invalide."));
        }
        Double x = parseFinite(action.param("x"));
        Double y = parseFinite(action.param("y"));
        Double z = parseFinite(action.param("z"));
        // Ancre partielle = refus net : on ne complète jamais une coordonnée manquante par une
        // supposition, et on ne déplace jamais l'ancre « à peu près ».
        boolean anyAnchor = world != null || x != null || y != null || z != null;
        boolean fullAnchor = world != null && x != null && y != null && z != null;
        if (anyAnchor && !fullAnchor) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Ancre incomplète : « world », « x », « y » et « z » vont ensemble. "
                            + "Les laisser tous vides reprend la position actuelle du PNJ."));
        }
        Integer xRange = parsePositiveInt(trimOrNull(action.param("x_range")));
        Integer yRange = parseNonNegativeInt(trimOrNull(action.param("y_range")));
        if (xRange == null || xRange > WANDER_MAX_X_RANGE) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « x_range » manquant ou hors bornes (1 à " + WANDER_MAX_X_RANGE + " blocs)."));
        }
        if (yRange == null || yRange > WANDER_MAX_Y_RANGE) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « y_range » manquant ou hors bornes (0 à " + WANDER_MAX_Y_RANGE + " blocs)."));
        }
        boolean confirmReplace = Boolean.TRUE.equals(parseExplicitBoolean(action.param("confirm_replace")));
        return actions.citizensWander(npcId, enabled, world, x, y, z, xRange, yRange, confirmReplace)
                .thenApply(r -> mutationOutcome(action, r, "npc_id", npcId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * Borne de portée du regard propre au panel (Citizens n'en impose aucune) : au-delà, le trait
     * compare la distance de chaque joueur à chaque tick pour un effet invisible en jeu.
     */
    private static final double LOOKCLOSE_MAX_RANGE = 64.0;

    /** Bornes de zone de promenade propres au panel : une zone plus large n'est plus « bornée ». */
    private static final int WANDER_MAX_X_RANGE = 64;

    private static final int WANDER_MAX_Y_RANGE = 32;

    /**
     * {@code true}/{@code false} uniquement — jamais « inverser », jamais de défaut implicite.
     * C'est ce qui garantit qu'un rejeu de l'action ne bascule pas l'état.
     */
    private static Boolean parseExplicitBoolean(String raw) {
        String v = trimOrNull(raw);
        if (v == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(v) || "0".equals(v) || "off".equalsIgnoreCase(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Integer parseNonNegativeInt(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code npc.citizens.skin} (#165) : lien MineSkin <em>ou</em> pseudo Minecraft, revalidé côté
     * plugin. {@code skin_source} vaut {@code url} (défaut, rétrocompatible) ou {@code player}.
     */
    private CompletableFuture<AgentActionOutcome> npcCitizensSkin(AgentAction action) {
        String npcId = firstNonBlank(action.param("npc_id"), action.param("id"));
        if (npcId == null || !NPC_ID.matcher(npcId).matches()) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « npc_id » manquant ou invalide."));
        }
        boolean byPlayerName = "player".equalsIgnoreCase(trimOrNull(action.param("skin_source")));
        String value = byPlayerName
                ? firstNonBlank(action.param("skin_player"), action.param("skin_url"), action.param("url"))
                : firstNonBlank(action.param("skin_url"), action.param("url"));
        if (value == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    byPlayerName ? "Paramètre « skin_player » manquant." : "Paramètre « skin_url » manquant."));
        }
        return actions.citizensSkin(npcId, value, byPlayerName)
                .thenApply(r -> mutationOutcome(action, r, "npc_id", npcId))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /** Projection commune d'un {@link AgentActions.MutationResult} en issue d'action. */
    private static AgentActionOutcome mutationOutcome(AgentAction action, AgentActions.MutationResult r,
                                                      String detailKey, String detailValue) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", r.code());
        details.put(detailKey, detailValue);
        details.put("effects", r.effects());
        if (r.ok()) {
            return AgentActionOutcome.success(action.id(), r.code(), r.message(), details);
        }
        return new AgentActionOutcome(action.id(), AgentActionOutcome.FAILED, r.code(), r.message(),
                details, java.time.Instant.now());
    }

    /** {@code npc.citizens.create} (#81 phase 2) : paramètres métier stricts, aucun spawn si un contrôle échoue. */
    /**
     * {@code npc.citizens.provision} : création complète d'un PNJ — définition, apparition, liaison
     * et skin optionnel — en une seule opération atomique. La position est optionnelle : fournie,
     * elle est validée telle quelle ; omise, le moteur cherche un emplacement sûr près du Guide.
     */
    private CompletableFuture<AgentActionOutcome> npcCitizensProvision(AgentAction action) {
        String displayName = trimOrNull(action.param("display_name"));
        if (displayName == null) {
            return done(AgentActionOutcome.rejected(action.id(), "Paramètre « display_name » manquant."));
        }
        boolean byPlayerName = "player".equalsIgnoreCase(trimOrNull(action.param("skin_source")));
        String skin = byPlayerName
                ? trimOrNull(action.param("skin_player"))
                : trimOrNull(action.param("skin_url"));

        String world = trimOrNull(action.param("world"));
        Double x = parseFinite(action.param("x"));
        Double y = parseFinite(action.param("y"));
        Double z = parseFinite(action.param("z"));
        Double yaw = action.param("yaw") == null || action.param("yaw").isBlank()
                ? null : parseFinite(action.param("yaw"));
        Double pitch = action.param("pitch") == null || action.param("pitch").isBlank()
                ? null : parseFinite(action.param("pitch"));

        return actions.citizensProvision(displayName, skin, byPlayerName, world, x, y, z,
                        yaw == null ? null : yaw.floatValue(), pitch == null ? null : pitch.floatValue())
                .thenApply(r -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("code", r.code());
                    details.put("npc_id", r.npcId() == null ? "" : r.npcId());
                    details.put("citizens_id", r.citizensId() == null ? -1 : r.citizensId());
                    details.put("display_name", r.displayName() == null ? "" : r.displayName());
                    details.put("world", r.world() == null ? "" : r.world());
                    details.put("x", r.x() == null ? 0.0 : r.x());
                    details.put("y", r.y() == null ? 0.0 : r.y());
                    details.put("z", r.z() == null ? 0.0 : r.z());
                    details.put("skin_note", r.skinNote() == null ? "" : r.skinNote());
                    details.put("rolled_back", r.rolledBack());
                    details.put("effects", r.effects());
                    if (r.ok()) {
                        return AgentActionOutcome.success(action.id(),
                                r.citizensId() == null ? r.code() : String.valueOf(r.citizensId()),
                                r.message(), details);
                    }
                    return new AgentActionOutcome(action.id(), AgentActionOutcome.FAILED, r.code(), r.message(),
                            details, java.time.Instant.now());
                })
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

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
                    "Un choix redirige vers un nœud (« next_node_id ») OU ferme le dialogue (« close=true »)."));
        }

        // Édition structurée optionnelle. Paramètre absent = « keep » : on ne touche à rien, et le
        // choix garde son action de quête / sa condition d'état telles quelles.
        DialogueDefinitionEditor.QuestActionEdit questAction;
        try {
            questAction = questActionEdit(action);
        } catch (IllegalArgumentException e) {
            return done(AgentActionOutcome.rejected(action.id(), e.getMessage()));
        }
        DialogueDefinitionEditor.QuestConditionEdit questCondition;
        try {
            questCondition = questConditionEdit(action);
        } catch (IllegalArgumentException e) {
            return done(AgentActionOutcome.rejected(action.id(), e.getMessage()));
        }

        return actions.dialogueChoiceUpdate(dialogueId, nodeId, index, choiceText, next, close,
                        questAction, questCondition)
                .thenApply(r -> toOutcome(action, r))
                .exceptionally(err -> AgentActionOutcome.failed(action.id(), "Échec : " + rootName(err)));
    }

    /**
     * {@code quest_action} ∈ {@code keep} (défaut) / {@code none} / {@code START_QUEST} /
     * {@code ADVANCE_QUEST} / {@code TURN_IN_QUEST}, avec {@code quest_id} pour les trois derniers.
     */
    private static DialogueDefinitionEditor.QuestActionEdit questActionEdit(AgentAction action) {
        String mode = trimOrNull(action.param("quest_action"));
        if (mode == null || mode.equalsIgnoreCase("keep")) {
            return DialogueDefinitionEditor.QuestActionEdit.keep();
        }
        if (mode.equalsIgnoreCase("none")) {
            return DialogueDefinitionEditor.QuestActionEdit.remove();
        }
        ActionType type;
        try {
            type = ActionType.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Paramètre « quest_action » inconnu : « " + mode + " ».");
        }
        if (!DialogueDefinitionEditor.QUEST_ACTION_TYPES.contains(type)) {
            throw new IllegalArgumentException("« quest_action » doit être START_QUEST, ADVANCE_QUEST ou TURN_IN_QUEST.");
        }
        String questId = questRef(action.param("quest_id"));
        if (questId == null) {
            throw new IllegalArgumentException("Paramètre « quest_id » manquant ou invalide pour l'action de quête.");
        }
        return DialogueDefinitionEditor.QuestActionEdit.set(type, questId);
    }

    /**
     * {@code quest_condition} ∈ {@code keep} (défaut) / {@code none} / un {@link QuestState}, avec
     * {@code condition_quest_id} et l'option {@code condition_negate}.
     */
    private static DialogueDefinitionEditor.QuestConditionEdit questConditionEdit(AgentAction action) {
        String mode = trimOrNull(action.param("quest_condition"));
        if (mode == null || mode.equalsIgnoreCase("keep")) {
            return DialogueDefinitionEditor.QuestConditionEdit.keep();
        }
        if (mode.equalsIgnoreCase("none")) {
            return DialogueDefinitionEditor.QuestConditionEdit.remove();
        }
        QuestState state;
        try {
            state = QuestState.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Paramètre « quest_condition » inconnu : « " + mode + " ».");
        }
        String questId = questRef(action.param("condition_quest_id"));
        if (questId == null) {
            throw new IllegalArgumentException("Paramètre « condition_quest_id » manquant ou invalide pour la condition.");
        }
        return DialogueDefinitionEditor.QuestConditionEdit.set(state, questId, isTrue(action.param("condition_negate")));
    }

    /** Référence de quête normalisée en {@code namespace:key} minuscule, ou {@code null} si invalide. */
    private static String questRef(String raw) {
        String value = trimOrNull(raw);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(java.util.Locale.ROOT);
        if (!DIALOGUE_REF.matcher(value).matches()) {
            return null;
        }
        return value.contains(":") ? value : "rpgquest:" + value;
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

    private CompletableFuture<AgentActionOutcome> resetPreview(AgentAction action, ResetScope scope) {
        return withPlayer(action, (uuid, name) -> actions.resetPreview(uuid, scope).thenApply(preview -> {
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
            // Issue #235 : la portée voyage avec l'aperçu, pour que le panel n'ait pas à la deviner
            // et qu'un aperçu « conserve l'inventaire » ne puisse pas s'afficher sous un bouton qui
            // le vide.
            details.put("scope", scope.name());
            details.put("scope_label", scope.label());
            details.put("wipes_inventory", scope.wipesInventory());
            return AgentActionOutcome.success(action.id(), name,
                    "Aperçu — " + scope.label() + " de " + name + " (aucune écriture).", details);
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

    private CompletableFuture<AgentActionOutcome> resetConfirm(AgentAction action, ResetScope scope) {
        if (!isTrue(action.param("confirm"))) {
            return done(AgentActionOutcome.rejected(action.id(),
                    scope.label() + " : paramètre « confirm=true » obligatoire (garde-fou)."));
        }
        return withResolvedUuid(action, (uuid, name) ->
                actions.resetConfirm(uuid, name, scope).thenApply(r -> toOutcome(action, r)));
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

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
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
