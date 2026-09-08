package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.web.Json;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Sérialisation réelle du payload {@code quest.list} après structuration (#78 / #75).
 *
 * <p>On exerce {@link AgentActionExecutor} de bout en bout puis on <strong>sérialise en JSON</strong>
 * ({@link Json#write}) le {@code details} produit — le contrat testé est le JSON réellement envoyé à
 * PlugAdmin, pas seulement les objets Java. Couvre : objectifs kill / collect / craft / talk,
 * récompenses XP / item / variable / commande (longue, non tronquée), quantités, identifiants
 * techniques, giver absent et présent.</p>
 */
class QuestListPayloadTest {

    private static final String LONG_COMMAND =
            "customitem give %player% rpgquest:miner_pickaxe 1 && lp user %player% permission set rpgquest.claim.tier2 true "
                    + "&& broadcast %player% vient de terminer une quête épique dans les mines profondes";

    private final AgentActionExecutor executor = new AgentActionExecutor(
            ref -> CompletableFuture.completedFuture(Optional.empty()),
            (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()),
            new QuestCatalogActions());

    private Map<String, Object> questListDetails() {
        AgentActionOutcome outcome = executor.execute(new AgentAction("q", "quest.list", Map.of())).join();
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        return outcome.details();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> quests(Map<String, Object> details) {
        return (List<Map<String, Object>>) details.get("quests");
    }

    @Test
    void structuredObjectivesAreSerialized() {
        String json = Json.write(questListDetails());

        // kind + target + amount présents, sans regex nécessaire côté panel
        assertTrue(json.contains("\"objectiveDetails\""), "champ structuré présent");
        assertTrue(json.contains("\"kind\":\"KILL_ENTITY\"") && json.contains("\"target\":\"SPIDER\"")
                && json.contains("\"amount\":5"), "objectif kill entity structuré");
        assertTrue(json.contains("\"kind\":\"COLLECT_ITEM\"") && json.contains("\"target\":\"AMETHYST_SHARD\"")
                && json.contains("\"amount\":2"), "objectif collect item structuré");
        assertTrue(json.contains("\"kind\":\"CRAFT_ITEM\"") && json.contains("\"target\":\"DIAMOND_SWORD\""),
                "objectif craft structuré");
        assertTrue(json.contains("\"kind\":\"TALK_TO_NPC\"") && json.contains("\"target\":\"guard\""),
                "objectif talk-to-npc structuré");

        // legacy toujours présent (compat agent déployé)
        assertTrue(json.contains("\"objectives\":[\"Tuer SPIDER (x5)\"]"), "chaînes legacy conservées");
    }

    @Test
    void structuredRewardsAreSerializedAndCommandNotTruncated() {
        String json = Json.write(questListDetails());

        assertTrue(json.contains("\"rewardDetails\""), "champ structuré présent");
        assertTrue(json.contains("\"kind\":\"EXPERIENCE\"") && json.contains("\"amount\":100"), "XP structuré");
        assertTrue(json.contains("\"kind\":\"ITEM\"") && json.contains("\"target\":\"IRON_SWORD\"")
                && json.contains("\"amount\":1"), "item reward structuré");
        assertTrue(json.contains("\"kind\":\"VARIABLE\"") && json.contains("\"target\":\"CLAIM_TIER_1\"")
                && json.contains("\"value\":\"true\""), "variable reward structurée");
        assertTrue(json.contains("\"kind\":\"COMMAND\""), "command reward structurée");

        // commande complète, jamais coupée à 60 caractères, dans le champ structuré
        String structuredCommand = commandRewardOf(quests(questListDetails()).get(0));
        assertEquals(LONG_COMMAND, structuredCommand, "commande longue transportée intégralement");
        assertFalse(structuredCommand.contains("…"), "aucune troncature de la commande structurée");
        assertTrue(json.contains(LONG_COMMAND), "commande longue présente dans le JSON");
    }

    @SuppressWarnings("unchecked")
    private String commandRewardOf(Map<String, Object> quest) {
        for (Map<String, Object> r : (List<Map<String, Object>>) quest.get("rewardDetails")) {
            if ("COMMAND".equals(r.get("kind"))) {
                return String.valueOf(r.get("command"));
            }
        }
        throw new AssertionError("aucune récompense COMMAND");
    }

    @Test
    void giverIsPresentOnlyWhenDeclared() {
        List<Map<String, Object>> quests = quests(questListDetails());
        Map<String, Object> withGiver = quests.stream()
                .filter(q -> "rpgquest:crystal_hunt".equals(q.get("id"))).findFirst().orElseThrow();
        Map<String, Object> noGiver = quests.stream()
                .filter(q -> "rpgquest:first_steps".equals(q.get("id"))).findFirst().orElseThrow();

        assertEquals("guard", withGiver.get("giverId"));
        assertNull(noGiver.get("giverId"), "aucune clé giverId si la quête n'en déclare pas");
        assertFalse(Json.write(noGiver).contains("giverId"), "giver absent du JSON quand non déclaré");
    }

    // ---- Fausse façade métier : deux quêtes, tous les types utiles -----------------------

    private final class QuestCatalogActions extends StubAgentActions {
        @Override
        public List<QuestSummary> questDefinitions() {
            QuestStepSummary hunt = new QuestStepSummary("hunt_spiders",
                    List.of("Tuer SPIDER (x5)"),
                    List.of(new ObjectiveSummary("KILL_ENTITY", "SPIDER", 5, "Tuer SPIDER (x5)")));
            QuestStepSummary gather = new QuestStepSummary("gather_crystals",
                    List.of("Collecter AMETHYST_SHARD (x2)"),
                    List.of(new ObjectiveSummary("COLLECT_ITEM", "AMETHYST_SHARD", 2, "Collecter AMETHYST_SHARD (x2)")));
            QuestStepSummary forge = new QuestStepSummary("forge_blade",
                    List.of("Fabriquer DIAMOND_SWORD (x1)"),
                    List.of(new ObjectiveSummary("CRAFT_ITEM", "DIAMOND_SWORD", 1, "Fabriquer DIAMOND_SWORD (x1)")));
            QuestStepSummary report = new QuestStepSummary("report_to_guard",
                    List.of("Parler à guard (x1)"),
                    List.of(new ObjectiveSummary("TALK_TO_NPC", "guard", 1, "Parler à guard (x1)")));

            List<RewardSummary> rewards = List.of(
                    new RewardSummary("EXPERIENCE", 100, null, null, null, "+100 XP"),
                    new RewardSummary("ITEM", 1, "IRON_SWORD", null, null, "+1x IRON_SWORD"),
                    new RewardSummary("VARIABLE", 0, "CLAIM_TIER_1", "true", null, "variable CLAIM_TIER_1 = true"),
                    new RewardSummary("COMMAND", 0, null, null, LONG_COMMAND, "commande console : " + LONG_COMMAND));

            QuestSummary crystalHunt = new QuestSummary("rpgquest:crystal_hunt", "La chasse aux cristaux",
                    "crafting", false, List.of("rpgquest:first_steps"),
                    List.of(hunt, gather, forge, report),
                    List.of("+100 XP", "+1x IRON_SWORD", "variable CLAIM_TIER_1 = true",
                            "commande console : customitem give %player% rpgquest:miner_picka…"),
                    rewards, "guard", null);

            QuestSummary firstSteps = new QuestSummary("rpgquest:first_steps", "Premiers pas",
                    "tutorial", false, List.of(),
                    List.of(new QuestStepSummary("kill_spiders", List.of("Tuer SPIDER (x10)"),
                            List.of(new ObjectiveSummary("KILL_ENTITY", "SPIDER", 10, "Tuer SPIDER (x10)")))),
                    List.of("+50 XP"),
                    List.of(new RewardSummary("EXPERIENCE", 50, null, null, null, "+50 XP")),
                    null, null);

            return List.of(crystalHunt, firstSteps);
        }
    }
}
