package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #248 — le relevé serveur des dialogues, et les trois états qu'il ne faut pas confondre. */
class RuntimeDialogueInventoryTest {

    static Map<String, Object> dialogue(String id, List<String> npcs, List<String> starts,
                                        String openTarget) {
        List<Object> actions = openTarget == null ? List.of()
                : List.of(Map.of("kind", "OPEN_DIALOGUE", "target", openTarget));
        return Map.of("id", id, "key", id.substring(id.indexOf(':') + 1), "linkedNpcIds", npcs,
                "startsQuestIds", starts, "referencedQuestIds", starts,
                "nodes", List.of(Map.of("id", "start", "choices", List.of(Map.of("actions", actions)))));
    }

    static Map<String, Object> survey(Map<String, Object>... dialogues) {
        return Map.of("dialogues", List.of(dialogues));
    }

    @Test
    void absentStaleAndUsableAreThreeDifferentStates() {
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        assertFalse(RuntimeDialogueInventory.unavailable().available());
        assertFalse(RuntimeDialogueInventory.unavailable().usable(now));

        var stale = RuntimeDialogueInventory.from(survey(), now.minus(Duration.ofMinutes(16)));
        assertTrue(stale.available());
        assertFalse(stale.usable(now), "un relevé de plus de 15 min ne fait plus foi");

        var fresh = RuntimeDialogueInventory.from(survey(), now.minus(Duration.ofMinutes(2)));
        assertTrue(fresh.usable(now));

        assertFalse(RuntimeDialogueInventory.from(survey(), null).usable(now),
                "une date inconnue n'est pas une date récente");
    }

    @Test
    void theRealHelpCaseIsReadCompletely() {
        var inv = RuntimeDialogueInventory.from(survey(
                dialogue("rpgquest:help", List.of(), List.of("rpgquest:help_recolte"), null),
                dialogue("rpgquest:guide", List.of("guide"), List.of(), "rpgquest:help")),
                Instant.now());

        var help = inv.find("HELP").orElseThrow();
        assertTrue(help.linkedNpcIds().isEmpty());
        assertEquals(List.of("help_recolte"), help.startsQuestIds(), "namespace retiré pour comparer");
        assertEquals(1, inv.openersOf("rpgquest:help").size());
        assertTrue(inv.openersOf("help").get(0).contains("rpgquest:guide"));
        assertEquals(List.of("guide"), inv.openerKeys("help"));
        assertEquals("help", inv.referencingQuest("rpgquest:help_recolte").get(0).key());
        assertTrue(inv.openersOf("guide").isEmpty());
    }

    @Test
    void aMalformedSurveyIsUnavailableNotEmpty() {
        assertFalse(RuntimeDialogueInventory.from(Map.of("autre", 1), Instant.now()).available());
        assertFalse(RuntimeDialogueInventory.from(null, Instant.now()).available());
    }
}
