package be.lloyd.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.quest.model.KillEntityObjective;
import be.lloyd.rpgquest.quest.model.QuestDefinition;
import java.io.StringReader;
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

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
