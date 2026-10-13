package com.lodygames.rpgquest.panel.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.content.DialogueYaml;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Issue #325 — identifiant libre et dialogue initial d'un PNJ créé depuis le panel. */
class NpcCreationPlannerTest {

    @Test
    void theIdFollowsThePluginRule() {
        assertEquals("bucheron_bob", NpcCreationPlanner.slugify("<green>Bûcheron Bob</green>"));
        assertEquals("", NpcCreationPlanner.slugify("!!!"));
    }

    @Test
    void aTakenIdGetsTheNextFreeSuffixWhateverItsPrefix() {
        assertEquals("lucien", NpcCreationPlanner.freeId("Lucien", Set.of("guard")).orElseThrow());
        assertEquals("lucien_2", NpcCreationPlanner.freeId("Lucien", Set.of("rpgquest:lucien")).orElseThrow());
        assertEquals("lucien_3", NpcCreationPlanner.freeId("Lucien", List.of("LUCIEN", "lucien_2")).orElseThrow());
        assertTrue(NpcCreationPlanner.freeId("???", Set.of()).isEmpty(), "un nom sans lettre ni chiffre");
    }

    @Test
    void aLongNameKeepsItsSuffixWithinTheLimit() {
        String name = "a".repeat(60);
        String id = NpcCreationPlanner.freeId(name, Set.of("a".repeat(48))).orElseThrow();
        assertTrue(id.endsWith("_2") && id.length() <= 48, id);
    }

    @Test
    void theInitialDialogueBelongsToThisNpcAndOffersNoQuest() {
        String yaml = NpcCreationPlanner.initialDialogueYaml("lucien", "Lucien");
        assertTrue(yaml.contains("id: rpgquest:lucien"));
        assertTrue(yaml.contains("Salut !") && yaml.contains("Au revoir"));
        assertFalse(yaml.contains("START_QUEST"));
        assertTrue(DialogueYaml.roundTripProblems(yaml).isEmpty(), "relisible par l'éditeur");
        assertEquals("rpgquest:lucien", NpcCreationPlanner.dialogueRef("lucien"));
    }
}
