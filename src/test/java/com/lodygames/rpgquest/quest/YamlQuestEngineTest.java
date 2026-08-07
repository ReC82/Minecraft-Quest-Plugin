package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class YamlQuestEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void startGeneratesBundledExamplesThatParseWithoutErrors() {
        YamlQuestEngine engine = new YamlQuestEngine(tempDir.resolve("quests"), NOPLogger.NOP_LOGGER);

        engine.start();

        assertTrue(Files.exists(tempDir.resolve("quests/first_steps.yml")));
        assertTrue(Files.exists(tempDir.resolve("quests/woodcutters_request.yml")));
        assertTrue(Files.exists(tempDir.resolve("quests/crystal_hunt.yml")));
        assertEquals(0, engine.lastReport().issues().size(), () -> "issues: " + engine.lastReport().issues());
        assertEquals(3, engine.quests().size());
    }

    @Test
    void startDoesNotOverwriteAnExistingCustomizedExample() throws Exception {
        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("first_steps.yml"), """
                id: rpgquest:first_steps
                title: "Titre personnalisé"
                description: "Description personnalisée"
                category: tutorial
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """);

        YamlQuestEngine engine = new YamlQuestEngine(questsDir, NOPLogger.NOP_LOGGER);
        engine.start();

        assertEquals("Titre personnalisé", engine.quests().stream()
                .filter(q -> q.id().toString().equals("rpgquest:first_steps"))
                .findFirst().orElseThrow().title().base());
    }

    @Test
    void validateDoesNotChangeTheActiveQuestSet() throws Exception {
        Path questsDir = tempDir.resolve("quests");
        YamlQuestEngine engine = new YamlQuestEngine(questsDir, NOPLogger.NOP_LOGGER);
        engine.start();
        int before = engine.quests().size();

        Files.writeString(questsDir.resolve("broken.yml"), "category: incomplete");
        QuestLoadReport validation = engine.validate();

        assertFalse(validation.issues().isEmpty());
        assertEquals(before, engine.quests().size(), "validate() ne doit pas modifier l'ensemble de quêtes actif");
    }

    @Test
    void reloadAppliesTheNewValidQuestSet() throws Exception {
        Path questsDir = tempDir.resolve("quests");
        YamlQuestEngine engine = new YamlQuestEngine(questsDir, NOPLogger.NOP_LOGGER);
        engine.start();
        int before = engine.quests().size();

        Files.writeString(questsDir.resolve("extra.yml"), """
                id: rpgquest:extra
                title: "Titre"
                description: "Description"
                category: test
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """);
        engine.reload();

        assertEquals(before + 1, engine.quests().size());
    }
}
