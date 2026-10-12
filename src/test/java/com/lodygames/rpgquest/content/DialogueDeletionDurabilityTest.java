package com.lodygames.rpgquest.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Issue #248 — suppression de la copie serveur d'un DIALOGUE : sauvegarde, refus si un guide de
 * Hub l'utilise, et durabilité face à l'amorçage des exemples du JAR.
 */
class DialogueDeletionDurabilityTest {

    @TempDir
    Path data;

    private Path dialogues;
    private Path guides;
    private ContentDefinitionDeleter deleter;

    @BeforeEach
    void setUp() throws IOException {
        dialogues = Files.createDirectories(data.resolve("dialogues"));
        guides = Files.createDirectories(data.resolve("hub-guides"));
        deleter = new ContentDefinitionDeleter(data.resolve("quests"), data.resolve("stories"),
                dialogues, guides, data.resolve("content-backups"),
                ContentTombstones.besideContent(dialogues));
    }

    private void dialogue(String file, String id) throws IOException {
        Files.writeString(dialogues.resolve(file), "id: " + id + "\nstart: a\nnodes:\n  a:\n"
                + "    speaker: \"Aide\"\n    text: \"Bonjour\"\n    choices:\n"
                + "      - text: \"Au revoir\"\n        actions:\n          - type: CLOSE\n");
    }

    @Test
    @DisplayName("Un dialogue hors source est supprimé par son identifiant déclaré, avec sauvegarde")
    void deletesByDeclaredIdWithBackup() throws IOException {
        dialogue("aide_village.yml", "rpgquest:help");

        ContentDefinitionDeleter.Result result = deleter.delete("dialogues", "help");

        assertTrue(result.ok(), result.message());
        assertFalse(Files.exists(dialogues.resolve("aide_village.yml")));
        assertTrue(Files.exists(Path.of(result.backupPath())), "sauvegarde prise avant");
        assertFalse(result.backupPath().startsWith(dialogues.toString()),
                "jamais dans le dossier relu comme contenu");
    }

    @Test
    @DisplayName("Le dialogue d'aide d'un guide de Hub n'est pas supprimé")
    void refusesTheHelpDialogueOfAHubGuide() throws IOException {
        dialogue("guide.yml", "rpgquest:guide");
        Files.writeString(guides.resolve("hub_depart.yml"),
                "hub-id: hub_depart\nguide-dialogue: rpgquest:guide\nhelp-node: help_menu\n");

        ContentDefinitionDeleter.Result result = deleter.delete("dialogues", "rpgquest:guide");

        assertFalse(result.ok());
        assertEquals("REFERENCED", result.code());
        assertTrue(result.message().contains("hub_depart.yml"), result.message());
        assertTrue(Files.exists(dialogues.resolve("guide.yml")), "rien n'a été supprimé");
        assertFalse(Files.exists(data.resolve("content-backups")), "ni même sauvegardé");
    }

    @Test
    @DisplayName("Un exemple du JAR supprimé n'est pas recréé au démarrage suivant")
    void aDeletedBundledExampleIsNotReseeded() throws IOException {
        YamlDialogueEngine first = new YamlDialogueEngine(dialogues,
                LoggerFactory.getLogger("test"), List.of());
        first.start();
        assertTrue(Files.exists(dialogues.resolve("guard.yml")), "amorcé depuis le JAR");

        ContentDefinitionDeleter.Result result = deleter.delete("dialogues", "rpgquest:guard");
        assertTrue(result.ok(), result.message());
        assertTrue(result.message().contains("Suppression durable"), result.message());

        YamlDialogueEngine restarted = new YamlDialogueEngine(dialogues,
                LoggerFactory.getLogger("test"), List.of());
        restarted.start();
        assertFalse(Files.exists(dialogues.resolve("guard.yml")),
                "la pierre tombale empêche l'amorçage de le recréer");
        assertTrue(restarted.dialogues().stream().noneMatch(d -> d.id().getKey().equals("guard")));
    }

    @Test
    @DisplayName("Sans pierre tombale, l'amorçage recrée l'exemple : c'est bien elle qui compte")
    void withoutTombstoneTheExampleComesBack() throws IOException {
        new YamlDialogueEngine(dialogues, LoggerFactory.getLogger("test"), List.of()).start();
        Files.delete(dialogues.resolve("guard.yml"));

        new YamlDialogueEngine(dialogues, LoggerFactory.getLogger("test"), List.of()).start();

        assertTrue(Files.exists(dialogues.resolve("guard.yml")));
    }

    @Test
    @DisplayName("Republier un dialogue supprimé reste possible")
    void aTombstoneDoesNotPreventRecreation() throws IOException {
        dialogue("help.yml", "rpgquest:help");
        assertTrue(deleter.delete("dialogues", "help").ok());

        dialogue("help.yml", "rpgquest:help");
        YamlDialogueEngine engine = new YamlDialogueEngine(dialogues,
                LoggerFactory.getLogger("test"), List.of());
        engine.start();

        assertTrue(engine.dialogues().stream().anyMatch(d -> d.id().getKey().equals("help")));
    }

    @Test
    @DisplayName("Sans dossier de dialogues configuré, le type est refusé")
    void legacyConstructorStillRefusesDialogues() {
        ContentDefinitionDeleter legacy = new ContentDefinitionDeleter(data.resolve("quests"),
                data.resolve("stories"), data.resolve("content-backups"));
        assertEquals("INVALID_KIND", legacy.delete("dialogues", "help").code());
    }
}
