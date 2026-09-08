package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.DialogueDraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Écriture sûre d'un squelette de dialogue : atomique, refus d'écrasement, round-trip parse. */
class DialogueDefinitionStoreTest {

    @TempDir
    Path dir;

    private DialogueDefinitionStore store() {
        return new DialogueDefinitionStore(dir, List.of("give"));
    }

    @Test
    void createWritesASkeletonThatReloadsCleanly() {
        DialogueDraft draft = DialogueDraft.skeleton("woodcutter_bob", "Bûcheron Bob",
                "<white>Bonjour voyageur.</white>", "Au revoir");
        DialogueDefinitionStore.Result r = store().create(draft);

        assertTrue(r.ok(), r.message());
        assertEquals("CREATED", r.code());
        assertEquals("woodcutter_bob.yml", r.file());
        assertTrue(Files.exists(dir.resolve("woodcutter_bob.yml")));

        // Le fichier écrit se recharge en une DialogueDefinition valide.
        DialogueLoadReport report = new DialogueLoader(List.of()).loadDirectory(dir);
        assertTrue(report.issues().isEmpty(), report.issues().toString());
        assertEquals(1, report.loaded().size());
        var loaded = report.loaded().get(0);
        assertEquals("rpgquest:woodcutter_bob", loaded.id().toString());
        assertEquals("start", loaded.startNodeId());
        assertEquals(1, loaded.nodes().size());
        assertEquals("Bûcheron Bob", loaded.startNode().speaker());
    }

    @Test
    void createRefusesToOverwriteAnExistingDialogue() {
        DialogueDraft draft = DialogueDraft.skeleton("guard", "Garde", "Salut.", "Bye");
        assertTrue(store().create(draft).ok());

        DialogueDefinitionStore.Result again = store().create(
                DialogueDraft.skeleton("guard", "Garde", "Autre texte.", "Bye"));
        assertFalse(again.ok());
        assertEquals("EXISTS", again.code());
        // Le contenu d'origine est intact.
        var loaded = new DialogueLoader(List.of()).loadDirectory(dir).loaded().get(0);
        assertEquals("Salut.", loaded.startNode().text().base());
    }

    @Test
    void invalidKeyIsRejectedBeforeAnyWrite() {
        assertTrue(assertThrowsIae(() -> DialogueDraft.skeleton("Bad Key", "X", "Y", "Z")));
        assertFalse(Files.exists(dir.resolve("Bad Key.yml")));
    }

    private static boolean assertThrowsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
