package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DialogueLoaderTest {

    private final DialogueLoader loader = new DialogueLoader(List.of("give", "xp"));

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(simpleDialogue("rpgquest:duplicate")));
        files.put("b.yml", load(simpleDialogue("rpgquest:duplicate")));

        DialogueLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void openDialogueReferencingMissingDialogueIsRejected() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("opener.yml", load(dialogueThatOpens("rpgquest:opener", "rpgquest:ghost")));

        DialogueLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertTrue(report.issues().stream().anyMatch(i -> i.message().contains("introuvable")));
    }

    @Test
    void cycleBetweenTwoDialoguesIsDetectedAndBothRejected() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(dialogueThatOpens("rpgquest:a", "rpgquest:b")));
        files.put("b.yml", load(dialogueThatOpens("rpgquest:b", "rpgquest:a")));

        DialogueLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertTrue(report.issues().stream().anyMatch(i -> i.message().contains("boucle")),
                () -> "issues: " + report.issues());
    }

    @Test
    void selfLoopViaNextWithinSameDialogueIsAllowed() {
        // Un menu "hub" qui revient sur lui-même via `next` est un usage normal, pas une boucle rejetée.
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("hub.yml", load("""
                id: rpgquest:hub
                start: menu
                nodes:
                  menu:
                    speaker: "Garde"
                    text: "Que veux-tu ?"
                    choices:
                      - text: "Reste ici"
                        next: menu
                      - text: "Partir"
                        actions:
                          - type: CLOSE
                """));

        DialogueLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    @Test
    void multipleFilesWithOneInvalidStillLoadTheOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good-a.yml", load(simpleDialogue("rpgquest:good_a")));
        files.put("broken.yml", load("start: nowhere\n"));
        files.put("good-b.yml", load(simpleDialogue("rpgquest:good_b")));

        DialogueLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertFalse(report.issues().isEmpty());
        assertTrue(report.issues().stream().allMatch(issue -> issue.file().equals("broken.yml")));
    }

    private String simpleDialogue(String id) {
        return """
                id: %s
                start: greeting
                nodes:
                  greeting:
                    speaker: "Garde"
                    text: "Bienvenue."
                    choices:
                      - text: "D'accord"
                        actions:
                          - type: CLOSE
                """.formatted(id);
    }

    private String dialogueThatOpens(String id, String target) {
        return """
                id: %s
                start: greeting
                nodes:
                  greeting:
                    speaker: "Garde"
                    text: "Bienvenue."
                    choices:
                      - text: "Suivant"
                        actions:
                          - type: OPEN_DIALOGUE
                            dialogue: %s
                """.formatted(id, target);
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
