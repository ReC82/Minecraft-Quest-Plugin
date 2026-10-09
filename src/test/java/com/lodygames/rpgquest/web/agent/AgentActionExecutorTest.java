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
    void contentDeleteRejectsAnythingButQuestsAndStories() {
        for (String kind : new String[] {"dialogues", "npcs", "..", ""}) {
            AgentActionOutcome outcome = run(new AgentAction("cd1", "content.definition.delete",
                    Map.of("kind", kind, "id", "test_cible")));
            assertEquals(AgentActionOutcome.REJECTED, outcome.status(), "type accepté à tort : " + kind);
        }
    }

    @Test
    void contentDeleteRequiresAnIdentifier() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("cd2", "content.definition.delete",
                        Map.of("kind", "quests"))).status());
    }

    @Test
    void contentDeletePassesTheValidatedParametersThrough() {
        AgentActionOutcome outcome = run(new AgentAction("cd3", "content.definition.delete",
                Map.of("kind", "stories", "id", "rpgquest:test_saga")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("stories", actions.lastDeleteKind);
        assertEquals("rpgquest:test_saga", actions.lastDeleteId);
    }

    @Test
    void itemCatalogsReportsTheRealMaterialsAndWhatCannotBeAnItem() {
        AgentActionOutcome outcome = run(new AgentAction("ic1", "item.catalogs", Map.of()));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("4", outcome.value(), "la valeur porte le nombre d'objets utilisables");

        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) outcome.details().get("items");
        assertTrue(items.contains("WOODEN_SWORD"), items.toString());
        assertTrue(items.contains("NETHERITE_SWORD"), items.toString());

        @SuppressWarnings("unchecked")
        List<String> blocks = (List<String>) outcome.details().get("blocksWithoutItem");
        assertTrue(blocks.contains("WATER"), "un bloc sans forme d'objet doit être rapporté à part");
        assertFalse(items.contains("WATER"), "il ne doit jamais être proposé comme objet");

        // La version réelle est citée : c'est ce qui permet de vérifier d'où vient la liste.
        assertEquals("1.21.11", outcome.details().get("minecraftVersion"));
        assertTrue(outcome.message().contains("1.21.11"), outcome.message());
        assertTrue(outcome.message().contains("formes historiques écartées"), outcome.message());
    }

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
    void travelCatalogReturnsWaypointsBeaconsAndUnpairedCount() {
        AgentActionOutcome outcome = run(new AgentAction("tc0", "travel.catalog", Map.of()));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(2, outcome.details().get("waypointCount"));
        assertEquals(2, outcome.details().get("beaconCount"));
        assertEquals(1, outcome.details().get("autoGeneratedBeaconCount"));
        // Issue #156 : chaque instance Hub sans borne porte sa cause, pas seulement son identifiant.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unpaired = (List<Map<String, Object>>) outcome.details().get("unpairedHubInstances");
        assertEquals(1, unpaired.size());
        assertEquals("wp_hub_forest_1_0", unpaired.get(0).get("waypointId"));
        assertEquals(0, unpaired.get(0).get("attempts"));
        assertEquals(false, unpaired.get(0).get("inProgress"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> waypoints = (List<Map<String, Object>>) outcome.details().get("waypoints");
        assertEquals(2, waypoints.size());
        assertEquals("beacon_auto_world_hub_plains_0_0", waypoints.get(0).get("pairedBeaconId"));
        assertNull(waypoints.get(1).get("pairedBeaconId"));
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
    void mobDefinitionToggleAndTestSpawnAcceptTheFullNamespacedId() {
        // mob.list renvoie toujours l'id namespacé complet (NamespacedKey#asString(), ex.
        // "rpgquest:creeper_pig") et les formulaires d'édition/bascule/spawn de test le
        // réinjectent tel quel -- doit être accepté, pas seulement la forme courte sans ":".
        AgentActionOutcome toggle = run(new AgentAction("mt0", "mob.definition.toggle",
                Map.of("mob_id", "rpgquest:creeper_pig", "enabled", "false")));
        assertEquals(AgentActionOutcome.SUCCESS, toggle.status(), toggle.message());

        AgentActionOutcome spawn = run(new AgentAction("mt1", "mob.test.spawn",
                Map.of("mob_id", "rpgquest:creeper_pig", "player", "Steve")));
        assertEquals(AgentActionOutcome.SUCCESS, spawn.status(), spawn.message());

        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("mt2", "mob.definition.toggle",
                Map.of("mob_id", "bad id!!", "enabled", "false"))).status(), "id invalide toujours rejeté");
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

    // ---- Issue #213 : emplacements de construction ---------------------------------------------

    /** Le payload que le Control Panel lira : tous les champs, dans leur type. */
    @Test
    void buildingSiteListCarriesEveryFieldThePanelNeeds() {
        AgentActionOutcome outcome = run(new AgentAction("bs0", "building.site.list", Map.of()));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        String json = com.lodygames.rpgquest.web.Json.write(outcome.details());
        assertTrue(json.contains("\"id\":\"buildsite_0001\""), json);
        assertTrue(json.contains("\"name\":\"Taverne du village\""), json);
        assertTrue(json.contains("\"world\":\"world_hub\""), json);
        assertTrue(json.contains("\"x\":712") && json.contains("\"y\":67")
                && json.contains("\"z\":-702"), json);
        assertTrue(json.contains("\"facing\":\"WEST\""), json);
        assertTrue(json.contains("\"status\":\"EMPTY\""), json);
        assertTrue(json.contains("\"createdBy\":\"Lody\""), json);
        assertTrue(json.contains("\"worldLoaded\":true"), json);
        assertTrue(json.contains("\"worlds\":[\"world_hub\"]"), json);
        assertTrue(json.contains("\"total\":1"), json);
    }

    /** L'identifiant doit avoir exactement la forme que le serveur attribue. */
    @Test
    void buildingSiteActionsRejectAnythingButARealSiteId() {
        for (String type : new String[] {"building.site.rename", "building.site.describe",
                "building.site.facing", "building.site.delete"}) {
            assertEquals(AgentActionOutcome.REJECTED,
                    run(new AgentAction("x", type, Map.of("name", "n", "facing", "NORTH",
                            "description", "d"))).status(), type + " sans id");
            assertEquals(AgentActionOutcome.REJECTED,
                    run(new AgentAction("x", type, Map.of("id", "../etc/passwd", "name", "n",
                            "facing", "NORTH", "description", "d"))).status(), type + " id forgé");
            assertEquals(AgentActionOutcome.REJECTED,
                    run(new AgentAction("x", type, Map.of("id", "village_tavern_01", "name", "n",
                            "facing", "NORTH", "description", "d"))).status(),
                    type + " id d'une autre forme");
        }
    }

    @Test
    void buildingSiteRenameValidatesAndDelegates() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("r0", "building.site.rename",
                Map.of("id", "buildsite_0001"))).status(), "name obligatoire");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("r1", "building.site.rename",
                Map.of("id", "buildsite_0001", "name", "n".repeat(65)))).status(),
                "nom trop long");

        AgentActionOutcome ok = run(new AgentAction("r2", "building.site.rename",
                Map.of("id", "buildsite_0001", "name", "Test hutte")));

        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("buildsite_0001", actions.lastBuildSiteId);
        assertEquals("Test hutte", actions.lastBuildSiteName);
    }

    /** Une description VIDE est une valeur valide : c'est le geste « effacer la note ». */
    @Test
    void buildingSiteDescribeAcceptsAnEmptyDescription() {
        AgentActionOutcome ok = run(new AgentAction("d0", "building.site.describe",
                Map.of("id", "buildsite_0001", "description", "")));

        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("", actions.lastBuildSiteDescription);

        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("d1", "building.site.describe",
                Map.of("id", "buildsite_0001", "description", "d".repeat(501)))).status(),
                "description trop longue");
    }

    @Test
    void buildingSiteFacingRequiresACardinalValue() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("f0", "building.site.facing",
                Map.of("id", "buildsite_0001"))).status(), "facing obligatoire");

        AgentActionOutcome ok = run(new AgentAction("f1", "building.site.facing",
                Map.of("id", "buildsite_0001", "facing", "EAST")));

        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("EAST", actions.lastBuildSiteFacing);
    }

    @Test
    void buildingSiteDeleteDelegatesWithTheExactId() {
        AgentActionOutcome ok = run(new AgentAction("del0", "building.site.delete",
                Map.of("id", "BUILDSITE_0001")));

        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("buildsite_0001", actions.lastBuildSiteDeletedId,
                "l'identifiant est normalisé avant d'atteindre le service");
    }

    /** Il n'existe AUCUNE action de création : un emplacement naît d'un clic en jeu, pas d'un écran. */
    @Test
    void thereIsNoBuildingSiteCreateAction() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("c0", "building.site.create",
                        Map.of("world", "world_hub", "x", "0", "y", "64", "z", "0"))).status());
    }

    // ---- Issue #226 : les trois suppressions, et leurs garde-fous -----------------------------

    /**
     * La définition seule. {@code expect_dialogue} est transmis tel quel : c'est le serveur qui le
     * confronte à la réalité, et l'exécuteur n'a pas à en juger.
     */
    @Test
    void npcDefinitionDeleteValidatesAndDelegates() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("dd0", "npc.definition.delete", Map.of())).status(),
                "npc_id obligatoire");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dd1", "npc.definition.delete",
                Map.of("npc_id", "Bad Id"))).status());

        AgentActionOutcome ok = run(new AgentAction("dd2", "npc.definition.delete",
                Map.of("npc_id", "mira_cartographer", "expect_dialogue", "rpgquest:mira_first_map")));

        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("mira_cartographer", actions.lastNpcDeleteId);
        assertEquals("rpgquest:mira_first_map", actions.lastNpcDeleteExpectedDialogue);
    }

    /**
     * Délier exige l'identifiant Citizens attendu. Sans lui, une page affichée il y a dix minutes
     * pourrait retirer une liaison créée depuis — c'est-à-dire la mauvaise.
     */
    @Test
    void npcCitizensUnlinkRequiresTheExpectedCitizensId() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("ul0", "npc.citizens.unlink",
                Map.of("npc_id", "mira_cartographer"))).status(), "citizens_id obligatoire");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("ul1", "npc.citizens.unlink",
                Map.of("npc_id", "mira_cartographer", "citizens_id", "0"))).status(),
                "entier positif requis");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("ul2", "npc.citizens.unlink",
                Map.of("npc_id", "Bad Id", "citizens_id", "9"))).status());

        AgentActionOutcome ok = run(new AgentAction("ul3", "npc.citizens.unlink",
                Map.of("npc_id", "mira_cartographer", "citizens_id", "9")));

        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("mira_cartographer", actions.lastUnlinkNpcId);
        assertEquals(9, actions.lastUnlinkCitizensId);
    }

    /** Détruire un PNJ Citizens sans cible numérique explicite est refusé. */
    @Test
    void npcCitizensDeleteRefusesWithoutAnExplicitTarget() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("cd0", "npc.citizens.delete",
                Map.of("npc_id", "mira_cartographer"))).status(), "citizens_id obligatoire");
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("cd1", "npc.citizens.delete",
                Map.of("npc_id", "mira_cartographer", "citizens_id", "-3"))).status());

        AgentActionOutcome ok = run(new AgentAction("cd2", "npc.citizens.delete",
                Map.of("npc_id", "mira_cartographer", "citizens_id", "9")));

        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("mira_cartographer", actions.lastCitizensDeleteNpcId);
        assertEquals(9, actions.lastCitizensDeleteCitizensId);
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
    void dialogueChoiceUpdateCarriesTheStructuredQuestEdits() {
        AgentActionOutcome ok = run(new AgentAction("dcs0", "dialogue.choice.update",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "greeting", "choice_index", "0",
                        "choice_text", "Je vais m'en charger", "next_node_id", "accepted",
                        "quest_action", "start_quest", "quest_id", "cleanup",
                        "quest_condition", "not_started", "condition_quest_id", "rpgquest:cleanup",
                        "condition_negate", "true")));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals(com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor.EditMode.SET,
                actions.lastQuestAction.mode());
        assertEquals("rpgquest:cleanup", actions.lastQuestAction.questId(), "clé simple préfixée");
        assertEquals(com.lodygames.rpgquest.dialogue.model.ActionType.START_QUEST, actions.lastQuestAction.type());
        assertEquals(com.lodygames.rpgquest.quest.model.QuestState.NOT_STARTED, actions.lastQuestCondition.state());
        assertTrue(actions.lastQuestCondition.negate());
    }

    @Test
    void dialogueChoiceUpdateRejectsAStructuredEditWithoutQuest() {
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dcs1", "dialogue.choice.update",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "greeting", "choice_index", "0",
                        "choice_text", "X", "close", "true", "quest_action", "start_quest"))).status());
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dcs2", "dialogue.choice.update",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "greeting", "choice_index", "0",
                        "choice_text", "X", "close", "true", "quest_action", "give_item",
                        "quest_id", "rpgquest:x"))).status());
        assertEquals(AgentActionOutcome.REJECTED, run(new AgentAction("dcs3", "dialogue.choice.update",
                Map.of("dialogue_id", "rpgquest:guard", "node_id", "greeting", "choice_index", "0",
                        "choice_text", "X", "close", "true", "quest_condition", "nowhere",
                        "condition_quest_id", "rpgquest:x"))).status());
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
        // Sans paramètre structuré, l'action et la condition de quête du choix ne sont pas touchées.
        assertEquals(com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor.EditMode.KEEP,
                actions.lastQuestAction.mode());
        assertEquals(com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor.EditMode.KEEP,
                actions.lastQuestCondition.mode());

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

    // ---- content.export (issue #108) --------------------------------------------------

    @Test
    void contentExportDefaultsToAllAndReturnsThePackInDetails() {
        AgentActionOutcome outcome = run(new AgentAction("ce1", "content.export", Map.of()));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("all", actions.lastExportFamily);
        assertEquals("lodyquests-content-pack", outcome.details().get("format"));
        assertEquals(1, outcome.details().get("schemaVersion"));
        assertEquals("all", outcome.details().get("family"));
        assertTrue(String.valueOf(outcome.details().get("pack")).startsWith("format: lodyquests-content-pack"));
        assertTrue(((Number) outcome.details().get("bytes")).intValue() > 0);
    }

    @Test
    void contentExportForwardsFamilyAndIdSelection() {
        AgentActionOutcome outcome = run(new AgentAction("ce2", "content.export",
                Map.of("family", "quests", "ids", "rpgquest:first_steps, rpgquest:crystal_hunt")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("quests", actions.lastExportFamily);
        assertEquals(List.of("rpgquest:first_steps", "rpgquest:crystal_hunt"), actions.lastExportIds);
    }

    @Test
    void contentExportRejectsUnknownFamily() {
        AgentActionOutcome outcome = run(new AgentAction("ce3", "content.export", Map.of("family", "bogus")));
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
        assertTrue(outcome.message().contains("family"));
    }

    @Test
    void contentExportRejectsIdsWithFamilyAll() {
        AgentActionOutcome outcome = run(new AgentAction("ce4", "content.export",
                Map.of("family", "all", "ids", "rpgquest:first_steps")));
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
    }

    @Test
    void contentExportRejectsMalformedId() {
        AgentActionOutcome outcome = run(new AgentAction("ce5", "content.export",
                Map.of("family", "quests", "ids", "not a valid id!!")));
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
    }

    @Test
    void contentExportFailsCleanlyWhenPackExceedsTransportLimit() {
        actions.exportOversize = true;
        AgentActionOutcome outcome = run(new AgentAction("ce6", "content.export", Map.of()));
        assertEquals(AgentActionOutcome.FAILED, outcome.status());
        assertTrue(outcome.message().contains("volumineux"));
        actions.exportOversize = false;
    }

    // ---- server.announce / server.logs.tail (issue #95) ------------------------------

    @Test
    void announceIsForwardedWithItsChannelAndReportsRealRecipients() {
        AgentActionOutcome outcome = run(new AgentAction("an1", "server.announce",
                Map.of("message", "Redémarrage dans 5 minutes.", "channel", "title")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("Redémarrage dans 5 minutes.", actions.lastAnnounceMessage);
        assertEquals("title", actions.lastAnnounceChannel);
        assertEquals("3", outcome.value(), "la valeur est le nombre réel de destinataires");
        assertEquals(3, outcome.details().get("recipients"));
        assertEquals(3, outcome.details().get("online"));
    }

    @Test
    void announceDefaultsToChatWhenNoChannelIsGiven() {
        run(new AgentAction("an2", "server.announce", Map.of("message", "Bonjour")));

        assertEquals("chat", actions.lastAnnounceChannel);
    }

    @Test
    void announceRejectsACommandInsteadOfBroadcastingItLiterally() {
        AgentActionOutcome outcome = run(new AgentAction("an3", "server.announce",
                Map.of("message", "/say coucou")));

        // Diffuser littéralement « /say coucou » serait pire que refuser : l'administrateur
        // croirait avoir lancé une commande.
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
        assertTrue(outcome.message().contains("commande"), outcome.message());
        assertNull(actions.lastAnnounceMessage, "rien ne doit atteindre le serveur");
    }

    @Test
    void announceRejectsEmptyTooLongAndMultilineMessages() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("an4", "server.announce", Map.of("message", "  "))).status());
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("an5", "server.announce", Map.of("message", "x".repeat(201)))).status());
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("an6", "server.announce", Map.of("message", "a\nb"))).status());
        assertNull(actions.lastAnnounceMessage);
    }

    @Test
    void announceRejectsAnUnknownChannel() {
        AgentActionOutcome outcome = run(new AgentAction("an7", "server.announce",
                Map.of("message", "test", "channel", "bossbar")));

        // « bossbar » existe dans Minecraft mais n'est pas implémenté ici : proposer un canal non
        // supporté serait un bouton qui ne fait rien.
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
        assertTrue(outcome.message().contains("bossbar"), outcome.message());
    }

    @Test
    void anAnnounceWithNobodyOnlineSucceedsButSaysSo() {
        actions.announceOnline = 0;
        actions.announceRecipients = 0;

        AgentActionOutcome outcome = run(new AgentAction("an8", "server.announce",
                Map.of("message", "personne n'écoute")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(0, outcome.details().get("online"));
        assertEquals("NO_PLAYERS", outcome.details().get("code"));
    }

    @Test
    void aFailedAnnounceIsAFailureNotASuccess() {
        actions.announceOk = false;

        assertEquals(AgentActionOutcome.FAILED,
                run(new AgentAction("an9", "server.announce", Map.of("message", "test"))).status());
    }

    @Test
    void logsTailForwardsTheCursorAndLimitAndReportsTheGap() {
        AgentActionOutcome outcome = run(new AgentAction("lg1", "server.logs.tail",
                Map.of("after", "5", "limit", "50")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(5L, actions.lastLogsAfter);
        assertEquals(50, actions.lastLogsLimit);
        assertEquals("7", outcome.value(), "la valeur est le curseur à renvoyer ensuite");
        assertEquals(Boolean.TRUE, outcome.details().get("gap"));
        assertEquals(2L, outcome.details().get("dropped"));
        assertEquals(1, ((java.util.List<?>) outcome.details().get("lines")).size());
    }

    @Test
    void logsTailDefaultsToTheStartOfTheBufferAndASaneLimit() {
        run(new AgentAction("lg2", "server.logs.tail", Map.of()));

        assertEquals(0L, actions.lastLogsAfter);
        assertEquals(200, actions.lastLogsLimit);
    }

    @Test
    void logsTailRejectsANonNumericOrOutOfBoundsRequest() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("lg3", "server.logs.tail", Map.of("after", "abc"))).status());
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("lg4", "server.logs.tail", Map.of("limit", "0"))).status());
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("lg5", "server.logs.tail", Map.of("limit", "501"))).status());
    }

    @Test
    void logsTailSurfacesTheCaptureLimitationInsteadOfPretendingTheConsoleIsEmpty() {
        actions.logsLimitation = "Capture de la console indisponible sur ce serveur.";

        AgentActionOutcome outcome = run(new AgentAction("lg6", "server.logs.tail", Map.of()));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(actions.logsLimitation, outcome.details().get("limitation"));
        assertTrue(outcome.message().contains("indisponible"), outcome.message());
    }

    // ---- content.reload / content.reload.preview (issue #131) -------------------------

    @Test
    void aReloadForwardsTheRequestedFamiliesAndReportsTheRuntimeHash() {
        AgentActionOutcome outcome = run(new AgentAction("cr1", "content.reload",
                Map.of("families", "quests,dialogues", "confirm", "true")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(List.of("quests", "dialogues"), actions.lastReloadFamilies);
        assertTrue(actions.lastReloadApply, "content.reload applique");
        assertEquals("abc123def456", outcome.value(), "la valeur est l'empreinte du runtime");
        assertEquals(Boolean.TRUE, outcome.details().get("applied"));
        assertEquals(42L, outcome.details().get("durationMillis"));
    }

    @Test
    void aPreviewNeverApplies() {
        run(new AgentAction("cr2", "content.reload.preview", Map.of("families", "quests")));

        assertFalse(actions.lastReloadApply, "l'aperçu ne doit jamais appliquer");
    }

    @Test
    void theFamilyIdsAreCarriedSoThePanelCanTellPublishedFromLoaded() {
        AgentActionOutcome outcome = run(new AgentAction("cr3", "content.reload.preview",
                Map.of("families", "quests")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> families = (List<Map<String, Object>>) outcome.details().get("families");
        assertEquals(1, families.size());
        assertEquals(List.of("rpgquest:alpha", "rpgquest:beta", "rpgquest:gamma"), families.get(0).get("ids"));
    }

    @Test
    void aReloadWithoutFamiliesIsRejected() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("cr4", "content.reload", Map.of("confirm", "true"))).status());
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("cr5", "content.reload", Map.of("families", "  ,  "))).status());
        assertNull(actions.lastReloadFamilies, "rien ne doit atteindre le serveur");
    }

    @Test
    void aRefusedReloadIsAFailureNotASuccess() {
        actions.reloadApplied = false;
        actions.reloadCode = "INVALID_CONTENT";

        AgentActionOutcome outcome = run(new AgentAction("cr6", "content.reload",
                Map.of("families", "quests", "confirm", "true")));

        // Le panel doit pouvoir distinguer « rien appliqué parce qu'invalide » de « appliqué » :
        // un SUCCESS ici ferait croire que le contenu est chargé.
        assertEquals(AgentActionOutcome.FAILED, outcome.status());
        assertTrue(outcome.message().contains("rien n'a été rechargé"), outcome.message());
    }

    @Test
    void anAlreadyRunningReloadIsReportedAsAFailure() {
        actions.reloadApplied = false;
        actions.reloadCode = "BUSY";

        assertEquals(AgentActionOutcome.FAILED,
                run(new AgentAction("cr7", "content.reload",
                        Map.of("families", "quests", "confirm", "true"))).status());
    }

    @Test
    void tooManyFamiliesAreRejected() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("cr8", "content.reload",
                        Map.of("families", "a,b,c,d,e,f,g,h,i,j,k,l,m", "confirm", "true"))).status());
    }

    // ---- Monnaie (issue #140) ---------------------------------------------------------

    @Test
    void readingABalanceCarriesTheLedgerWithSignedAmounts() {
        AgentActionOutcome outcome = run(new AgentAction("ec1", "economy.balance",
                Map.of("player", "Rondoudou9000", "history", "20")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(20, actions.lastEcoHistoryLimit);
        assertEquals("250", outcome.value());
        assertEquals(250L, outcome.details().get("balance"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) outcome.details().get("history");
        assertEquals(2, history.size());
        // Le montant reste SIGNÉ : un débit est négatif, et le panel n'a pas à le deviner.
        assertEquals(-30L, history.get(1).get("amount"));
    }

    @Test
    void theLedgerLimitDefaultsAndIsBounded() {
        run(new AgentAction("ec2", "economy.balance", Map.of("player", "Rondoudou9000")));
        assertEquals(20, actions.lastEcoHistoryLimit, "défaut raisonnable");

        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("ec3", "economy.balance",
                        Map.of("player", "Rondoudou9000", "history", "0"))).status());
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("ec4", "economy.balance",
                        Map.of("player", "Rondoudou9000", "history", "101"))).status());
    }

    @Test
    void aCreditForwardsTheAmountAndTheReason() {
        AgentActionOutcome outcome = run(new AgentAction("ec5", "economy.credit",
                Map.of("player", "Rondoudou9000", "amount", "250", "reason", "compensation bug",
                        "confirm", "true")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(250L, actions.lastEcoAmount);
        assertEquals(Boolean.TRUE, actions.lastEcoCredit);
        assertEquals("compensation bug", actions.lastEcoReason);
        assertEquals(100L, outcome.details().get("balanceBefore"));
        assertEquals(350L, outcome.details().get("balanceAfter"));
    }

    @Test
    void aDebitIsDistinguishedFromACredit() {
        run(new AgentAction("ec6", "economy.debit",
                Map.of("player", "Rondoudou9000", "amount", "10", "reason", "correction", "confirm", "true")));

        assertEquals(Boolean.FALSE, actions.lastEcoCredit);
    }

    @Test
    void aReasonIsAlwaysRequiredBecauseItLandsInTheLedger() {
        AgentActionOutcome outcome = run(new AgentAction("ec7", "economy.credit",
                Map.of("player", "Rondoudou9000", "amount", "10", "confirm", "true")));

        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
        assertTrue(outcome.message().contains("Raison"), outcome.message());
        assertEquals(-1, actions.lastEcoAmount, "rien ne doit atteindre le serveur");
    }

    @Test
    void anInvalidOrExcessiveAmountIsRejectedBeforeReachingTheServer() {
        for (String amount : new String[] {"0", "-5", "abc", "1000001"}) {
            AgentActionOutcome outcome = run(new AgentAction("ec8", "economy.credit",
                    Map.of("player", "Rondoudou9000", "amount", amount, "reason", "test", "confirm", "true")));
            assertEquals(AgentActionOutcome.REJECTED, outcome.status(), "montant " + amount);
        }
        assertEquals(-1, actions.lastEcoAmount);
    }

    @Test
    void insufficientFundsIsAReadableFailureWithTheRealBalance() {
        actions.ecoOk = false;
        actions.ecoCode = "INSUFFICIENT_FUNDS";

        AgentActionOutcome outcome = run(new AgentAction("ec9", "economy.debit",
                Map.of("player", "Rondoudou9000", "amount", "999", "reason", "test", "confirm", "true")));

        // Un refus métier n'est pas un succès : sinon l'administrateur croirait avoir débité.
        assertEquals(AgentActionOutcome.FAILED, outcome.status());
        assertTrue(outcome.message().contains("insuffisants"), outcome.message());
    }

    @Test
    void aMultilineReasonIsRejected() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("ec10", "economy.credit",
                        Map.of("player", "Rondoudou9000", "amount", "10", "reason", "a\nb",
                                "confirm", "true"))).status());
    }

    // ---- Récompenses monétaires restées dues (issue #16, second lot) ---------------------------

    @Test
    void thePendingRewardSurveyExposesEverythingNeededToDecide() {
        actions.debts.add(new AgentActions.QuestRewardDebtView("token-a#0", "rpgquest:q1", "Ma quête",
                2, 0, 70L, 3, "SQLException: disk I/O error", null));

        AgentActionOutcome outcome = run(new AgentAction("d1", "economy.debts",
                Map.of("player", "Rondoudou9000")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals(70L, outcome.details().get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) outcome.details().get("debts");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        // L'état RÉEL, pas un « en attente » qui cacherait trois échecs.
        assertEquals("token-a#0", row.get("grantId"));
        assertEquals("rpgquest:q1", row.get("questId"));
        assertEquals("Ma quête", row.get("questTitle"));
        assertEquals(2, row.get("occurrence"));
        assertEquals(70L, row.get("amount"));
        assertEquals(3, row.get("attempts"));
        assertTrue(String.valueOf(row.get("lastError")).contains("disk I/O"));
    }

    @Test
    void thePendingRewardSurveyIsBounded() {
        for (String limit : new String[] {"0", "101", "-1"}) {
            assertEquals(AgentActionOutcome.REJECTED,
                    run(new AgentAction("d2", "economy.debts",
                            Map.of("player", "Rondoudou9000", "limit", limit))).status(), limit);
        }
    }

    @Test
    void aRetryForwardsOnlyTheIdentityAndNeverAnAmount() {
        AgentActionOutcome outcome = run(new AgentAction("d3", "economy.debt.retry",
                Map.of("player", "Rondoudou9000", "grant", "token-a#0", "confirm", "true",
                        // Un montant glissé dans le formulaire ne doit avoir AUCUN effet : une
                        // reprise paie ce qui a été enregistré, pas ce qu'on lui demande.
                        "amount", "999999")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("token-a#0", actions.lastRetryGrant);
        assertEquals("PAID", outcome.details().get("code"));
        assertEquals(70L, outcome.details().get("amount"));
    }

    @Test
    void aRetryThatPaysNothingIsAFailureAndNotASuccess() {
        actions.retryCode = "ALREADY_PAID";

        AgentActionOutcome outcome = run(new AgentAction("d4", "economy.debt.retry",
                Map.of("player", "Rondoudou9000", "grant", "token-a#0", "confirm", "true")));

        // Si c'était un succès, l'audit laisserait croire que le joueur a été crédité.
        assertEquals(AgentActionOutcome.FAILED, outcome.status());
        assertTrue(outcome.message().contains("Déjà réglée"), outcome.message());
    }

    @Test
    void aRetryWithoutAnIdentityIsRejectedBeforeReachingTheServer() {
        actions.lastRetryGrant = null;

        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("d5", "economy.debt.retry",
                        Map.of("player", "Rondoudou9000", "confirm", "true"))).status());
        assertNull(actions.lastRetryGrant, "rien ne doit atteindre le serveur");
    }

    @Test
    void settlingManuallyRequiresAReasonAndKeepsIt() {
        assertEquals(AgentActionOutcome.REJECTED,
                run(new AgentAction("d6", "economy.debt.settle",
                        Map.of("player", "Rondoudou9000", "grant", "token-a#0", "confirm", "true"))).status());
        assertNull(actions.lastSettleGrant, "rien ne doit atteindre le serveur sans raison");

        AgentActionOutcome outcome = run(new AgentAction("d7", "economy.debt.settle",
                Map.of("player", "Rondoudou9000", "grant", "token-a#0",
                        "reason", "compensé à la main", "confirm", "true")));

        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("token-a#0", actions.lastSettleGrant);
        assertEquals("compensé à la main", actions.lastSettleReason);
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

        String lastExportFamily;
        List<String> lastExportIds;
        boolean exportOversize;

        @Override
        public ContentExportResult exportContent(String family, List<String> ids) {
            lastExportFamily = family;
            lastExportIds = ids;
            String pack = "format: lodyquests-content-pack\nschemaVersion: 1\ncontent:\n"
                    + "  quests: []\n  stories: []\n  dialogues: []\n  npcs: []\n";
            if (exportOversize) {
                pack = pack + "# " + "x".repeat(60_000) + "\n";
            }
            return new ContentExportResult(true, "OK", "lodyquests-content-pack", 1,
                    family == null ? "all" : family, java.util.Map.of("quests", 0), 0, pack);
        }

        String lastNpcCreateId;
        String lastNpcUpdateId;
        String lastGiverQuestId;
        String lastGiverNpcId;
        String lastNpcDeleteId;
        String lastNpcDeleteExpectedDialogue;
        String lastUnlinkNpcId;
        int lastUnlinkCitizensId;
        String lastCitizensDeleteNpcId;
        int lastCitizensDeleteCitizensId;

        @Override
        public CompletableFuture<NpcCatalogView> npcDefinitions() {
            NpcSummary guard = new NpcSummary("guard", "Garde", true, true, 7, 1, true,
                    "Garde du village", "quest_giver", "rpgquest:guard", true, "rpgquest:guard", 6, 9,
                    List.of("rpgquest:first_steps"), List.of("rpgquest:crystal_hunt"),
                    List.of("rpgquest:crystal_hunt"), List.of(),
                    List.of("DEFINITION", "BINDING", "DIALOGUE", "QUEST_GIVER", "QUEST_TALK"), "LINKED", List.of());
            NpcSummary woodcutter = new NpcSummary("woodcutter_bob", null, false, false, null, 0, true,
                    null, null, null, false, null, 0, 0,
                    List.of(), List.of(), List.of("rpgquest:woodcutters_request"), List.of(),
                    List.of("QUEST_TALK"), "UNDEFINED_REFERENCE",
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

        String lastBuildSiteId;
        String lastBuildSiteName;
        String lastBuildSiteDescription;
        String lastBuildSiteFacing;
        String lastBuildSiteDeletedId;

        @Override
        public CompletableFuture<BuildingSiteCatalogView> buildingSites() {
            return CompletableFuture.completedFuture(new BuildingSiteCatalogView(
                    List.of(new BuildingSiteSummary("buildsite_0001", "Taverne du village", "",
                            "world_hub", 712, 67, -702, "WEST", "EMPTY", "Lody",
                            "2026-10-08T18:00:00Z", true)),
                    List.of("world_hub"), 1, List.of()));
        }

        String lastPreviewSiteId;
        String lastPreviewBuildingId;
        String lastPlacedSiteId;
        String lastPlacedBuildingId;
        String lastPlacedBy;
        String lastRollbackSiteId;

        String lastPublishKind;
        String lastPublishSlug;
        String lastPublishYaml;
        String lastPublishExpectedSha;
        String lastPublishExpectedId;
        String lastRollbackBackup;
        boolean publishOk = true;

        String lastReadKind;
        String lastReadSlug;

        @Override
        public CompletableFuture<DevContentFileText> contentDevRead(String kind, String slug) {
            lastReadKind = kind;
            lastReadSlug = slug;
            return CompletableFuture.completedFuture(new DevContentFileText(kind, slug, true, false,
                    "abc123", "id: rpgquest:test_publish_quest\ntitle: \"sur DEV\"\n"));
        }

        @Override
        public CompletableFuture<DevContentStateView> contentDevState() {
            return CompletableFuture.completedFuture(new DevContentStateView(
                    List.of(new DevContentFile("quests", "test_publish_quest", "abc123", 42L)),
                    java.util.Map.of("quests", List.of("rpgquest:test_publish_quest")),
                    "runtime-hash"));
        }

        @Override
        public CompletableFuture<ContentPublishResultView> contentPublish(String kind, String slug,
                                                                          String yaml,
                                                                          String expectedDevSha,
                                                                          String expectedId) {
            lastPublishKind = kind;
            lastPublishSlug = slug;
            lastPublishYaml = yaml;
            lastPublishExpectedSha = expectedDevSha;
            lastPublishExpectedId = expectedId;
            return CompletableFuture.completedFuture(new ContentPublishResultView(publishOk,
                    publishOk ? "PUBLISHED" : "CONFLICT",
                    publishOk ? "publié et confirmé" : "le fichier DEV a changé",
                    kind, slug, expectedId, "", "after", "after", true, "", true, "APPLIED",
                    "rechargé", 3, 0, publishOk, "hash", "2026-10-09T00:00:00Z"));
        }

        @Override
        public CompletableFuture<ContentPublishResultView> contentPublishRollback(String kind,
                                                                                  String slug,
                                                                                  String backupPath,
                                                                                  String expectedId) {
            lastRollbackBackup = backupPath;
            return CompletableFuture.completedFuture(new ContentPublishResultView(true,
                    "RESTORED", "restauré", kind, slug, expectedId, "after", "before", "before",
                    false, backupPath == null ? "" : backupPath, true, "APPLIED", "rechargé",
                    3, 0, true, "hash", "2026-10-09T00:00:00Z"));
        }

        @Override
        public CompletableFuture<BuildingLibraryView> buildingLibrary() {
            return CompletableFuture.completedFuture(new BuildingLibraryView(
                    List.of(new BuildingDefinitionSummary("test_hut_01", "Hutte de test", "",
                            7, 6, 5, 3, 1, 0, "NORTH",
                            List.of("cobblestone", "oak_planks"), "test_hut_01.schem", true, 1)),
                    List.of(), true, ""));
        }

        @Override
        public CompletableFuture<BuildingPreviewView> buildingPlacementPreview(String siteId,
                                                                               String buildingId) {
            lastPreviewSiteId = siteId;
            lastPreviewBuildingId = buildingId;
            return CompletableFuture.completedFuture(new BuildingPreviewView(true,
                    siteId, "Taverne du village", "WEST", "world_hub", 712, 67, -702,
                    buildingId, "Hutte de test", 7, 6, 5, "NORTH", 270,
                    708, 66, -705, 712, 71, -699, 210L, 12L, List.of(), List.of()));
        }

        @Override
        public CompletableFuture<MutationResult> buildingPlacementPlace(String siteId,
                                                                        String buildingId,
                                                                        String placedBy) {
            lastPlacedSiteId = siteId;
            lastPlacedBuildingId = buildingId;
            lastPlacedBy = placedBy;
            return CompletableFuture.completedFuture(
                    MutationResult.of(true, "PLACED", "posé"));
        }

        @Override
        public CompletableFuture<MutationResult> buildingPlacementRollback(String siteId) {
            lastRollbackSiteId = siteId;
            return CompletableFuture.completedFuture(
                    MutationResult.of(true, "RESTORED", "restauré"));
        }

        @Override
        public CompletableFuture<MutationResult> buildingSiteRename(String id, String name) {
            lastBuildSiteId = id;
            lastBuildSiteName = name;
            return mutation("rename " + id);
        }

        @Override
        public CompletableFuture<MutationResult> buildingSiteDescribe(String id, String description) {
            lastBuildSiteId = id;
            lastBuildSiteDescription = description;
            return mutation("describe " + id);
        }

        @Override
        public CompletableFuture<MutationResult> buildingSiteFacing(String id, String facing) {
            lastBuildSiteId = id;
            lastBuildSiteFacing = facing;
            return mutation("facing " + id);
        }

        @Override
        public CompletableFuture<MutationResult> buildingSiteDelete(String id) {
            lastBuildSiteDeletedId = id;
            return mutation("delete " + id);
        }

        @Override
        public CompletableFuture<MutationResult> npcDefinitionDelete(String id, String expectDialogueId) {
            lastNpcDeleteId = id;
            lastNpcDeleteExpectedDialogue = expectDialogueId;
            return mutation("delete " + id);
        }

        @Override
        public CompletableFuture<MutationResult> npcCitizensUnlink(String npcId, int expectedCitizensId) {
            lastUnlinkNpcId = npcId;
            lastUnlinkCitizensId = expectedCitizensId;
            return mutation("unlink " + npcId + " #" + expectedCitizensId);
        }

        @Override
        public CompletableFuture<MutationResult> npcCitizensDelete(String npcId, int expectedCitizensId) {
            lastCitizensDeleteNpcId = npcId;
            lastCitizensDeleteCitizensId = expectedCitizensId;
            return mutation("citizens delete " + npcId + " #" + expectedCitizensId);
        }

        String lastRenameNpcId;
        String lastRenameName;
        String lastSkinNpcId;
        String lastSkinUrl;
        boolean lastSkinByPlayerName;
        final List<String> lookCloseCalls = new java.util.ArrayList<>();
        final List<String> wanderCalls = new java.util.ArrayList<>();
        String lastMoveNpcId;
        String lastMoveWorld;
        String lastProvisionName;
        String lastProvisionWorld;
        String lastProvisionSkin;

        @Override
        public CompletableFuture<MutationResult> questGiverSet(String questId, String npcId) {
            lastGiverQuestId = questId;
            lastGiverNpcId = npcId;
            return mutation("giver " + questId + " -> " + npcId);
        }

        String lastLinkNpcId;
        int lastLinkCitizensId;

        @Override
        public CompletableFuture<MobCatalogsView> mobCatalogs() {
            return CompletableFuture.completedFuture(new MobCatalogsView(
                    List.of("ZOMBIE", "CREEPER"), List.of("FLAME", "DUST"),
                    List.of("ENTITY_CREEPER_PRIMED"), List.of("PLAINS", "SWAMP"),
                    List.of("wild"), "wild", List.of("DUST")));
        }

        /** Mémorise l'appel pour vérifier que l'exécuteur transmet bien ce qu'il a validé. */
        String lastDeleteKind;
        String lastDeleteId;

        @Override
        public CompletableFuture<MutationResult> contentDefinitionDelete(String kind, String id) {
            lastDeleteKind = kind;
            lastDeleteId = id;
            return CompletableFuture.completedFuture(
                    MutationResult.of(true, "DELETED", "Fichier supprimé du serveur."));
        }

        @Override
        public CompletableFuture<ItemCatalogsView> itemCatalogs() {
            // Jeu réduit mais représentatif : des épées de matières différentes (le cas du
            // ticket #196), un objet quelconque, et un bloc SANS forme d'objet.
            return CompletableFuture.completedFuture(new ItemCatalogsView(
                    List.of("DIAMOND_SWORD", "NETHERITE_SWORD", "WOODEN_SWORD", "BOOK"),
                    List.of("WATER", "FIRE"), "1.21.11", 500));
        }

        @Override
        public CompletableFuture<MutationResult> citizensRename(String npcId, String newName) {
            lastRenameNpcId = npcId;
            lastRenameName = newName;
            return mutation("rename " + npcId + " -> " + newName);
        }

        @Override
        public CompletableFuture<CitizensProvisionResult> citizensProvision(
                String displayName, String skinValue, boolean skinByPlayerName,
                String world, Double x, Double y, Double z, Float yaw, Float pitch) {
            lastProvisionName = displayName;
            lastProvisionWorld = world;
            lastProvisionSkin = skinValue;
            return CompletableFuture.completedFuture(new CitizensProvisionResult(true, "PROVISIONED",
                    "ok", "bob", 42, displayName, world == null ? "world_hub" : world,
                    x == null ? 0.5 : x, y == null ? 65.0 : y, z == null ? 0.5 : z,
                    yaw == null ? 0f : yaw, pitch == null ? 0f : pitch, "skin note", false,
                    java.util.List.of()));
        }

        @Override
        public CompletableFuture<MutationResult> citizensMove(String npcId, String world,
                                                              double x, double y, double z,
                                                              float yaw, float pitch) {
            lastMoveNpcId = npcId;
            lastMoveWorld = world;
            return mutation("move " + npcId + " -> " + world + " " + x + "/" + y + "/" + z);
        }

        @Override
        public CompletableFuture<MutationResult> citizensSkin(String npcId, String value, boolean byPlayerName) {
            lastSkinNpcId = npcId;
            lastSkinUrl = value;
            lastSkinByPlayerName = byPlayerName;
            return mutation("skin " + npcId + " -> " + (byPlayerName ? "pseudo " : "") + value);
        }

        @Override
        public CompletableFuture<MutationResult> citizensLookClose(String npcId, boolean enabled, Double range) {
            lookCloseCalls.add(npcId + " enabled=" + enabled + " range=" + range);
            return mutation("lookclose " + npcId);
        }

        @Override
        public CompletableFuture<MutationResult> citizensWander(String npcId, boolean enabled,
                                                                String world, Double x, Double y, Double z,
                                                                int xRange, int yRange, boolean confirmReplace) {
            wanderCalls.add(npcId + " enabled=" + enabled + " anchor=" + world + "/" + x + "/" + y + "/" + z
                    + " zone=" + xRange + "x" + yRange + " confirm=" + confirmReplace);
            return mutation("wander " + npcId);
        }

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
        com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor.QuestActionEdit lastQuestAction;
        com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor.QuestConditionEdit lastQuestCondition;

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
        public CompletableFuture<MutationResult> dialogueChoiceUpdate(
                String dialogueId, String nodeId, int choiceIndex, String choiceText, String nextNodeId, boolean close,
                com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor.QuestActionEdit questAction,
                com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor.QuestConditionEdit questCondition) {
            lastEdit = "choice.update";
            lastEditDialogueId = dialogueId;
            lastEditNodeId = nodeId;
            lastEditChoiceIndex = choiceIndex;
            lastEditNext = nextNodeId;
            lastEditClose = close;
            lastQuestAction = questAction;
            lastQuestCondition = questCondition;
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
                            1_600_000_000_000L, null, false, null, "world_hub", 1, 64, 2, true, false),
                    new PlayerCatalogEntry("11111111-1111-1111-1111-111111111111", "Steve", false, true,
                            1_500_000_000_000L, 1_599_000_000_000L, true, "spam", null, null, null, null,
                            false, true)));
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

        @Override
        public CompletableFuture<TravelCatalogView> travelCatalog() {
            WaypointSummary paired = new WaypointSummary("wp_hub_plains_0_0", "Rochebrune", "world_hub",
                    "minecraft:plains", "minecraft:plains@0,0", 10, 65, 10, true, 1, "beacon_auto_world_hub_plains_0_0");
            WaypointSummary unpaired = new WaypointSummary("wp_hub_forest_1_0", "Clairval", "world_hub",
                    "minecraft:forest", "minecraft:forest@1,0", 300, 65, 10, true, 1, null);
            BeaconSummary autoBeacon = new BeaconSummary("beacon_auto_world_hub_plains_0_0", "world_hub",
                    15, 65, 10, true, 1, true, "minecraft:plains@0,0", "wp_hub_plains_0_0");
            BeaconSummary adminBeacon = new BeaconSummary("beacon_wild_1_65_1", "wild", 1, 65, 1, true, 1,
                    false, "", null);
            return CompletableFuture.completedFuture(new TravelCatalogView(
                    List.of(paired, unpaired), List.of(autoBeacon, adminBeacon),
                    2, 2, 1,
                    List.of(new UnpairedHubInstance("wp_hub_forest_1_0", "minecraft:forest@1,0",
                            "minecraft:forest", 300, 10, 0, null, false, 290)),
                    1_700_000_000_000L));
        }

        @Override
        public CompletableFuture<MobCatalogView> mobDefinitions() {
            return CompletableFuture.completedFuture(
                    new MobCatalogView(List.of(), new MobSpawnSettingsView(true, 1.0, null), false, List.of()));
        }

        @Override
        public CompletableFuture<MutationResult> mobDefinitionCreate(String id, String category, boolean enabled,
                String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
                List<String> zones, Double health, Double damage, Double speed, Double armor,
                Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
                Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
                Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
                Integer summonCooldownSeconds, Integer summonMaxAlive) {
            return mutation("mob create " + id);
        }

        @Override
        public CompletableFuture<MutationResult> mobDefinitionUpdate(String id, String category, boolean enabled,
                String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
                List<String> zones, Double health, Double damage, Double speed, Double armor,
                Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
                Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
                Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
                Integer summonCooldownSeconds, Integer summonMaxAlive) {
            return mutation("mob update " + id);
        }

        @Override
        public CompletableFuture<MutationResult> mobDefinitionToggle(String id, boolean enabled) {
            return mutation("mob toggle " + id);
        }

        @Override
        public CompletableFuture<MutationResult> mobSpawnSettingsSet(boolean enabled, double chance, Integer maxSimultaneousSpecial) {
            return mutation("mob spawn-settings");
        }

        @Override
        public CompletableFuture<MutationResult> mobTestSpawn(String definitionId, String playerName) {
            return mutation("mob test spawn " + definitionId);
        }

        @Override
        public CompletableFuture<MutationResult> mobTestClear() {
            return mutation("mob test clear");
        }

        // ---- Exploitation serveur (issue #95) ----------------------------------------

        String lastAnnounceMessage;
        String lastAnnounceChannel;
        int announceRecipients = 3;
        int announceOnline = 3;
        boolean announceOk = true;
        long lastLogsAfter = -1;
        int lastLogsLimit = -1;
        String logsLimitation;

        @Override
        public CompletableFuture<AnnounceResult> announce(String message, String channel) {
            lastAnnounceMessage = message;
            lastAnnounceChannel = channel;
            return CompletableFuture.completedFuture(new AnnounceResult(announceOk,
                    announceOk ? (announceOnline == 0 ? "NO_PLAYERS" : "SENT") : "ERROR",
                    announceOk ? "Annonce envoyée." : "Diffusion impossible.",
                    channel, announceRecipients, announceOnline));
        }

        // ---- Monnaie (issue #140) ----------------------------------------------------

        long lastEcoAmount = -1;
        Boolean lastEcoCredit;
        String lastEcoReason;
        int lastEcoHistoryLimit = -1;
        boolean ecoOk = true;
        String ecoCode = "CREDITED";

        @Override
        public CompletableFuture<EconomyBalanceView> economyBalance(UUID playerId, int historyLimit) {
            lastEcoHistoryLimit = historyLimit;
            return CompletableFuture.completedFuture(new EconomyBalanceView(true, "Solde relu.", 250L,
                    List.of(new EconomyLedgerView("ADMIN_GRANT", 100L, "don", "2026-10-06T00:00:00Z"),
                            new EconomyLedgerView("MERCHANT_BUY", -30L, "pain", "2026-10-06T00:01:00Z"))));
        }

        @Override
        public CompletableFuture<EconomyAdjustView> economyAdjust(UUID playerId, String playerName,
                                                                  long amount, boolean credit, String reason) {
            lastEcoAmount = amount;
            lastEcoCredit = credit;
            lastEcoReason = reason;
            return CompletableFuture.completedFuture(new EconomyAdjustView(ecoOk, ecoCode,
                    ecoOk ? "Solde : 100 → 350." : "Fonds insuffisants : solde inchangé.",
                    100L, ecoOk ? 350L : 100L));
        }

        // ---- Récompenses monétaires dues (issue #16, second lot) ---------------------

        List<QuestRewardDebtView> debts = new java.util.ArrayList<>();
        String lastRetryGrant;
        String lastSettleGrant;
        String lastSettleReason;
        String retryCode = "PAID";

        @Override
        public CompletableFuture<QuestRewardDebtsView> questRewardDebts(UUID playerId, int limit) {
            return CompletableFuture.completedFuture(new QuestRewardDebtsView(true,
                    debts.size() + " récompense(s) monétaire(s) en attente.", List.copyOf(debts)));
        }

        @Override
        public CompletableFuture<QuestRewardRetryView> retryQuestRewardDebt(UUID playerId, String grantId) {
            lastRetryGrant = grantId;
            boolean ok = "PAID".equals(retryCode);
            return CompletableFuture.completedFuture(new QuestRewardRetryView(ok, retryCode,
                    ok ? "Récompense créditée : 70 pièce(s). Nouveau solde : 70."
                            : "Déjà réglée : aucun second crédit.", ok ? 70L : 70L, ok ? 70L : 0L));
        }

        @Override
        public CompletableFuture<MutationResult> settleQuestRewardDebt(UUID playerId, String grantId,
                                                                        String reason) {
            lastSettleGrant = grantId;
            lastSettleReason = reason;
            return CompletableFuture.completedFuture(MutationResult.of(true, "SETTLED",
                    "Récompense marquée réglée à la main."));
        }

        // ---- Pont vers les droits Minecraft (issue #200) -----------------------------

        boolean bridgeAvailable = true;
        String lastSyncGroupId;
        java.util.List<McNodeSpec> lastSyncNodes = java.util.List.of();
        java.util.List<String> lastUserGroupIds = java.util.List.of();
        java.util.List<String> preserved = java.util.List.of();
        boolean syncOk = true;

        @Override
        public CompletableFuture<McRightsView> mcRightsRead(UUID playerId) {
            return CompletableFuture.completedFuture(new McRightsView(true,
                    bridgeAvailable ? "1 droit(s) géré(s) porté(s)." : "Pont indisponible.",
                    bridgeAvailable, bridgeAvailable ? null : "LuckPerms n'est pas installé.",
                    bridgeAvailable
                            ? java.util.List.of("rpgquest.build.hub.world_hub (monde world_hub) ← rpgq-abc")
                            : java.util.List.of()));
        }

        @Override
        public CompletableFuture<McSyncView> mcGroupSync(String groupId, String displayName,
                                                          java.util.List<McNodeSpec> nodes) {
            lastSyncGroupId = groupId;
            lastSyncNodes = java.util.List.copyOf(nodes);
            return CompletableFuture.completedFuture(new McSyncView(syncOk,
                    syncOk ? "1 droit(s) ajouté(s), 0 retiré(s)." : "Pont indisponible. Aucun droit modifié.",
                    syncOk ? java.util.List.of("rpgquest.build.hub.world_hub (monde world_hub)") : java.util.List.of(),
                    java.util.List.of(), java.util.List.of(), preserved));
        }

        @Override
        public CompletableFuture<McSyncView> mcGroupDelete(String groupId) {
            lastSyncGroupId = groupId;
            return CompletableFuture.completedFuture(new McSyncView(syncOk,
                    syncOk ? "Groupe LuckPerms supprimé." : "Pont indisponible. Aucun droit modifié.",
                    java.util.List.of(), java.util.List.of("rpgq-" + groupId),
                    java.util.List.of(), preserved));
        }

        @Override
        public CompletableFuture<McSyncView> mcRightsSync(UUID playerId, java.util.List<String> groupIds) {
            lastUserGroupIds = java.util.List.copyOf(groupIds);
            return CompletableFuture.completedFuture(new McSyncView(syncOk,
                    syncOk ? "1 appartenance(s) ajoutée(s), 0 retirée(s)." : "Pont indisponible.",
                    syncOk ? java.util.List.of("groupe rpgq-abc") : java.util.List.of(),
                    java.util.List.of(), java.util.List.of(), preserved));
        }

        // ---- Administration de joueur (issue #210) -----------------------------------

        Boolean lastOpValue;
        String lastOpPlayer;
        String lastHubPlayer;
        String lastKickReason;
        Boolean lastWhitelistValue;

        @Override
        public CompletableFuture<MutationResult> setOperator(UUID playerId, String playerName, boolean op) {
            lastOpValue = op;
            lastOpPlayer = playerName;
            return mutation(op ? "OP accordé" : "OP retiré");
        }

        @Override
        public CompletableFuture<MutationResult> sendToHub(UUID playerId, String playerName) {
            lastHubPlayer = playerName;
            return mutation("renvoyé au Hub");
        }

        @Override
        public CompletableFuture<MutationResult> kickPlayer(UUID playerId, String playerName, String reason) {
            lastKickReason = reason;
            return mutation("expulsé");
        }

        @Override
        public CompletableFuture<MutationResult> setWhitelisted(UUID playerId, String playerName,
                                                                boolean whitelisted) {
            lastWhitelistValue = whitelisted;
            return mutation("whitelist");
        }

        // ---- Rechargement du contenu (issue #131) ------------------------------------

        List<String> lastReloadFamilies;
        boolean lastReloadApply;
        String reloadCode = "APPLIED";
        boolean reloadApplied = true;

        @Override
        public CompletableFuture<ContentReloadView> contentReload(List<String> families, boolean apply) {
            lastReloadFamilies = List.copyOf(families);
            lastReloadApply = apply;
            return CompletableFuture.completedFuture(new ContentReloadView(reloadApplied, reloadCode,
                    reloadApplied ? "Quêtes rechargées : 3 élément(s) en jeu."
                            : "Contenu invalide : rien n'a été rechargé.",
                    List.of(new ContentReloadFamilyView("quests", "Quêtes", 3, 0, List.of(),
                            List.of("rpgquest:alpha", "rpgquest:beta", "rpgquest:gamma"))),
                    reloadApplied ? List.of() : List.of("Story « s » : quête inconnue « x »."),
                    reloadApplied ? List.of() : List.of("quests"),
                    "abc123def456", 42L, false));
        }

        @Override
        public CompletableFuture<ServerLogsView> serverLogs(long afterSequence, int limit) {
            lastLogsAfter = afterSequence;
            lastLogsLimit = limit;
            return CompletableFuture.completedFuture(new ServerLogsView(
                    List.of(new ServerLogLine(7L, 1_700_000_000_000L, "WARN", "Citizens", "PNJ perdu")),
                    5L, 7L, 2L, 500, true, logsLimitation));
        }
    }
}
