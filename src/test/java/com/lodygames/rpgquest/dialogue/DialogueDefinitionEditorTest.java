package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.ActionType;
import com.lodygames.rpgquest.dialogue.model.CloseAction;
import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.GiveItemAction;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.StartQuestAction;
import com.lodygames.rpgquest.dialogue.model.TurnInQuestAction;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Édition guidée d'un dialogue : round-trip fidèle (actions/conditions riches conservées), édition
 * structurée de l'action de quête et de la condition d'état, écriture atomique, restauration en cas
 * de rechargement invalide, suppression toujours réservée aux choix sans effet de jeu.
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
              rich:
                speaker: "Garde"
                text: "<white>Tiens, prends ça.</white>"
                choices:
                  - text: "Merci"
                    conditions:
                      - type: HAS_PERMISSION
                        permission: "rpgquest.vip"
                    actions:
                      - type: GIVE_ITEM
                        material: BREAD
                        amount: 3
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
        assertEquals(4, d.nodes().size());
    }

    @Test
    void createNodeAddsAnOrphanNodeWithACloseChoice() {
        DialogueDefinitionEditor.Result r = editor.createNode("rpgquest:guard", "farewell", "Garde",
                "<gray>À bientôt.</gray>", "Fermer");
        assertTrue(r.ok(), r.message());

        DialogueDefinition d = reload("rpgquest:guard");
        assertEquals(5, d.nodes().size());
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
    void updateChoiceEditsTextOfARichChoiceWithoutTouchingItsConditionsOrActions() {
        // greeting choix #0 porte une condition QUEST_STATE + une action START_QUEST.
        DialogueChoice before = reload("rpgquest:guard").nodes().get("greeting").choices().get(0);

        DialogueDefinitionEditor.Result r = editor.updateChoice("rpgquest:guard", "greeting", 0,
                "<gray>Je vais m'en charger.</gray>", "accepted", false);
        assertTrue(r.ok(), r.message());

        DialogueChoice after = reload("rpgquest:guard").nodes().get("greeting").choices().get(0);
        assertEquals("<gray>Je vais m'en charger.</gray>", after.text().base());
        assertEquals("accepted", after.next());
        assertEquals(before.conditions(), after.conditions(), "conditions intactes");
        assertEquals(before.actions(), after.actions(), "actions intactes");
    }

    @Test
    void updateChoiceKeepsEveryOtherPropertyUntouchedByDefault() {
        // Un choix qui porte aussi une action non gérée par l'éditeur : elle traverse l'édition.
        DialogueDefinition before = reload("rpgquest:guard");
        assertTrue(editor.updateChoice("rpgquest:guard", "rich", 0, "Texte neuf", null, true).ok());
        DialogueChoice after = reload("rpgquest:guard").nodes().get("rich").choices().get(0);
        assertEquals("Texte neuf", after.text().base());
        assertEquals(before.nodes().get("rich").choices().get(0).conditions(), after.conditions());
        // GIVE_ITEM conservé, CLOSE toujours présent (le choix fermait déjà le dialogue).
        assertTrue(after.actions().stream().anyMatch(a -> a instanceof GiveItemAction));
        assertTrue(after.actions().stream().anyMatch(a -> a instanceof CloseAction));
    }

    @Test
    void updateChoiceSetsTheQuestActionInPlaceAndKeepsTheRest() {
        DialogueDefinitionEditor.Result r = editor.updateChoice("rpgquest:guard", "greeting", 0,
                "J'accepte", "accepted", false,
                DialogueDefinitionEditor.QuestActionEdit.set(ActionType.TURN_IN_QUEST, "rpgquest:crystal_hunt"),
                DialogueDefinitionEditor.QuestConditionEdit.keep());
        assertTrue(r.ok(), r.message());

        DialogueChoice after = reload("rpgquest:guard").nodes().get("greeting").choices().get(0);
        assertEquals(1, after.actions().size());
        assertTrue(after.actions().get(0) instanceof TurnInQuestAction t
                && t.questId().toString().equals("rpgquest:crystal_hunt"));
        // La condition QUEST_STATE n'était pas visée : elle n'a pas bougé.
        assertEquals(1, after.conditions().size());
        assertTrue(after.conditions().get(0) instanceof QuestStateCondition c
                && c.state() == QuestState.NOT_STARTED);
    }

    @Test
    void updateChoiceRemovesTheQuestActionOnDemandOnly() {
        assertTrue(editor.updateChoice("rpgquest:guard", "greeting", 0, "J'accepte", "accepted", false,
                DialogueDefinitionEditor.QuestActionEdit.remove(),
                DialogueDefinitionEditor.QuestConditionEdit.keep()).ok());
        DialogueChoice after = reload("rpgquest:guard").nodes().get("greeting").choices().get(0);
        assertTrue(after.actions().isEmpty());
        assertEquals(1, after.conditions().size(), "la condition reste");
    }

    @Test
    void updateChoiceEditsTheQuestStateConditionIncludingNegation() {
        assertTrue(editor.updateChoice("rpgquest:guard", "greeting", 0, "J'accepte", "accepted", false,
                DialogueDefinitionEditor.QuestActionEdit.keep(),
                DialogueDefinitionEditor.QuestConditionEdit.set(QuestState.COMPLETED, "rpgquest:first_steps", true)).ok());

        DialogueChoice after = reload("rpgquest:guard").nodes().get("greeting").choices().get(0);
        assertEquals(1, after.conditions().size());
        assertTrue(after.conditions().get(0) instanceof NegatedCondition n
                && n.inner() instanceof QuestStateCondition c && c.state() == QuestState.COMPLETED);
        assertEquals(1, after.actions().size(), "l'action START_QUEST reste");
    }

    @Test
    void updateChoiceRejectsAStructuredEditWithoutQuestId() {
        DialogueDefinitionEditor.Result r = editor.updateChoice("rpgquest:guard", "greeting", 0,
                "J'accepte", "accepted", false,
                DialogueDefinitionEditor.QuestActionEdit.set(ActionType.START_QUEST, "  "),
                DialogueDefinitionEditor.QuestConditionEdit.keep());
        assertFalse(r.ok());
        assertEquals("INVALID", r.code());
        assertEquals("J'accepte", reload("rpgquest:guard").nodes().get("greeting").choices().get(0).text().base());
    }

    @Test
    void updateChoiceTogglingCloseKeepsTheQuestAction() {
        assertTrue(editor.updateChoice("rpgquest:guard", "greeting", 0, "J'accepte", null, true).ok());
        DialogueChoice after = reload("rpgquest:guard").nodes().get("greeting").choices().get(0);
        assertEquals(null, after.next());
        assertTrue(after.actions().stream().anyMatch(a -> a instanceof StartQuestAction));
        assertTrue(after.actions().stream().anyMatch(a -> a instanceof CloseAction));
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
    void deleteChoiceStillRefusesAChoiceCarryingActionsOrConditions() {
        // Supprimer effacerait aussi le START_QUEST : le geste doit rester explicite (retirer d'abord).
        DialogueDefinitionEditor.Result r = editor.deleteChoice("rpgquest:guard", "greeting", 0);
        assertFalse(r.ok());
        assertEquals("UNSAFE_CHOICE", r.code());
        assertEquals(2, reload("rpgquest:guard").nodes().get("greeting").choices().size());
    }

    @Test
    void localizedNodeTextSurvivesAnEditOfTheSameDialogue() throws IOException {
        Files.writeString(dir.resolve("multi.yml"), """
                id: rpgquest:multi
                start: start
                nodes:
                  start:
                    speaker: "Lily"
                    text:
                      default: "<gray>Bonjour.</gray>"
                      en: "<gray>Hello.</gray>"
                    choices:
                      - text: "Au revoir"
                        actions:
                          - type: CLOSE
                  other:
                    speaker: "Lily"
                    text: "<gray>Encore toi.</gray>"
                    choices:
                      - text: "Au revoir"
                        actions:
                          - type: CLOSE
                """, StandardCharsets.UTF_8);
        // Une édition ailleurs réécrit tout le fichier : la table de traductions doit survivre.
        assertTrue(editor.updateNode("rpgquest:multi", "other", "Lily", "<gray>Te revoilà.</gray>").ok());

        DialogueDefinition d = reload("rpgquest:multi");
        assertEquals("<gray>Hello.</gray>", d.nodes().get("start").text().forLocale("en"));
        assertEquals("<gray>Bonjour.</gray>", d.nodes().get("start").text().base());
    }

    @Test
    void deleteChoiceRemovesSimpleChoice() {
        DialogueDefinitionEditor.Result r = editor.deleteChoice("rpgquest:guard", "greeting", 1);
        assertTrue(r.ok(), r.message());
        assertEquals(1, reload("rpgquest:guard").nodes().get("greeting").choices().size());
    }

    /**
     * Schéma canonique partagé : ce texte est <strong>exactement</strong> celui qu'émet le Control
     * Panel ({@code DialogueYaml.write}, cf. {@code DialogueYamlTest.RICH}). Le moteur doit le
     * charger, et le réécrire à l'octet près — sinon les deux écrivains ont divergé.
     */
    private static final String CANONICAL_RICH = """
            id: rpgquest:jeff
            start: start
            nodes:
              start:
                speaker: "Jeff"
                text: "<gray>Compris.</gray>"
                choices:
                  - text: "Je vais m'en charger"
                    conditions:
                      - type: QUEST_STATE
                        quest: rpgquest:cleanup
                        state: NOT_STARTED
                    actions:
                      - type: START_QUEST
                        quest: rpgquest:cleanup
                    next: accepted
                  - text: "Pas maintenant"
                    actions:
                      - type: CLOSE
              accepted:
                speaker: "Jeff"
                text: "<green>Merci.</green>"
                choices:
                  - text: "OK"
                    actions:
                      - type: CLOSE
            """;

    @Test
    void canonicalPanelFormatLoadsAndIsRewrittenIdentically() throws IOException {
        Files.writeString(dir.resolve("jeff.yml"), CANONICAL_RICH, StandardCharsets.UTF_8);
        DialogueDefinition d = reload("rpgquest:jeff");

        String rendered = DialogueDefinitionWriter.render(d, List.of("start", "accepted"));
        assertTrue(rendered.endsWith(CANONICAL_RICH),
                () -> "le moteur et le panel n'écrivent plus la même chose :\n" + rendered);
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
