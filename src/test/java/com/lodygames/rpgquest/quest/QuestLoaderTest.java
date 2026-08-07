package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestLoaderTest {

    private final QuestLoader loader = new QuestLoader();

    @TempDir
    Path tempDir;

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(quest("rpgquest:duplicate")));
        files.put("b.yml", load(quest("rpgquest:duplicate")));

        QuestLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void missingPrerequisiteReferenceIsRejected() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("depends.yml", load(quest("rpgquest:needs_ghost", "rpgquest:ghost_quest")));

        QuestLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(1, report.issues().size());
        assertTrue(report.issues().get(0).message().contains("prérequis introuvable"));
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good-a.yml", load(quest("rpgquest:good_a")));
        // Un seul problème délibéré (type d'objectif inconnu) pour isoler ce scénario
        // de l'accumulation multi-erreurs déjà couverte par QuestDefinitionParserTest.
        files.put("broken.yml", load(minimalQuestWithSteps("""
                steps:
                  - id: step_one
                    objectives:
                      - type: NOT_A_REAL_OBJECTIVE
                """)));
        files.put("good-b.yml", load(quest("rpgquest:good_b")));

        QuestLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertTrue(report.loaded().stream().anyMatch(q -> q.id().toString().equals("rpgquest:good_a")));
        assertTrue(report.loaded().stream().anyMatch(q -> q.id().toString().equals("rpgquest:good_b")));
        assertFalse(report.issues().isEmpty());
        assertTrue(report.issues().stream().allMatch(issue -> issue.file().equals("broken.yml")),
                () -> "toutes les erreurs doivent venir du fichier fautif : " + report.issues());
    }

    @Test
    void validPrerequisiteChainLoadsSuccessfully() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("base.yml", load(quest("rpgquest:base")));
        files.put("advanced.yml", load(quest("rpgquest:advanced", "rpgquest:base")));

        QuestLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    @Test
    void loadDirectoryReturnsEmptyReportWhenDirectoryIsMissing() {
        QuestLoadReport report = loader.loadDirectory(tempDir.resolve("does-not-exist"));

        assertEquals(0, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    @Test
    void loadDirectoryReadsRealFilesFromDisk() throws Exception {
        Files.writeString(tempDir.resolve("first.yml"), quest("rpgquest:from_disk"));

        QuestLoadReport report = loader.loadDirectory(tempDir);

        assertEquals(1, report.loaded().size());
        assertEquals("rpgquest:from_disk", report.loaded().get(0).id().toString());
    }

    private String quest(String id) {
        return """
                id: %s
                title: "Titre"
                description: "Description"
                category: test
                steps:
                  - id: step_one
                    objectives:
                      - type: BREAK_BLOCK
                        material: STONE
                        amount: 1
                """.formatted(id);
    }

    private String quest(String id, String prerequisite) {
        return quest(id) + """
                prerequisites:
                  - %s
                """.formatted(prerequisite);
    }

    private String minimalQuestWithSteps(String stepsYaml) {
        return """
                id: rpgquest:broken
                title: "Titre"
                description: "Description"
                category: test
                """ + stepsYaml;
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
