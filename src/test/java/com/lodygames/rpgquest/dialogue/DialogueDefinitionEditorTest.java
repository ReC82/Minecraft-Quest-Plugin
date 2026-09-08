package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Édition guidée d'un dialogue : round-trip fidèle (actions/conditions riches conservées), écriture
 * atomique, restauration en cas de rechargement invalide, garde-fous des choix « non simples ».
 */
class DialogueDefinitionEditorTest {

    @TempDir
    Path dir;

    private static final String GUARD = """
            id: rpgquest:guard
            start: greeting
            nodes:
              greeting:
                speaker: "Garde"
                text: "<white>Bienvenue.</white>"
                choices:
                  - text: "J'accepte"
                    conditions:
                      - type: QUEST_STATE
                        quest: rpgquest:first_steps
                        state: NOT_STARTED
                    actions:
                      - type: START_QUEST
                        quest: rpgquest:first_steps
                    next: accepted
                  - text: "Non merci"
                    next: refused
              accepted:
                speaker: "Garde"
                text: "<green>Bien.</green>"
                choices:
                  - text: "OK"
                    actions:
                      - type: CLOSE
              refused:
                speaker: "Garde"
                text: "<gray>Comme tu voudras.</gray>"
                choices:
                  - text: "OK"
                    actions:
                      - type: CLOSE
            """;

    private DialogueDefinitionEditor editor;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(dir.resolve("guard.yml"), GUARD, StandardCharsets.UTF_8);
        editor = new DialogueDefinitionEditor(dir, List.of("give"));
    }

    private DialogueDefinition reload(String id) {
        return new DialogueLoader(List.of("give")).loadDirectory(dir).loaded().stream()
                .filter(d -> d.id().toString().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void updateNodePreservesRichChoicesAndReloadsCleanly() {
        DialogueDefinitionEditor.Result r = editor.updateNode("rpgquest:guard", "greeting", "Capitaine",
                "<yellow>Salut, soldat.</yellow>");
        assertTrue(r.ok(), r.message());
        assertEquals("UPDATED", r.code());
        assertEquals("guard.yml", r.file());

        DialogueDefinition d = reload("rpgquest:guard");
        assertEquals("Capitaine", d.nodes().get("greeting").speaker());
        assertEquals("<yellow>Salut, soldat.</yellow>", d.nodes().get("greeting").text().base());
        // La condition QUEST_STATE et l'action START_QUEST du 1er choix sont conservées.
        assertEquals(1, d.nodes().get("greeting").choices().get(0).conditions().size());
        assertEquals(1, d.nodes().get("greeting").choices().get(0).actions().size());
        assertEquals("accepted", d.nodes().get("greeting").choices().get(0).next());
        assertEquals(3, d.nodes().size());
    }

    @Test
    void createNodeAddsAnOrphanNodeWithACloseChoice() {
        DialogueDefinitionEditor.Result r = editor.createNode("rpgquest:guard", "farewell", "Garde",
                "<gray>À bientôt.</gray>", "Fermer");
        assertTrue(r.ok(), r.message());

        DialogueDefinition d = reload("rpgquest:guard");
        assertEquals(4, d.nodes().size());
        assertTrue(d.nodes().containsKey("farewell"));
        assertEquals(1, d.nodes().get("farewell").choices().size());
        assertTrue(d.nodes().get("farewell").choices().get(0).actions().get(0)
                instanceof com.lodygames.rpgquest.dialogue.model.CloseAction);
    }

    @Test
    void createNodeRejectsDuplicateId() {
        DialogueDefinitionEditor.Result r = editor.createNode("rpgquest:guard", "greeting", "X", "Y", null);
        assertFalse(r.ok());
        assertEquals("NODE_EXISTS", r.code());
        // fichier inchangé : greeting garde son texte d'origine
        assertEquals("<white>Bienvenue.</white>", reload("rpgquest:guard").nodes().get("greeting").text().base());
    }

    @Test
    void createNodeRejectsInvalidId() {
        assertEquals("INVALID", editor.createNode("rpgquest:guard", "Bad Id", "X", "Y", null).code());
    }

    @Test
    void addChoiceTowardsExistingNode() {
        DialogueDefinitionEditor.Result r = editor.addChoice("rpgquest:guard", "accepted",
                "Revenir au début", "greeting", false);
        assertTrue(r.ok(), r.message());

        DialogueDefinition d = reload("rpgquest:guard");
        assertEquals(2, d.nodes().get("accepted").choices().size());
        assertEquals("greeting", d.nodes().get("accepted").choices().get(1).next());
    }

    @Test
    void addChoiceRejectsUnknownTarget() {
        DialogueDefinitionEditor.Result r = editor.addChoice("rpgquest:guard", "accepted", "X", "nowhere", false);
        assertFalse(r.ok());
        assertEquals("UNKNOWN_TARGET", r.code());
    }

    @Test
    void addChoiceRejectsBothCloseAndNext() {
        assertEquals("INVALID", editor.addChoice("rpgquest:guard", "accepted", "X", "greeting", true).code());
        assertEquals("INVALID", editor.addChoice("rpgquest:guard", "accepted", "X", null, false).code());
    }

    @Test
    void updateChoiceRefusesNonSimpleChoice() {
        // greeting choix #0 porte une condition + une action START_QUEST -> non simple.
        DialogueDefinitionEditor.Result r = editor.updateChoice("rpgquest:guard", "greeting", 0,
                "Texte modifié", "accepted", false);
        assertFalse(r.ok());
        assertEquals("UNSAFE_CHOICE", r.code());
        assertEquals("J'accepte", reload("rpgquest:guard").nodes().get("greeting").choices().get(0).text().base());
    }

    @Test
    void updateChoiceEditsSimpleChoiceTextAndTarget() {
        DialogueDefinitionEditor.Result r = editor.updateChoice("rpgquest:guard", "greeting", 1,
                "Je refuse poliment", "accepted", false);
        assertTrue(r.ok(), r.message());
        DialogueDefinition d = reload("rpgquest:guard");
        assertEquals("Je refuse poliment", d.nodes().get("greeting").choices().get(1).text().base());
        assertEquals("accepted", d.nodes().get("greeting").choices().get(1).next());
    }

    @Test
    void deleteChoiceRefusesLastChoiceOfNode() {
        DialogueDefinitionEditor.Result r = editor.deleteChoice("rpgquest:guard", "accepted", 0);
        assertFalse(r.ok());
        assertEquals("LAST_CHOICE", r.code());
    }

    @Test
    void deleteChoiceRemovesSimpleChoice() {
        DialogueDefinitionEditor.Result r = editor.deleteChoice("rpgquest:guard", "greeting", 1);
        assertTrue(r.ok(), r.message());
        assertEquals(1, reload("rpgquest:guard").nodes().get("greeting").choices().size());
    }

    @Test
    void unknownDialogueIsReported() {
        DialogueDefinitionEditor.Result r = editor.updateNode("rpgquest:ghost", "n", "s", "t");
        assertFalse(r.ok());
        assertEquals("NOT_FOUND", r.code());
    }

    @Test
    void alreadyBrokenSourceFileIsNeverRewritten() throws IOException {
        Files.writeString(dir.resolve("broken.yml"), "id: rpgquest:broken\nstart: x\n", StandardCharsets.UTF_8);
        DialogueDefinitionEditor.Result r = editor.updateNode("rpgquest:broken", "x", "s", "t");
        assertFalse(r.ok());
        assertEquals("SOURCE_INVALID", r.code());
        assertEquals("id: rpgquest:broken\nstart: x\n", Files.readString(dir.resolve("broken.yml")));
    }

    @Test
    void richDialogueRoundTripsWithoutLoss() {
        // Édition « neutre » d'un nœud simple : tout le reste du dialogue doit être identique.
        DialogueDefinition before = reload("rpgquest:guard");
        assertTrue(editor.updateNode("rpgquest:guard", "refused", "Garde", "<gray>Comme tu voudras.</gray>").ok());
        DialogueDefinition after = reload("rpgquest:guard");
        assertEquals(before.nodes().get("greeting"), after.nodes().get("greeting"));
        assertEquals(before.nodes().get("accepted"), after.nodes().get("accepted"));
        assertEquals(before.startNodeId(), after.startNodeId());
    }
}
