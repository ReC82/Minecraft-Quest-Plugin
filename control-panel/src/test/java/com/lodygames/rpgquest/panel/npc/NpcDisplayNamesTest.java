package com.lodygames.rpgquest.panel.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #125 — noms affichés partagés entre PNJ d'identifiants distincts. */
class NpcDisplayNamesTest {

    private static NpcDisplayNames names(String... idThenName) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < idThenName.length; i += 2) {
            m.put(idThenName[i], idThenName[i + 1]);
        }
        return NpcDisplayNames.of(m);
    }

    @Test
    void twoNpcsWithDifferentIdsButTheSameNameAreDetected() {
        NpcDisplayNames n = names("robert_writer", "Robert", "iron_specialist", "Robert", "guard", "Garde");

        assertEquals(1, n.duplicates().size());
        NpcDisplayNames.Group g = n.duplicates().get(0);
        assertEquals("Robert", g.name());
        assertEquals(List.of("robert_writer", "iron_specialist"), g.ids());
        assertEquals(List.of("iron_specialist"), n.othersSharing("robert_writer"));
        assertEquals(List.of("robert_writer"), n.othersSharing("iron_specialist"));
        assertTrue(n.othersSharing("guard").isEmpty(), "un nom unique n'est pas signalé");
    }

    @Test
    void namesAreComparedAsTheyReadOnScreen() {
        NpcDisplayNames n = names("a", "<yellow>Robert</yellow>", "b", "  robert ", "c", "Robért", "d", "Rob  ert");

        assertEquals(List.of("a", "b"), n.duplicates().get(0).ids(), "balises, casse et espaces ignorés");
        assertEquals("Robert", n.duplicates().get(0).name(), "nom visible, sans balises");
        assertEquals(1, n.duplicates().size(), "les accents et les espaces internes distinguent");
    }

    @Test
    void emptyNamesNeverCollide() {
        NpcDisplayNames n = names("a", "", "b", "", "c", "<red></red>");
        assertTrue(n.duplicates().isEmpty());
    }

    @Test
    void theFormQuestionExcludesTheNpcBeingRenamed() {
        NpcDisplayNames n = names("robert_writer", "Robert", "guard", "Garde");

        assertEquals(List.of("robert_writer"), n.holdersOf("ROBERT", ""), "création : le nom est pris");
        assertTrue(n.holdersOf("Robert", "robert_writer").isEmpty(), "renommage : son propre nom ne compte pas");
        assertTrue(n.holdersOf("Lucien", "").isEmpty());
    }

    @Test
    void theSurveyKeepsOnlyRealCharactersAndFallsBackToTheInGameName() {
        List<Object> npcs = List.of(
                Map.of("id", "robert_writer", "displayName", "Robert", "logicalDefinitionPresent", true),
                // Rattaché à Citizens sans nom de fiche : son nom est celui du jeu.
                Map.of("id", "iron_specialist", "displayName", "null", "citizensBindingPresent", true,
                        "citizensNumericId", 7),
                // Seulement cité par une quête : pas un personnage, pas de nom à partager.
                Map.of("id", "robert_ghost", "displayName", "Robert"));
        List<Object> roster = List.of(Map.of("numericId", 7, "name", "Robert"));

        NpcDisplayNames n = NpcDisplayNames.fromSurvey(npcs, roster);

        assertEquals(List.of("robert_writer", "iron_specialist"), n.duplicates().get(0).ids());
    }

    @Test
    void aRealIdCollisionStaysTheJobOfFreeIdAndIsNeverMerged() {
        // Le même identifiant deux fois n'est pas un « nom en double » : c'est une collision d'ID,
        // que le moteur signale (DUPLICATE_DEFINITION) et que la création évite par un suffixe.
        NpcDisplayNames n = names("lucien", "Lucien", "LUCIEN", "Lucien");
        assertTrue(n.duplicates().isEmpty());
        assertEquals("lucien_2", NpcCreationPlanner.freeId("Lucien", List.of("lucien")).orElseThrow());
    }
}
