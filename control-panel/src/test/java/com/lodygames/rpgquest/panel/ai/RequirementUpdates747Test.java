package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #747 — les consignes de l'administrateur deviennent des exigences actualisées : relues dans le
 * journal du projet, dédoublonnées, closes par une modification de la description, et prioritaires
 * dans le texte envoyé à l'IA.
 */
class RequirementUpdates747Test {

    private final List<AiJournalStore.Event> events = new ArrayList<>();

    private void note(String text) {
        events.add(new AiJournalStore.Event(events.size() + 1, "building", "p", events.size() + 1,
                RequirementUpdates.KIND, "", "", "", 0, "", "", Instant.parse("2026-10-23T12:20:00Z"), -1, "owner",
                "", Map.of("notes", text)));
    }

    private void projectChanged(String briefBefore, String briefAfter) {
        events.add(new AiJournalStore.Event(events.size() + 1, "building", "p", events.size() + 1,
                "PROJECT_CHANGED", "", "", "", 0, "", "", Instant.now(), -1, "owner", "",
                Map.of("before", Map.of("briefSha256", briefBefore), "after", Map.of("briefSha256", briefAfter))));
    }

    @Test
    void cabinFurnitureRequestOutranksTheEmptyRoomOfTheBriefAndTheDesign() {
        note("Ajoute un lit rouge, un coffre, un établi, un four et deux torches murales.");
        RequirementUpdates u = RequirementUpdates.fromJournal(events);
        String block = u.promptBlock();
        assertTrue(block.contains("PRIMENT sur la description initiale ET sur la conception"), block);
        assertTrue(block.contains("« pièce vide »"), "l'exemple de contradiction est celui de la cabane");
        assertTrue(block.contains("(23/10 14:20) Ajoute un lit rouge"), block);
        assertTrue(u.text().contains("lit rouge"));
    }

    @Test
    void repeatedNotesAreKeptOnceAndInOrder() {
        note("Ajoute un lit.");
        note("Ajoute un four.");
        note("Ajoute un lit.");
        RequirementUpdates u = RequirementUpdates.fromJournal(events);
        assertEquals(List.of("Ajoute un four.", "Ajoute un lit."), u.updates().stream()
                .map(RequirementUpdates.Update::text).toList());
    }

    @Test
    void editingTheDescriptionClosesEarlierNotesButNotAConstraintChange() {
        note("Ajoute un lit.");
        projectChanged("aaa", "aaa");
        assertEquals(1, RequirementUpdates.fromJournal(events).updates().size(), "contraintes seules : notes gardées");
        projectChanged("aaa", "bbb");
        assertTrue(RequirementUpdates.fromJournal(events).isEmpty(), "la description consolidée les remplace");
        note("Ajoute un four.");
        assertEquals("Ajoute un four.", RequirementUpdates.fromJournal(events).text());
    }

    @Test
    void theRenderedListIsBounded() {
        for (int i = 0; i < 20; i++) {
            note("Consigne " + i + " " + "x".repeat(600));
        }
        RequirementUpdates u = RequirementUpdates.fromJournal(events);
        assertTrue(u.updates().size() <= RequirementUpdates.MAX_UPDATES);
        assertTrue(u.text().contains("Consigne 19"), "la plus récente est toujours gardée");
        assertFalse(u.text().contains("Consigne 0 "));
    }

    @Test
    void noNoteMeansNoBlock() {
        assertEquals("", RequirementUpdates.fromJournal(List.of()).promptBlock());
    }
}
