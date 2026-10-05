package com.lodygames.rpgquest.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Suppression de la copie <strong>serveur</strong> d'une quête ou d'une story (issue #194).
 *
 * <p>Deux propriétés comptent ici, et aucune n'est cosmétique : le fichier est retrouvé par
 * l'<strong>identifiant déclaré dedans</strong> — jamais par un nom de fichier deviné — et rien
 * n'est supprimé sans sauvegarde préalable.</p>
 */
class ContentDefinitionDeleterTest {

    @TempDir
    Path tempDir;

    private Path quests;
    private Path stories;
    private Path backups;
    private ContentDefinitionDeleter deleter;

    @BeforeEach
    void setUp() throws IOException {
        quests = Files.createDirectories(tempDir.resolve("quests"));
        stories = Files.createDirectories(tempDir.resolve("stories"));
        backups = tempDir.resolve("content-backups");
        deleter = new ContentDefinitionDeleter(quests, stories, backups);
    }

    private void writeQuestFile(String fileName, String declaredId) throws IOException {
        Files.writeString(quests.resolve(fileName),
                "id: " + declaredId + "\ntitle: \"Quête de test\"\n");
    }

    @Test
    @DisplayName("Le fichier est retrouvé par son id DÉCLARÉ, même si le nom de fichier diffère")
    void findsByDeclaredIdNotFileName() throws IOException {
        // Piège réel : le nom de fichier n'a pas à correspondre à l'identifiant.
        writeQuestFile("un-nom-sans-rapport.yml", "rpgquest:test_cible");

        ContentDefinitionDeleter.Result result = deleter.delete("quests", "test_cible");

        assertTrue(result.ok(), result.message());
        assertEquals("un-nom-sans-rapport.yml", result.file());
        assertFalse(Files.exists(quests.resolve("un-nom-sans-rapport.yml")));
    }

    @Test
    @DisplayName("Le namespace est optionnel des deux côtés")
    void namespaceIsOptional() throws IOException {
        writeQuestFile("a.yml", "test_sans_ns");
        assertTrue(deleter.delete("quests", "rpgquest:test_sans_ns").ok());

        writeQuestFile("b.yml", "rpgquest:test_avec_ns");
        assertTrue(deleter.delete("quests", "test_avec_ns").ok());
    }

    @Test
    @DisplayName("Une sauvegarde horodatée est prise AVANT la suppression, hors des dossiers de contenu")
    void backsUpBeforeDeleting() throws IOException {
        writeQuestFile("test.yml", "test_cible");
        String original = Files.readString(quests.resolve("test.yml"));

        ContentDefinitionDeleter.Result result = deleter.delete("quests", "test_cible");

        assertTrue(result.ok(), result.message());
        assertNotNull(result.backupPath());
        Path backup = Path.of(result.backupPath());
        assertTrue(Files.exists(backup));
        assertEquals(original, Files.readString(backup), "la sauvegarde doit être fidèle");
        assertFalse(backup.startsWith(quests),
                "une sauvegarde dans quests/ serait relue comme une définition");
        assertTrue(result.message().contains("sauvegarde"), result.message());
    }

    @Test
    @DisplayName("Un identifiant inconnu ne supprime rien, et le dit")
    void unknownIdDeletesNothing() throws IOException {
        writeQuestFile("test.yml", "test_autre");

        ContentDefinitionDeleter.Result result = deleter.delete("quests", "test_absent");

        assertFalse(result.ok());
        assertEquals("NOT_FOUND", result.code());
        assertTrue(Files.exists(quests.resolve("test.yml")), "aucun autre fichier ne doit partir");
    }

    @Test
    @DisplayName("Un fichier illisible n'est jamais supprimé « au cas où »")
    void unreadableFileIsNeverDeleted() throws IOException {
        Files.writeString(quests.resolve("casse.yml"), "\t\tceci: [n'est pas: du yaml\n");
        writeQuestFile("bon.yml", "test_cible");

        ContentDefinitionDeleter.Result result = deleter.delete("quests", "test_cible");

        assertTrue(result.ok(), result.message());
        assertEquals("bon.yml", result.file());
        assertTrue(Files.exists(quests.resolve("casse.yml")),
                "le fichier illisible doit rester intact");
    }

    @Test
    @DisplayName("Seuls « quests » et « stories » sont supprimables : aucun chemin ne vient du panel")
    void onlyKnownKindsAreAccepted() {
        for (String kind : new String[] {"dialogues", "npcs", "..", "/etc", "", null}) {
            ContentDefinitionDeleter.Result result = deleter.delete(kind, "x");
            assertFalse(result.ok(), "type accepté à tort : " + kind);
            assertEquals("INVALID_KIND", result.code());
        }
    }

    @Test
    @DisplayName("Les stories suivent exactement les mêmes règles")
    void storiesBehaveTheSame() throws IOException {
        Files.writeString(stories.resolve("saga.yml"), "id: rpgquest:test_saga\nname: \"Saga\"\n");

        ContentDefinitionDeleter.Result result = deleter.delete("stories", "test_saga");

        assertTrue(result.ok(), result.message());
        assertFalse(Files.exists(stories.resolve("saga.yml")));
        assertTrue(Files.exists(Path.of(result.backupPath())));
    }

    @Test
    @DisplayName("Un dossier absent renvoie une erreur lisible, pas une exception")
    void missingDirectoryIsReadableFailure() {
        ContentDefinitionDeleter.Result result =
                new ContentDefinitionDeleter(tempDir.resolve("absent"), stories, backups)
                        .delete("quests", "test_cible");

        assertFalse(result.ok());
        assertEquals("NOT_FOUND", result.code());
        assertTrue(result.message().contains("Aucun dossier"), result.message());
    }

    @Test
    @DisplayName("Un identifiant vide ou absurde est refusé avant tout accès disque")
    void blankIdIsRejected() {
        for (String id : new String[] {null, "", "   ", ":"}) {
            ContentDefinitionDeleter.Result result = deleter.delete("quests", id);
            assertFalse(result.ok(), "identifiant accepté à tort : " + id);
        }
    }
}
