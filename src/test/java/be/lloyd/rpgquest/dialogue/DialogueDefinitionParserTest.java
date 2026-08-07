package be.lloyd.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.dialogue.model.DialogueDefinition;
import be.lloyd.rpgquest.dialogue.model.OpenDialogueAction;
import java.io.StringReader;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DialogueDefinitionParserTest {

    private final DialogueDefinitionParser parser = new DialogueDefinitionParser(Set.of("give", "xp"));

    @Test
    void validFileParsesSuccessfully() {
        DialogueDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: rpgquest:guard
                start: greeting

                nodes:
                  greeting:
                    speaker: "Garde"
                    text: "Bienvenue."
                    choices:
                      - text: "Accepter"
                        conditions:
                          - type: QUEST_STATE
                            quest: rpgquest:first_steps
                            state: NOT_STARTED
                        actions:
                          - type: START_QUEST
                            quest: rpgquest:first_steps
                        next: accepted
                      - text: "Refuser"
                        actions:
                          - type: CLOSE
                  accepted:
                    speaker: "Garde"
                    text: "Merci."
                    choices:
                      - text: "D'accord"
                        actions:
                          - type: CLOSE
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        DialogueDefinition dialogue = result.dialogue();
        assertEquals("rpgquest:guard", dialogue.id().toString());
        assertEquals("greeting", dialogue.startNodeId());
        assertEquals(2, dialogue.nodes().size());
        assertEquals(2, dialogue.nodes().get("greeting").choices().size());
    }

    @Test
    void missingRequiredFieldsAreAllReportedTogether() {
        DialogueDefinitionParser.ParseResult result = parser.parse("incomplete.yml", load("start: greeting\n"));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(DialogueLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("nodes"), combined);
    }

    @Test
    void unknownConditionTypeIsRejected() {
        DialogueDefinitionParser.ParseResult result = parser.parse("bad-condition.yml", load(minimalDialogueWithChoice("""
                      - text: "Choix"
                        conditions:
                          - type: NOT_A_REAL_CONDITION
                        actions:
                          - type: CLOSE
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("type de condition inconnu")));
    }

    @Test
    void unknownActionTypeIsRejected() {
        DialogueDefinitionParser.ParseResult result = parser.parse("bad-action.yml", load(minimalDialogueWithChoice("""
                      - text: "Choix"
                        actions:
                          - type: NOT_A_REAL_ACTION
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("type d'action inconnu")));
    }

    @Test
    void runSafeCommandNotOnWhitelistIsRejected() {
        DialogueDefinitionParser.ParseResult result = parser.parse("unsafe.yml", load(minimalDialogueWithChoice("""
                      - text: "Choix"
                        actions:
                          - type: RUN_SAFE_COMMAND
                            command: "op %player%"
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("commande non autorisée")));
    }

    @Test
    void runSafeCommandOnWhitelistIsAccepted() {
        DialogueDefinitionParser.ParseResult result = parser.parse("safe.yml", load(minimalDialogueWithChoice("""
                      - text: "Choix"
                        actions:
                          - type: RUN_SAFE_COMMAND
                            command: "give %player% diamond 1"
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
    }

    @Test
    void openDialogueActionIsParsed() {
        DialogueDefinitionParser.ParseResult result = parser.parse("open-other.yml", load(minimalDialogueWithChoice("""
                      - text: "Choix"
                        actions:
                          - type: OPEN_DIALOGUE
                            dialogue: rpgquest:other
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        var action = result.dialogue().nodes().get("greeting").choices().get(0).actions().get(0);
        assertTrue(action instanceof OpenDialogueAction);
        assertEquals("rpgquest:other", ((OpenDialogueAction) action).dialogueId().toString());
    }

    @Test
    void nextReferencingUnknownNodeIsRejected() {
        DialogueDefinitionParser.ParseResult result = parser.parse("bad-next.yml", load(minimalDialogueWithChoice("""
                      - text: "Choix"
                        next: nowhere
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("nœud inexistant")));
    }

    @Test
    void nodeWithoutChoicesIsRejected() {
        DialogueDefinitionParser.ParseResult result = parser.parse("no-choices.yml", load("""
                id: rpgquest:empty
                start: greeting
                nodes:
                  greeting:
                    speaker: "Garde"
                    text: "..."
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("choices")));
    }

    private String minimalDialogueWithChoice(String choicesYaml) {
        return """
                id: rpgquest:test
                start: greeting
                nodes:
                  greeting:
                    speaker: "Garde"
                    text: "Bienvenue."
                    choices:
                """ + choicesYaml;
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
