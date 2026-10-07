package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.MoneyReward;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.RewardType;
import java.io.StringReader;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class QuestDefinitionParserTest {

    private final QuestDefinitionParser parser = new QuestDefinitionParser();

    @Test
    void validFileParsesSuccessfully() {
        QuestDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: rpgquest:first_steps
                title: "<gold>Premiers pas</gold>"
                description: "<gray>Élimine des araignées.</gray>"
                category: tutorial
                repeatable: false

                steps:
                  - id: kill_spiders
                    objectives:
                      - type: KILL_ENTITY
                        entity: SPIDER
                        amount: 10

                rewards:
                  - type: EXPERIENCE
                    amount: 50

                variables:
                  started: "true"
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        QuestDefinition quest = result.quest();
        assertEquals("rpgquest:first_steps", quest.id().toString());
        assertEquals("<gold>Premiers pas</gold>", quest.title().base());
        assertEquals("tutorial", quest.category());
        assertEquals(Material.BOOK, quest.icon(), "icône par défaut quand « icon » est absent");
        assertFalse(quest.repeatable());
        assertEquals(1, quest.steps().size());
        assertEquals("kill_spiders", quest.steps().get(0).id());
        assertEquals(new KillEntityObjective(EntityType.SPIDER, 10), quest.steps().get(0).objectives().get(0));
        assertEquals("true", quest.variables().get("started"));
    }

    @Test
    void missingRequiredFieldsAreAllReportedTogether() {
        QuestDefinitionParser.ParseResult result = parser.parse("incomplete.yml", load("""
                steps:
                  - id: only_step
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(QuestLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("title"), combined);
        assertTrue(combined.contains("description"), combined);
        assertTrue(combined.contains("category"), combined);
    }

    @Test
    void unknownObjectiveTypeIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-objective.yml", load(minimalQuestWithSteps("""
                steps:
                  - id: step_one
                    objectives:
                      - type: FLY_TO_THE_MOON
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("type d'objectif inconnu")));
    }

    @Test
    void stepWithoutObjectiveIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("empty-step.yml", load(minimalQuestWithSteps("""
                steps:
                  - id: step_one
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("objectives")));
    }

    @Test
    void negativeAmountRewardIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-reward.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: EXPERIENCE
                    amount: -5
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("amount")));
    }

    @Test
    void unknownRewardTypeIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-reward-type.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: FREE_HOUSE
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("type de récompense inconnu")));
    }

    @Test
    void selfReferencingPrerequisiteIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("self-ref.yml", load(minimalQuestWithStepsAnd("""
                prerequisites:
                  - rpgquest:loop
                """).replace("id: rpgquest:base", "id: rpgquest:loop")));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("elle-même")));
    }

    @Test
    void unknownMaterialIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-material.yml", load(minimalQuestWithSteps("""
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: NOT_A_REAL_BLOCK
                        amount: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("matériau inconnu")));
    }

    @Test
    void explicitIconIsParsed() {
        QuestDefinitionParser.ParseResult result = parser.parse("with-icon.yml", load("""
                id: rpgquest:base
                title: "Titre"
                description: "Description"
                category: test
                icon: IRON_SWORD
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(Material.IRON_SWORD, result.quest().icon());
    }

    @Test
    void unknownIconIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("bad-icon.yml", load(minimalQuestWithSteps("""
                icon: NOT_A_REAL_MATERIAL
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("icon")));
    }

    private String minimalQuestWithSteps(String stepsYaml) {
        return """
                id: rpgquest:base
                title: "Titre"
                description: "Description"
                category: test
                """ + stepsYaml;
    }

    private String minimalQuestWithStepsAnd(String extraYaml) {
        return minimalQuestWithSteps("""
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """) + extraYaml;
    }

    // ---- Récompense monétaire (issue #16) -----------------------------------------------------

    @Test
    void aMoneyRewardIsParsedWithItsAmount() {
        QuestDefinitionParser.ParseResult result = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: MONEY
                    amount: 250
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(1, result.quest().rewards().size());
        MoneyReward reward = (MoneyReward) result.quest().rewards().get(0);
        assertEquals(250, reward.amount());
        assertEquals(RewardType.MONEY, reward.type());
    }

    @Test
    void aMoneyRewardRefusesANonPositiveOrMissingAmount() {
        for (String amount : new String[] {"0", "-50"}) {
            QuestDefinitionParser.ParseResult result = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                    rewards:
                      - type: MONEY
                        amount: %s
                    """.formatted(amount))));
            assertFalse(result.isSuccess(), "montant « " + amount + " » doit être refusé");
            assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("amount")));
        }

        QuestDefinitionParser.ParseResult missing = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: MONEY
                """)));
        assertFalse(missing.isSuccess(), "sans montant, il n'y a rien à créditer");
    }

    @Test
    void aLargeMoneyRewardIsAcceptedBecauseBalancingIsNotATechnicalRule() {
        // Aucun plafond côté moteur : décider du gain maximum serait prendre une décision de
        // gameplay à la place de l'auteur. Le garde-fou contre la faute de frappe est un
        // AVERTISSEMENT côté panel, et il n'empêche rien.
        QuestDefinitionParser.ParseResult result = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: MONEY
                    amount: 5000000
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(5_000_000, ((MoneyReward) result.quest().rewards().get(0)).amount());
    }

    @Test
    void aMoneyRewardCoexistsWithTheOtherRewardTypes() {
        QuestDefinitionParser.ParseResult result = parser.parse("money.yml", load(minimalQuestWithStepsAnd("""
                rewards:
                  - type: EXPERIENCE
                    amount: 10
                  - type: MONEY
                    amount: 25
                  - type: ITEM
                    material: IRON_INGOT
                    amount: 2
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        // L'ORDRE est préservé : il décide de l'ordre d'affichage du résumé de fin de quête.
        assertEquals(List.of(RewardType.EXPERIENCE, RewardType.MONEY, RewardType.ITEM),
                result.quest().rewards().stream().map(r -> r.type()).toList());
    }

    // ---- Remise d'objets à un PNJ (issue #123) -------------------------------------------------

    @Test
    void aDeliverObjectiveIsParsedWithItsNpcMaterialAndAmount() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver.yml", load("""
                id: rpgquest:deliver
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                        npc: blacksmith
                        material: LEATHER
                        amount: 4
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(new DeliverItemToNpcObjective("blacksmith", Material.LEATHER, 4),
                result.quest().steps().get(0).objectives().get(0));
        assertEquals(4, QuestObjective.requiredAmount(result.quest().steps().get(0).objectives().get(0)));
    }

    @Test
    void severalDeliverObjectivesCoexistInTheSameStep() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver_multi.yml", load("""
                id: rpgquest:deliver_multi
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: STICK
                        amount: 1
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: COBBLESTONE
                        amount: 2
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: LEATHER
                        amount: 4
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: WHEAT_SEEDS
                        amount: 3
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        // L'ORDRE est préservé : il décide de l'ordre du récapitulatif lu par le joueur.
        assertEquals(List.of(Material.STICK, Material.COBBLESTONE, Material.LEATHER, Material.WHEAT_SEEDS),
                result.quest().steps().get(0).objectives().stream()
                        .map(o -> ((DeliverItemToNpcObjective) o).material())
                        .toList());
    }

    @Test
    void aDeliverObjectiveWithoutNpcMaterialOrAmountIsRejectedWithEveryReason() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver_broken.yml", load("""
                id: rpgquest:deliver_broken
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                """));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(QuestLoadIssue::message).toList());
        assertTrue(combined.contains("npc"), combined);
        assertTrue(combined.contains("material"), combined);
        assertTrue(combined.contains("amount"), combined);
    }

    @Test
    void aDeliverObjectiveWithAnUnknownMaterialIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver_bad_material.yml", load("""
                id: rpgquest:deliver_bad
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: PAS_UN_OBJET
                        amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(String.join(" ", result.issues().stream().map(QuestLoadIssue::message).toList())
                .contains("PAS_UN_OBJET"));
    }

    @Test
    void aDeliverObjectiveWithANonPositiveAmountIsRejected() {
        QuestDefinitionParser.ParseResult result = parser.parse("deliver_zero.yml", load("""
                id: rpgquest:deliver_zero
                title: "Titre"
                description: "Description"
                category: test

                steps:
                  - id: deliver_step
                    objectives:
                      - type: DELIVER_ITEM_TO_NPC
                        npc: guard
                        material: LEATHER
                        amount: 0
                """));

        assertFalse(result.isSuccess());
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
