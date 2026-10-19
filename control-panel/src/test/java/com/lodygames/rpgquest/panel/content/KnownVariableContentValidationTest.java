package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #320 — une action {@code SET_VARIABLE} ou une récompense {@code VARIABLE} qui poserait une
 * valeur hors type sur une variable connue (CLAIM_TIER_1 = lasagne) est refusée avant publication ;
 * une variable narrative libre reste libre.
 */
class KnownVariableContentValidationTest {

    private static List<Diagnostic> dialogueErrors(String key, String value) {
        DialogueYaml.ReadResult read = DialogueYaml.read("""
                id: tc320_pnj
                start: accueil
                nodes:
                  accueil:
                    speaker: "Un PNJ"
                    text: "Bonjour."
                    choices:
                      - text: "Oui"
                        actions:
                          - type: SET_VARIABLE
                            key: %s
                            value: "%s"
                """.formatted(key, value));
        assertTrue(read.problems().isEmpty(), "relecture : " + read.problems());
        return DialogueValidator.validate(read.draft()).stream()
                .filter(d -> d.level() == Diagnostic.Level.ERROR).toList();
    }

    private static List<Diagnostic> questErrors(String key, String value) {
        QuestDraft d = new QuestDraft();
        d.id = "tc320_quest";
        d.title = "Titre";
        d.description = "Desc";
        d.category = "side";
        d.icon = "STRING";
        QuestDraft.Step step = new QuestDraft.Step("talk");
        Map<String, String> objective = new LinkedHashMap<>();
        objective.put("kind", "KILL_ENTITY");
        objective.put("entity", "SPIDER");
        objective.put("amount", "1");
        step.objectives.add(objective);
        d.steps.add(step);
        Map<String, String> reward = new LinkedHashMap<>();
        reward.put("kind", "VARIABLE");
        reward.put("key", key);
        reward.put("value", value);
        d.rewards.add(reward);
        return QuestValidator.validate(d, RefData.empty()).stream()
                .filter(x -> x.level() == Diagnostic.Level.ERROR).toList();
    }

    @Test
    void aDialogueCannotSetAKnownBooleanToLasagne() {
        assertTrue(dialogueErrors("CLAIM_TIER_1", "lasagne").stream()
                .anyMatch(e -> e.message().contains("true ou false")));
        assertTrue(dialogueErrors("CLAIM_TIER_1", "true").isEmpty());
        assertTrue(dialogueErrors("met_lily", "lasagne").isEmpty(), "variable libre");
    }

    @Test
    void aQuestRewardCannotSetAKnownBooleanToLasagne() {
        assertFalse(questErrors("CLAIM_TIER_1", "lasagne").isEmpty());
        assertTrue(questErrors("CLAIM_TIER_1", "false").isEmpty(), () -> questErrors("CLAIM_TIER_1", "false").toString());
        assertTrue(questErrors("woodcutter_reputation", "lasagne").isEmpty(), "variable libre");
    }
}
