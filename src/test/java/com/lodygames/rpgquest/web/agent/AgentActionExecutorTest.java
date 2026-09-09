package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Exécuteur d'actions de l'agent : whitelist stricte, validation des paramètres aux trois couches,
 * délégation aux services métier via {@link AgentActions} (jamais une commande texte).
 */
class AgentActionExecutorTest {

    private static final UUID RONDOUDOU = UUID.fromString("00000000-0000-0000-0000-000000009000");

    private final PlayerDirectory directory = ref -> {
        if ("Rondoudou9000".equalsIgnoreCase(ref) || RONDOUDOU.toString().equals(ref)) {
            return CompletableFuture.completedFuture(Optional.of(new PlayerDirectory.ResolvedPlayer(RONDOUDOU, "Rondoudou9000")));
        }
        return CompletableFuture.completedFuture(Optional.empty());
    };

    private final PlayerVariables variables = (uuid, key) -> {
        if (RONDOUDOU.equals(uuid) && "CLAIM_TIER_1".equals(key)) {
            return CompletableFuture.completedFuture(Optional.of("false"));
        }
        return CompletableFuture.completedFuture(Optional.empty());
    };

    private final FakeAgentActions actions = new FakeAgentActions();
    private final AgentActionExecutor executor = new AgentActionExecutor(directory, variables, actions);

    private AgentActionOutcome run(AgentAction action) {
        return executor.execute(action).join();
    }

    // ---- Whitelist + player.variable.get (issue #51) ---------------------------------------

    @Test
    void unknownTypeIsRejectedWithoutExecuting() {
        AgentActionOutcome outcome = run(new AgentAction("a1", "server.shutdown", Map.of()));
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
        assertTrue(outcome.message().contains("non whitelisté"));
    }

    @Test
    void arbitraryConsoleCommandIsRejected() {
        AgentActionOutcome outcome = run(new AgentAction("a1b", "console.run", Map.of("command", "op me")));
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
    }

    @Test
    void missingKeyIsRejected() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("a2", "player.variable.get", Map.of("player", "Rondoudou9000"))).status());
    }

    @Test
    void missingPlayerIsRejected() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("a3", "player.variable.get", Map.of("key", "CLAIM_TIER_1"))).status());
    }

    @Test
    void unknownPlayerFails() {
        AgentActionOutcome outcome = run(new AgentAction("a4", "player.variable.get",
                Map.of("player", "GhostPlayer", "key", "CLAIM_TIER_1")));
        assertEquals(AgentActionOutcome.FAILED, outcome.status());
        assertTrue(outcome.message().contains("Joueur inconnu"));
    }

    @Test
    void readsExistingVariable() {
        AgentActionOutcome outcome = run(new AgentAction("a5", "player.variable.get",
                Map.of("player", "Rondoudou9000", "key", "CLAIM_TIER_1")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("false", outcome.value());
        assertEquals(Boolean.TRUE, outcome.details().get("present"));
    }

    @Test
    void absentVariableIsStillSuccessWithNullValue() {
        AgentActionOutcome outcome = run(new AgentAction("a6", "player.variable.get",
                Map.of("player", "Rondoudou9000", "key", "NEVER_SET")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertNull(outcome.value());
        assertEquals(Boolean.FALSE, outcome.details().get("present"));
    }

    // ---- Lectures catalogue / roster ------------------------------------------------------

    @Test
    void playerListReturnsRoster() {
        AgentActionOutcome outcome = run(new AgentAction("p1", "player.list", Map.of()));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("1", outcome.value());
        assertTrue(outcome.details().containsKey("players"));
    }

    // ---- #96 : annuaire + modération ----------------------------------------------------

    @Test
    void playerCatalogSerialisesOnlineAndOfflineWithCounts() {
        AgentActionOutcome outcome = run(new AgentAction("pc1", "player.catalog", Map.of("limit", "500")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(500, actions.lastCatalogLimit);
        assertEquals("2", outcome.value());
        assertEquals(2L, ((Number) outcome.details().get("total")).longValue());
        assertEquals(1L, ((Number) outcome.details().get("online")).longValue());
        assertEquals(1L, ((Number) outcome.details().get("offline")).longValue());
        assertEquals(1L, ((Number) outcome.details().get("banned")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) outcome.details().get("players");
        Map<String, Object> steve = rows.stream().filter(r -> "Steve".equals(r.get("name"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, steve.get("banned"));
        assertEquals("spam", steve.get("banReason"));
        assertEquals(Boolean.FALSE, steve.get("online"));
        assertFalse(steve.containsKey("world"), "pas de position pour un joueur hors ligne");
    }

    @Test
    void playerCatalogWithoutLimitAppliesSafetyCap() {
        run(new AgentAction("pc2", "player.catalog", Map.of()));
        assertEquals(5_000, actions.lastCatalogLimit, "cap de sécurité quand aucune limite n'est demandée");
    }

    @Test
    void playerBanRequiresAReasonAndResolvesTheUuid() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("b0", "player.ban", Map.of("player", "Rondoudou9000"))).status());

        AgentActionOutcome ok = run(new AgentAction("b1", "player.ban",
                Map.of("player", "Rondoudou9000", "reason", "comportement toxique")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("comportement toxique", actions.lastBanReason);
        assertEquals(RONDOUDOU, actions.lastBanUuid);
    }

    @Test
    void playerBanOnUnknownPlayerFailsCleanly() {
        AgentActionOutcome outcome = run(new AgentAction("b2", "player.ban",
                Map.of("player", "GhostPlayer", "reason", "x")));
        assertEquals(AgentActionOutcome.FAILED, outcome.status());
        assertTrue(outcome.message().contains("Joueur inconnu"));
    }

    @Test
    void playerUnbanResolvesAndCalls() {
        AgentActionOutcome ok = run(new AgentAction("u1", "player.unban", Map.of("player", "Rondoudou9000")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertTrue(actions.unbanCalled);
    }

    @Test
    void questListReturnsDefinitions() {
        AgentActionOutcome outcome = run(new AgentAction("q0", "quest.list", Map.of()));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        @SuppressWarnings("unchecked")
        List<Object> quests = (List<Object>) outcome.details().get("quests");
        assertEquals(1, quests.size());
    }

    @Test
    void npcListReturnsCatalogWithDefinitionsAndWarnings() {
        AgentActionOutcome outcome = run(new AgentAction("n0", "npc.list", Map.of()));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertTrue(outcome.details().containsKey("npcs"));
        assertEquals(List.of("guard", "woodcutter_bob"), outcome.details().get("canonicalIds"));
        assertEquals(List.of("guard"), outcome.details().get("definedIds"));
        assertEquals(1, outcome.details().get("withDefinition"));
        assertEquals(1, outcome.details().get("withWarnings"));
    }

    @Test
    void npcDefinitionCreateValidatesIdAndDelegates() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("nd0", "npc.definition.create",
                Map.of("npc_id", "Bad Id", "display_name", "X"))).status());
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("nd1", "npc.definition.create",
                Map.of("npc_id", "woodcutter_bob"))).status(), "display_name obligatoire");
        AgentActionOutcome ok = run(new AgentAction("nd2", "npc.definition.create",
                Map.of("npc_id", "woodcutter_bob", "display_name", "Bûcheron Bob",
                        "dialogue_id", "rpgquest:woodcutter_bob", "enabled", "true")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("woodcutter_bob", actions.lastNpcCreateId);
    }

    @Test
    void npcCitizensListReturnsRoster() {
        AgentActionOutcome outcome = run(new AgentAction("cl0", "npc.citizens.list", Map.of()));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertTrue(outcome.details().containsKey("citizens"));
        assertEquals(1, outcome.details().get("available"));
        assertEquals(Boolean.TRUE, outcome.details().get("citizensAvailable"));
    }

    @Test
    void npcCitizensLinkValidatesAndDelegates() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("lk0", "npc.citizens.link",
                Map.of("npc_id", "woodcutter_bob"))).status(), "citizens_id obligatoire");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("lk1", "npc.citizens.link",
                Map.of("npc_id", "Bad Id", "citizens_id", "14"))).status());
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("lk2", "npc.citizens.link",
                Map.of("npc_id", "woodcutter_bob", "citizens_id", "0"))).status(), "entier positif requis");
        AgentActionOutcome ok = run(new AgentAction("lk3", "npc.citizens.link",
                Map.of("npc_id", "woodcutter_bob", "citizens_id", "14")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("woodcutter_bob", actions.lastLinkNpcId);
        assertEquals(14, actions.lastLinkCitizensId);
    }

    @Test
    void npcCitizensCreateValidatesParamsAndDelegates() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("cc0", "npc.citizens.create",
                Map.of("world", "world_hub", "x", "1", "y", "64", "z", "2"))).status(), "npc_id obligatoire");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("cc1", "npc.citizens.create",
                Map.of("npc_id", "woodcutter_bob", "world", "bad world!", "x", "1", "y", "64", "z", "2"))).status(),
                "world invalide");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("cc2", "npc.citizens.create",
                Map.of("npc_id", "woodcutter_bob", "world", "world_hub", "x", "NaN", "y", "64", "z", "2"))).status(),
                "coordonnée non finie");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("cc3", "npc.citizens.create",
                Map.of("npc_id", "woodcutter_bob", "world", "world_hub", "y", "64", "z", "2"))).status(),
                "x manquant");

        AgentActionOutcome ok = run(new AgentAction("cc4", "npc.citizens.create", Map.of(
                "npc_id", "woodcutter_bob", "world", "world_hub",
                "x", "125.5", "y", "64", "z", "-82.5", "yaw", "90", "pitch", "0")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("woodcutter_bob", actions.lastCreateNpcId);
        assertEquals("world_hub", actions.lastCreateWorld);
        assertEquals(125.5, actions.lastCreateX);
        assertEquals(90.0f, actions.lastCreateYaw);
        assertEquals(Integer.valueOf(31), ok.details().get("citizens_id"));
        assertEquals(Boolean.FALSE, ok.details().get("rolled_back"));
    }

    @Test
    void dialogueListSerialisesGraphActionsAndIssues() {
        AgentActionOutcome out = run(new AgentAction("dl0", "dialogue.list", Map.of()));
        assertEquals(AgentActionOutcome.SUCCESS, out.status());
        assertEquals(1, out.details().get("total"));
        assertEquals(3, out.details().get("nodeTotal"));
        String json = com.lodygames.rpgquest.web.Json.write(out.details());
        assertTrue(json.contains("\"id\":\"rpgquest:guard\"") && json.contains("\"startNodeId\":\"greeting\""));
        assertTrue(json.contains("\"kind\":\"START_QUEST\"") && json.contains("\"target\":\"rpgquest:first_steps\""));
        assertTrue(json.contains("\"reachable\":false"), "nœud orphelin marqué");
        assertTrue(json.contains("\"loadIssues\"") && json.contains("broken.yml"));
        assertTrue(json.contains("\"declaredButMissing\"") && json.contains("rpgquest:ghost"));
        assertTrue(json.contains("\"linkedNpcIds\":[\"guard\"]"));
    }

    @Test
    void dialogueDefinitionCreateValidatesAndDelegates() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dc0", "dialogue.definition.create",
                Map.of("speaker", "Bob", "text", "Salut"))).status(), "key obligatoire");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dc1", "dialogue.definition.create",
                Map.of("key", "Bad Key", "speaker", "Bob", "text", "Salut"))).status());
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dc2", "dialogue.definition.create",
                Map.of("key", "woodcutter_bob", "speaker", "Bob"))).status(), "text obligatoire");
        AgentActionOutcome ok = run(new AgentAction("dc3", "dialogue.definition.create",
                Map.of("key", "woodcutter_bob", "speaker", "Bûcheron Bob", "text", "<white>Bonjour.</white>")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("woodcutter_bob", actions.lastDialogueKey);
        assertEquals("Bûcheron Bob", actions.lastDialogueSpeaker);
    }

    @Test
    void dialogueNodeUpdateValidatesAndNormalisesDialogueId() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dn0", "dialogue.node.update",
                Map.of("node_id", "greeting", "speaker", "Garde", "text", "Salut"))).status(), "dialogue_id obligatoire");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dn1", "dialogue.node.update",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "Bad Node", "speaker", "G", "text", "T"))).status());
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dn2", "dialogue.node.update",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "greeting", "speaker", "G",
                        "text", "ligne1\nligne2"))).status(), "texte multi-ligne refusé");
        AgentActionOutcome ok = run(new AgentAction("dn3", "dialogue.node.update",
                Map.of("dialogue_id", "guard", "node_id", "Greeting", "speaker", "Capitaine", "text", "<y>Salut</y>")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("node.update", actions.lastEdit);
        assertEquals("rpgquest:guard", actions.lastEditDialogueId, "dialogue_id normalisé");
        assertEquals("greeting", actions.lastEditNodeId, "node_id normalisé en minuscules");
        assertEquals("Capitaine", actions.lastDialogueSpeaker);
    }

    @Test
    void dialogueNodeCreateDelegates() {
        AgentActionOutcome ok = run(new AgentAction("dnc", "dialogue.node.create",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "farewell", "speaker", "Garde", "text", "Adieu")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("node.create", actions.lastEdit);
        assertEquals("farewell", actions.lastEditNodeId);
    }

    @Test
    void dialogueChoiceAddRequiresExactlyOneTarget() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dca0", "dialogue.choice.add",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "accepted", "choice_text", "Retour"))).status(),
                "ni next ni close");
        AgentActionOutcome next = run(new AgentAction("dca1", "dialogue.choice.add",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "accepted", "choice_text", "Retour",
                        "next_node_id", "greeting")));
        assertEquals(AgentActionOutcome.SUCCESS, next.status());
        assertEquals("choice.add", actions.lastEdit);
        assertEquals("greeting", actions.lastEditNext);
        assertFalse(actions.lastEditClose);

        AgentActionOutcome close = run(new AgentAction("dca2", "dialogue.choice.add",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "accepted", "choice_text", "Fin", "close", "true")));
        assertEquals(AgentActionOutcome.SUCCESS, close.status());
        assertTrue(actions.lastEditClose);
        assertNull(actions.lastEditNext);
    }

    @Test
    void dialogueChoiceUpdateAndDeleteValidateIndex() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dcu0", "dialogue.choice.update",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "greeting", "choice_index", "-1",
                        "choice_text", "X", "close", "true"))).status());
        AgentActionOutcome upd = run(new AgentAction("dcu1", "dialogue.choice.update",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "greeting", "choice_index", "1",
                        "choice_text", "Je refuse", "next_node_id", "accepted")));
        assertEquals(AgentActionOutcome.SUCCESS, upd.status());
        assertEquals("choice.update", actions.lastEdit);
        assertEquals(1, actions.lastEditChoiceIndex);

        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dcd0", "dialogue.choice.delete",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "greeting", "choice_index", "999"))).status());
        AgentActionOutcome del = run(new AgentAction("dcd1", "dialogue.choice.delete",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "greeting", "choice_index", "1")));
        assertEquals(AgentActionOutcome.SUCCESS, del.status());
        assertEquals("choice.delete", actions.lastEdit);
    }

    @Test
    void npcCitizensCreateBindFailureIsAReadableFailedOutcome() {
        actions.createOk = false;
        actions.createCode = "BIND_FAILED_ROLLED_BACK";
        actions.createRolledBack = true;
        AgentActionOutcome out = run(new AgentAction("cc5", "npc.citizens.create", Map.of(
                "npc_id", "woodcutter_bob", "world", "world_hub", "x", "1", "y", "64", "z", "2")));
        assertEquals(AgentActionOutcome.FAILED, out.status());
        assertEquals("BIND_FAILED_ROLLED_BACK", out.value());
        assertEquals(Boolean.TRUE, out.details().get("rolled_back"));
    }

    @Test
    void questGiverSetValidatesAndDelegates() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("qg0", "quest.giver.set",
                Map.of("quest_id", "rpgquest:woodcutters_request"))).status(), "npc_id obligatoire");
        AgentActionOutcome ok = run(new AgentAction("qg1", "quest.giver.set",
                Map.of("quest_id", "rpgquest:woodcutters_request", "npc_id", "woodcutter_bob")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("rpgquest:woodcutters_request", actions.lastGiverQuestId);
        assertEquals("woodcutter_bob", actions.lastGiverNpcId);
    }

    @Test
    void questPlayerStatusResolvesPlayer() {
        AgentActionOutcome outcome = run(new AgentAction("q1", "quest.player.status",
                Map.of("player", "Rondoudou9000")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(RONDOUDOU, actions.lastQuestStatusUuid);
    }

    @Test
    void resetPreviewIsReadOnlyAndResolvesPlayer() {
        AgentActionOutcome outcome = run(new AgentAction("r1", "player.resetnew.preview",
                Map.of("player", "Rondoudou9000")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertTrue(outcome.details().containsKey("lines"));
        assertFalse(actions.resetConfirmCalled, "preview ne doit jamais confirmer");
    }

    // ---- Mutations : validation + délégation --------------------------------------------

    @Test
    void itemGiveRejectsNonPositiveAmount() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("g1", "player.item.give",
                Map.of("player", "Rondoudou9000", "item_id", "rpgquest:rune_rappel", "amount", "0"))).status());
    }

    @Test
    void itemGiveRejectsAmountAboveCap() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("g2", "player.item.give",
                Map.of("player", "Rondoudou9000", "item_id", "rpgquest:rune_rappel", "amount", "999"))).status());
    }

    @Test
    void itemGiveRejectsMissingItemId() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("g3", "player.item.give",
                Map.of("player", "Rondoudou9000"))).status());
    }

    @Test
    void itemGiveDelegatesToService() {
        AgentActionOutcome outcome = run(new AgentAction("g4", "player.item.give",
                Map.of("player", "Rondoudou9000", "item_id", "rpgquest:rune_rappel", "amount", "2")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("rpgquest:rune_rappel", actions.lastGiveItemId);
        assertEquals(2, actions.lastGiveAmount);
    }

    @Test
    void questStartRejectsMissingQuestId() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("s1", "quest.start",
                Map.of("player", "Rondoudou9000"))).status());
    }

    @Test
    void questStartPassesForceFlag() {
        run(new AgentAction("s2", "quest.start",
                Map.of("player", "Rondoudou9000", "quest_id", "rpgquest:crystal_hunt", "force", "true")));
        assertTrue(actions.lastQuestStartForce);
    }

    @Test
    void questCompleteBusinessRejectionBecomesReadableFailure() {
        actions.mutationOk = false;
        actions.mutationCode = "ALREADY_COMPLETED";
        AgentActionOutcome outcome = run(new AgentAction("s3", "quest.complete",
                Map.of("player", "Rondoudou9000", "quest_id", "rpgquest:crystal_hunt")));
        assertEquals(AgentActionOutcome.FAILED, outcome.status());
        assertEquals("ALREADY_COMPLETED", outcome.value());
    }

    @Test
    void storyAdvanceRejectsBadStoryId() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("s4", "story.advance",
                Map.of("player", "Rondoudou9000", "story_id", "Not A Valid Id!"))).status());
    }

    @Test
    void storyCompleteDelegates() {
        AgentActionOutcome outcome = run(new AgentAction("s5", "story.complete",
                Map.of("player", "Rondoudou9000", "story_id", "main_story")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("main_story", actions.lastStoryId);
    }

    @Test
    void resetConfirmRequiresExplicitConfirmParam() {
        AgentActionOutcome noConfirm = run(new AgentAction("rc1", "player.resetnew.confirm",
                Map.of("player", "Rondoudou9000")));
        assertEquals(AgentActionOutcome.REJECTED, noConfirm.status());
        assertFalse(actions.resetConfirmCalled);

        AgentActionOutcome withConfirm = run(new AgentAction("rc2", "player.resetnew.confirm",
                Map.of("player", "Rondoudou9000", "confirm", "true")));
        assertEquals(AgentActionOutcome.SUCCESS, withConfirm.status());
        assertTrue(actions.resetConfirmCalled);
    }

    @Test
    void variableSetRequiresValueAndValidKey() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("v1", "player.variable.set",
                Map.of("player", "Rondoudou9000", "key", "CLAIM_TIER_1"))).status());
        AgentActionOutcome ok = run(new AgentAction("v2", "player.variable.set",
                Map.of("player", "Rondoudou9000", "key", "CLAIM_TIER_1", "value", "true")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("true", actions.lastVariableValue);
    }

    // ---- Fake façade métier -----------------------------------------------------------

    private static final class FakeAgentActions implements AgentActions {
        UUID lastQuestStatusUuid;
        String lastGiveItemId;
        int lastGiveAmount;
        boolean lastQuestStartForce;
        String lastStoryId;
        String lastVariableValue;
        boolean resetConfirmCalled;
        boolean mutationOk = true;
        String mutationCode = "OK";
        int lastCatalogLimit = -999;
        String lastBanReason;
        UUID lastBanUuid;
        boolean unbanCalled;

        private CompletableFuture<MutationResult> mutation(String message) {
            return CompletableFuture.completedFuture(
                    new MutationResult(mutationOk, mutationCode, message, List.of()));
        }

        @Override
        public CompletableFuture<List<PlayerSummary>> onlinePlayers() {
            return CompletableFuture.completedFuture(List.of(
                    new PlayerSummary(RONDOUDOU.toString(), "Rondoudou9000", "world_hub", 1, 64, 2)));
        }

        @Override
        public List<QuestSummary> questDefinitions() {
            return List.of(new QuestSummary("rpgquest:crystal_hunt", "La chasse aux cristaux", "story",
                    false, List.of(),
                    List.of(new QuestStepSummary("hunt_spiders", List.of("Tuer 5 araignées (x5)"),
                            List.of(new ObjectiveSummary("KILL_ENTITY", "SPIDER", 5, "Tuer SPIDER (x5)")))),
                    List.of("+100 XP"),
                    List.of(new RewardSummary("EXPERIENCE", 100, null, null, null, "+100 XP")),
                    "guard", null));
        }

        @Override
        public CompletableFuture<List<QuestPlayerState>> questStatus(UUID playerId) {
            lastQuestStatusUuid = playerId;
            return CompletableFuture.completedFuture(List.of(
                    new QuestPlayerState("rpgquest:crystal_hunt", "La chasse aux cristaux", "NOT_STARTED", null, List.of())));
        }

        @Override
        public List<StorySummary> storyDefinitions() {
            return List.of(new StorySummary("main_story", "Histoire principale", List.of("rpgquest:first_steps")));
        }

        @Override
        public CompletableFuture<List<StoryPlayerState>> storyStatus(UUID playerId) {
            return CompletableFuture.completedFuture(List.of(
                    new StoryPlayerState("main_story", "Histoire principale", "NOT_STARTED", 1, 1, "rpgquest:first_steps")));
        }

        @Override
        public List<ItemSummary> itemDefinitions() {
            return List.of(new ItemSummary("rpgquest:rune_rappel", "Rune de rappel", "TOOL"));
        }

        String lastNpcCreateId;
        String lastNpcUpdateId;
        String lastGiverQuestId;
        String lastGiverNpcId;

        @Override
        public CompletableFuture<NpcCatalogView> npcDefinitions() {
            NpcSummary guard = new NpcSummary("guard", "Garde", true, true, 7, 1, true,
                    "Garde du village", "quest_giver", "rpgquest:guard", true, "rpgquest:guard", 6, 9,
                    List.of("rpgquest:first_steps"), List.of("rpgquest:crystal_hunt"),
                    List.of("rpgquest:crystal_hunt"),
                    List.of("DEFINITION", "BINDING", "DIALOGUE", "QUEST_GIVER", "QUEST_TALK"), "LINKED", List.of());
            NpcSummary woodcutter = new NpcSummary("woodcutter_bob", null, false, false, null, 0, true,
                    null, null, null, false, null, 0, 0,
                    List.of(), List.of(), List.of("rpgquest:woodcutters_request"), List.of("QUEST_TALK"),
                    "UNDEFINED_REFERENCE",
                    List.of(new NpcWarning("NO_DEFINITION", "error",
                            "Aucune définition logique RPGQuest pour « woodcutter_bob » (référencé par objectif « parler à »).")));
            return CompletableFuture.completedFuture(new NpcCatalogView(
                    List.of(woodcutter, guard), List.of("guard", "woodcutter_bob"), List.of("guard"),
                    true, 2, 1, 1, 1, 1));
        }

        @Override
        public CompletableFuture<MutationResult> npcDefinitionCreate(String id, String displayName, String dialogueId,
                                                                     String role, boolean enabled) {
            lastNpcCreateId = id;
            return mutation("create " + id);
        }

        @Override
        public CompletableFuture<MutationResult> npcDefinitionUpdate(String id, String displayName, String dialogueId,
                                                                     String role, boolean enabled) {
            lastNpcUpdateId = id;
            return mutation("update " + id);
        }

        @Override
        public CompletableFuture<MutationResult> questGiverSet(String questId, String npcId) {
            lastGiverQuestId = questId;
            lastGiverNpcId = npcId;
            return mutation("giver " + questId + " -> " + npcId);
        }

        String lastLinkNpcId;
        int lastLinkCitizensId;

        @Override
        public CompletableFuture<CitizensRosterView> citizensRoster() {
            return CompletableFuture.completedFuture(new CitizensRosterView(true, List.of(
                    new CitizensNpcSummary(6, "11111111-1111-1111-1111-111111111111", "Garde", "guard", false, true),
                    new CitizensNpcSummary(14, "22222222-2222-2222-2222-222222222222", "Bûcheron Bob", null, true, true)),
                    2, 1, 1));
        }

        @Override
        public CompletableFuture<MutationResult> citizensLink(String npcId, int citizensNumericId) {
            lastLinkNpcId = npcId;
            lastLinkCitizensId = citizensNumericId;
            return mutation("link " + npcId + " <-> #" + citizensNumericId);
        }

        String lastCreateNpcId;
        String lastCreateWorld;
        double lastCreateX;
        float lastCreateYaw;
        boolean createOk = true;
        String createCode = "CREATED";
        boolean createRolledBack = false;

        @Override
        public CompletableFuture<CitizensCreateResult> citizensCreate(String npcId, String world,
                                                                      double x, double y, double z,
                                                                      float yaw, float pitch) {
            lastCreateNpcId = npcId;
            lastCreateWorld = world;
            lastCreateX = x;
            lastCreateYaw = yaw;
            return CompletableFuture.completedFuture(new CitizensCreateResult(createOk, createCode,
                    createOk ? "créé" : "liaison impossible", createOk || createRolledBack ? 31 : null,
                    npcId, createOk ? List.of("Citizens #31 spawné") : List.of(), createRolledBack));
        }

        String lastDialogueKey;
        String lastDialogueSpeaker;
        String lastDialogueText;

        @Override
        public CompletableFuture<DialogueCatalogView> dialogueDefinitions() {
            DialogueActionSummary start = new DialogueActionSummary("START_QUEST", "rpgquest:first_steps", "",
                    "START_QUEST rpgquest:first_steps");
            DialogueConditionSummary cond = new DialogueConditionSummary("QUEST_STATE", "rpgquest:first_steps",
                    "NOT_STARTED", "QUEST_STATE rpgquest:first_steps = NOT_STARTED", false);
            DialogueChoiceSummary accept = new DialogueChoiceSummary("J'accepte", "accepted",
                    List.of(start), List.of(cond));
            DialogueChoiceSummary refuse = new DialogueChoiceSummary("Non merci", "", List.of(), List.of());
            DialogueNodeSummary greeting = new DialogueNodeSummary("greeting", "Garde", "<white>Bonjour.</white>",
                    true, true, List.of(accept, refuse));
            DialogueNodeSummary accepted = new DialogueNodeSummary("accepted", "Garde", "Bien.", false, true,
                    List.of(new DialogueChoiceSummary("OK", "", List.of(), List.of())));
            DialogueNodeSummary orphan = new DialogueNodeSummary("orphan", "Garde", "Personne ne me voit.",
                    false, false, List.of(new DialogueChoiceSummary("OK", "", List.of(), List.of())));
            DialogueSummary guard = new DialogueSummary("rpgquest:guard", "guard", "greeting",
                    List.of("guard"), 3, 4, List.of("rpgquest:first_steps"), List.of("rpgquest:first_steps"),
                    List.of(greeting, accepted, orphan),
                    List.of(new DialogueWarning("NODE_UNREACHABLE", "info", "le nœud « orphan » n'est atteignable…")));
            return CompletableFuture.completedFuture(new DialogueCatalogView(List.of(guard),
                    List.of(new DialogueLoadIssueSummary("broken.yml", "« nodes » est obligatoire.")),
                    List.of(new DialogueMissingDeclared("ghost", "rpgquest:ghost")), 1, 1, 3));
        }

        @Override
        public CompletableFuture<MutationResult> dialogueDefinitionCreate(String key, String speaker, String text) {
            lastDialogueKey = key;
            lastDialogueSpeaker = speaker;
            lastDialogueText = text;
            return mutation("dialogue " + key);
        }

        String lastEdit;
        String lastEditDialogueId;
        String lastEditNodeId;
        int lastEditChoiceIndex = -1;
        String lastEditNext;
        boolean lastEditClose;

        @Override
        public CompletableFuture<MutationResult> dialogueNodeUpdate(String dialogueId, String nodeId, String speaker,
                                                                    String text) {
            lastEdit = "node.update";
            lastEditDialogueId = dialogueId;
            lastEditNodeId = nodeId;
            lastDialogueSpeaker = speaker;
            lastDialogueText = text;
            return mutation("node.update " + nodeId);
        }

        @Override
        public CompletableFuture<MutationResult> dialogueNodeCreate(String dialogueId, String nodeId, String speaker,
                                                                    String text) {
            lastEdit = "node.create";
            lastEditDialogueId = dialogueId;
            lastEditNodeId = nodeId;
            lastDialogueSpeaker = speaker;
            lastDialogueText = text;
            return mutation("node.create " + nodeId);
        }

        @Override
        public CompletableFuture<MutationResult> dialogueChoiceAdd(String dialogueId, String nodeId, String choiceText,
                                                                   String nextNodeId, boolean close) {
            lastEdit = "choice.add";
            lastEditDialogueId = dialogueId;
            lastEditNodeId = nodeId;
            lastEditNext = nextNodeId;
            lastEditClose = close;
            return mutation("choice.add " + nodeId);
        }

        @Override
        public CompletableFuture<MutationResult> dialogueChoiceUpdate(String dialogueId, String nodeId, int choiceIndex,
                                                                      String choiceText, String nextNodeId, boolean close) {
            lastEdit = "choice.update";
            lastEditDialogueId = dialogueId;
            lastEditNodeId = nodeId;
            lastEditChoiceIndex = choiceIndex;
            lastEditNext = nextNodeId;
            lastEditClose = close;
            return mutation("choice.update " + nodeId + "#" + choiceIndex);
        }

        @Override
        public CompletableFuture<MutationResult> dialogueChoiceDelete(String dialogueId, String nodeId, int choiceIndex) {
            lastEdit = "choice.delete";
            lastEditDialogueId = dialogueId;
            lastEditNodeId = nodeId;
            lastEditChoiceIndex = choiceIndex;
            return mutation("choice.delete " + nodeId + "#" + choiceIndex);
        }

        @Override
        public CompletableFuture<ResetPreview> resetPreview(UUID playerId) {
            return CompletableFuture.completedFuture(new ResetPreview(true,
                    List.of(new ResetPreviewLine("Quêtes", 2, "2 quête(s) avec progression"))));
        }

        @Override
        public CompletableFuture<MutationResult> questStart(UUID playerId, String questId, boolean force) {
            lastQuestStartForce = force;
            return mutation("start " + questId);
        }

        @Override
        public CompletableFuture<MutationResult> questComplete(UUID playerId, String questId) {
            return mutation("complete " + questId);
        }

        @Override
        public CompletableFuture<MutationResult> questReset(UUID playerId, String questId) {
            return mutation("reset " + questId);
        }

        @Override
        public CompletableFuture<MutationResult> storyAdvance(UUID playerId, String storyId) {
            lastStoryId = storyId;
            return mutation("advance " + storyId);
        }

        @Override
        public CompletableFuture<MutationResult> storyComplete(UUID playerId, String storyId) {
            lastStoryId = storyId;
            return mutation("complete " + storyId);
        }

        @Override
        public CompletableFuture<MutationResult> giveItem(UUID playerId, String itemId, int amount) {
            lastGiveItemId = itemId;
            lastGiveAmount = amount;
            return mutation(amount + "x " + itemId);
        }

        @Override
        public CompletableFuture<MutationResult> resetConfirm(UUID playerId, String playerName) {
            resetConfirmCalled = true;
            return mutation("reset " + playerName);
        }

        @Override
        public CompletableFuture<MutationResult> variableSet(UUID playerId, String key, String value) {
            lastVariableValue = value;
            return mutation(key + "=" + value);
        }

        @Override
        public CompletableFuture<List<PlayerCatalogEntry>> playerCatalog(int limit) {
            lastCatalogLimit = limit;
            return CompletableFuture.completedFuture(List.of(
                    new PlayerCatalogEntry(RONDOUDOU.toString(), "Rondoudou9000", true, true,
                            1_600_000_000_000L, null, false, null, "world_hub", 1, 64, 2),
                    new PlayerCatalogEntry("11111111-1111-1111-1111-111111111111", "Steve", false, true,
                            1_500_000_000_000L, 1_599_000_000_000L, true, "spam", null, null, null, null)));
        }

        @Override
        public CompletableFuture<MutationResult> banPlayer(UUID playerId, String playerName, String reason) {
            lastBanReason = reason;
            lastBanUuid = playerId;
            return mutation("ban " + playerName + " : " + reason);
        }

        @Override
        public CompletableFuture<MutationResult> unbanPlayer(UUID playerId, String playerName) {
            unbanCalled = true;
            return mutation("unban " + playerName);
        }
    }
}
