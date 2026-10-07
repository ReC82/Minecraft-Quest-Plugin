package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DeliverQuestItemsAction;
import com.lodygames.rpgquest.dialogue.model.GiveStarterKitAction;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.OpenDialogueAction;
import com.lodygames.rpgquest.dialogue.model.PendingDeliveryCondition;
import com.lodygames.rpgquest.dialogue.model.OpenMerchantAction;
import com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition;
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
    void negateTrueWrapsAnyConditionInANegatedCondition() {
        DialogueDefinitionParser.ParseResult result = parser.parse("negate.yml", load(minimalDialogueWithChoice("""
                      - text: "Comment débloquer ?"
                        conditions:
                          - type: VARIABLE_EQUALS
                            key: CLAIM_TIER_1
                            value: "true"
                            negate: true
                        actions:
                          - type: CLOSE
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        DialogueCondition condition = result.dialogue().nodes().get("greeting").choices().get(0).conditions().get(0);
        assertTrue(condition instanceof NegatedCondition, "negate: true doit produire une NegatedCondition");
        DialogueCondition inner = ((NegatedCondition) condition).inner();
        assertTrue(inner instanceof VariableEqualsCondition);
        assertEquals("CLAIM_TIER_1", ((VariableEqualsCondition) inner).key());
    }

    @Test
    void negateFalseOrAbsentLeavesTheConditionUnwrapped() {
        DialogueDefinitionParser.ParseResult result = parser.parse("no-negate.yml", load(minimalDialogueWithChoice("""
                      - text: "Choix"
                        conditions:
                          - type: VARIABLE_EQUALS
                            key: CLAIM_TIER_1
                            value: "true"
                        actions:
                          - type: CLOSE
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        DialogueCondition condition = result.dialogue().nodes().get("greeting").choices().get(0).conditions().get(0);
        assertTrue(condition instanceof VariableEqualsCondition, "sans negate, la condition reste telle quelle");
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
    void openMerchantActionIsParsed() {
        DialogueDefinitionParser.ParseResult result = parser.parse("open-merchant.yml", load(minimalDialogueWithChoice("""
                      - text: "Choix"
                        actions:
                          - type: OPEN_MERCHANT
                            merchant: rpgquest:village_merchant
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        var action = result.dialogue().nodes().get("greeting").choices().get(0).actions().get(0);
        assertTrue(action instanceof OpenMerchantAction);
        assertEquals("rpgquest:village_merchant", ((OpenMerchantAction) action).merchantId().toString());
    }

    @Test
    void openMerchantActionWithoutMerchantIdIsRejected() {
        DialogueDefinitionParser.ParseResult result = parser.parse("open-merchant-bad.yml", load(minimalDialogueWithChoice("""
                      - text: "Choix"
                        actions:
                          - type: OPEN_MERCHANT
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("merchant")));
    }

    @Test
    void giveStarterKitActionIsParsed() {
        DialogueDefinitionParser.ParseResult result = parser.parse("give-starter-kit.yml", load(minimalDialogueWithChoice("""
                      - text: "Demander mon kit de départ"
                        actions:
                          - type: GIVE_STARTER_KIT
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        var action = result.dialogue().nodes().get("greeting").choices().get(0).actions().get(0);
        assertTrue(action instanceof GiveStarterKitAction);
    }

    // ---- Remise d'objets à un PNJ (issue #123) -------------------------------------------------

    @Test
    void deliverQuestItemsActionIsParsedWithoutAnyNpcByDefault() {
        DialogueDefinitionParser.ParseResult result = parser.parse("deliver.yml", load(minimalDialogueWithChoice("""
                      - text: "Donner les matériaux que j'ai"
                        actions:
                          - type: DELIVER_QUEST_ITEMS
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        var action = result.dialogue().nodes().get("greeting").choices().get(0).actions().get(0);
        assertTrue(action instanceof DeliverQuestItemsAction);
        assertNull(((DeliverQuestItemsAction) action).npcId(),
                "sans « npc », le destinataire est déduit du dialogue — jamais figé au chargement");
    }

    @Test
    void deliverQuestItemsActionKeepsAnExplicitNpc() {
        DialogueDefinitionParser.ParseResult result = parser.parse("deliver-npc.yml", load(minimalDialogueWithChoice("""
                      - text: "Donner"
                        actions:
                          - type: DELIVER_QUEST_ITEMS
                            npc: blacksmith
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        var action = result.dialogue().nodes().get("greeting").choices().get(0).actions().get(0);
        assertEquals("blacksmith", ((DeliverQuestItemsAction) action).npcId());
    }

    @Test
    void anEmptyNpcOnADeliverActionIsTreatedAsNotProvided() {
        DialogueDefinitionParser.ParseResult result = parser.parse("deliver-blank.yml", load(minimalDialogueWithChoice("""
                      - text: "Donner"
                        actions:
                          - type: DELIVER_QUEST_ITEMS
                            npc: "  "
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        var action = result.dialogue().nodes().get("greeting").choices().get(0).actions().get(0);
        assertNull(((DeliverQuestItemsAction) action).npcId(),
                "un id vide ne doit jamais viser un PNJ nommé « » ");
    }

    @Test
    void pendingDeliveryConditionIsParsedWithAndWithoutNpc() {
        DialogueDefinitionParser.ParseResult implicit = parser.parse("pending.yml", load(minimalDialogueWithChoice("""
                      - text: "J'ai des matériaux"
                        conditions:
                          - type: HAS_PENDING_DELIVERY
                """)));
        assertTrue(implicit.isSuccess(), () -> "issues: " + implicit.issues());
        var condition = implicit.dialogue().nodes().get("greeting").choices().get(0).conditions().get(0);
        assertTrue(condition instanceof PendingDeliveryCondition);
        assertNull(((PendingDeliveryCondition) condition).npcId());

        DialogueDefinitionParser.ParseResult explicit = parser.parse("pending-npc.yml", load(minimalDialogueWithChoice("""
                      - text: "J'ai des matériaux"
                        conditions:
                          - type: HAS_PENDING_DELIVERY
                            npc: blacksmith
                """)));
        assertTrue(explicit.isSuccess(), () -> "issues: " + explicit.issues());
        assertEquals("blacksmith", ((PendingDeliveryCondition) explicit.dialogue().nodes()
                .get("greeting").choices().get(0).conditions().get(0)).npcId());
    }

    /** {@code negate: true} doit fonctionner comme sur n'importe quelle autre condition. */
    @Test
    void aNegatedPendingDeliveryConditionIsParsed() {
        DialogueDefinitionParser.ParseResult result = parser.parse("pending-negate.yml", load(minimalDialogueWithChoice("""
                      - text: "Tout est remis"
                        conditions:
                          - type: HAS_PENDING_DELIVERY
                            negate: true
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        var condition = result.dialogue().nodes().get("greeting").choices().get(0).conditions().get(0);
        assertTrue(condition instanceof NegatedCondition negated
                && negated.inner() instanceof PendingDeliveryCondition);
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
