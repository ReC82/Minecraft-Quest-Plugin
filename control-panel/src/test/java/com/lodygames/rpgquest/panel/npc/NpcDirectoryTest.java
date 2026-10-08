package com.lodygames.rpgquest.panel.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issues #225 / #226 — l'annuaire PNJ, sur <strong>les données réelles du cas Mira</strong>.
 *
 * <p>Les deux lignes ci-dessous sont recopiées du relevé {@code npc.list} du serveur du
 * 2026-10-08, celui-là même qui a produit les deux entrées « Mira » de la capture du ticket. Les
 * tests portent donc sur ce que le serveur dit vraiment, pas sur ce qu'on imagine qu'il dit.</p>
 */
class NpcDirectoryTest {

    /** {@code mira_cartographer} : vraie définition, Citizens #9, dialogue déclaré différent. */
    private static Map<String, Object> miraCartographer() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "mira_cartographer");
        m.put("displayName", "Mira la Cartographe");
        m.put("logicalDefinitionPresent", true);
        m.put("citizensBindingPresent", true);
        m.put("citizensNumericId", 9);
        m.put("bindingCount", 1);
        m.put("enabled", true);
        m.put("definedDialogueId", "rpgquest:mira_first_map");
        m.put("hasDialogue", false);
        m.put("dialogueId", null);
        m.put("dialogueNodes", 0);
        m.put("dialogueChoices", 0);
        m.put("dialogueStartsQuests", List.of());
        m.put("questsGiven", List.of());
        m.put("questsReferenced", List.of());
        m.put("sources", List.of("DEFINITION", "BINDING"));
        m.put("state", "LINKED");
        m.put("warnings", List.of());
        return m;
    }

    /**
     * {@code mira_first_map} : l'entrée fantôme. Elle n'a aucune définition, aucun binding, et sa
     * seule source est {@code DIALOGUE} — c'est le dialogue lui-même, que le catalogue du moteur
     * déduit en PNJ parce qu'il suppose {@code dialogueId == npcId}.
     */
    private static Map<String, Object> miraFirstMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "mira_first_map");
        m.put("displayName", "Mira");
        m.put("logicalDefinitionPresent", false);
        m.put("citizensBindingPresent", false);
        m.put("citizensNumericId", null);
        m.put("bindingCount", 0);
        m.put("enabled", true);
        m.put("definedDialogueId", null);
        m.put("hasDialogue", true);
        m.put("dialogueId", "rpgquest:mira_first_map");
        m.put("dialogueNodes", 2);
        m.put("dialogueChoices", 3);
        m.put("dialogueStartsQuests", List.of());
        m.put("questsGiven", List.of());
        m.put("questsReferenced", List.of());
        m.put("sources", List.of("DIALOGUE"));
        m.put("state", "UNDEFINED_REFERENCE");
        m.put("warnings", List.of(Map.of("code", "NO_DEFINITION", "severity", "error",
                "message", "Aucune définition logique RPGQuest pour « mira_first_map » "
                        + "(référencé par dialogue). À migrer : créer la définition.")));
        return m;
    }

    private static NpcDirectory realMiraCase() {
        List<Object> rows = new ArrayList<>();
        rows.add(miraFirstMap());
        rows.add(miraCartographer());
        return NpcDirectory.from(Map.of("npcs", rows));
    }

    // ---- Projection ----------------------------------------------------------------------------

    @Test
    void theRealSurveyProjectsBothMiraEntries() {
        NpcDirectory dir = realMiraCase();

        assertTrue(dir.available());
        assertEquals(2, dir.npcs().size());
        assertEquals(1, dir.defined().size());
        assertEquals(1, dir.orphans().size());
        assertEquals("mira_cartographer", dir.defined().get(0).id());
        assertEquals("mira_first_map", dir.orphans().get(0).id());
    }

    /**
     * Le cœur du ticket : la définition déclare un dialogue qui ne porte pas son nom, et le panel
     * doit le dire — pas déduire {@code rpgquest:mira_cartographer}.
     */
    @Test
    void miraCartographerIsLinkedToTheDialogueSheDeclares() {
        NpcView mira = realMiraCase().find("mira_cartographer").orElseThrow();

        assertTrue(mira.hasLinkedDialogue());
        assertEquals("rpgquest:mira_first_map", mira.linkedDialogueId());
        assertEquals("mira_first_map", mira.linkedDialogueKey());
        assertTrue(mira.dialogueNamedDifferently(),
                "c'est précisément le cas où imposer dialogueId = npcId crée un doublon");
        assertEquals(Integer.valueOf(9), mira.citizensNumericId());
        assertFalse(mira.orphan());
    }

    /** Le dialogue réellement chargé l'emporte sur celui que la définition déclare. */
    @Test
    void aLoadedDialogueWinsOverTheDeclaredOne() {
        Map<String, Object> m = miraCartographer();
        m.put("dialogueId", "rpgquest:mira_first_map");
        m.put("dialogueNodes", 2);
        NpcView mira = NpcDirectory.from(Map.of("npcs", List.of(m))).find("mira_cartographer")
                .orElseThrow();

        assertEquals("rpgquest:mira_first_map", mira.linkedDialogueId());
        assertEquals(2, mira.dialogueNodes());
    }

    // ---- Provenance ----------------------------------------------------------------------------

    /**
     * L'exigence explicite du ticket : l'entrée « Mira / mira_first_map / sans définition » doit
     * avoir une provenance <em>dite</em>, pas une anomalie mystérieuse.
     */
    @Test
    void theGhostEntryExplainsThatItComesFromTheDialogue() {
        NpcView ghost = realMiraCase().find("mira_first_map").orElseThrow();

        assertTrue(ghost.orphan());
        String why = ghost.provenance();
        assertTrue(why.contains("dialogue"), why);
        assertTrue(why.contains("rpgquest:mira_first_map"), why);
        assertTrue(why.contains("même nom") || why.contains("porteur"), why);
    }

    @Test
    void aDefinedNpcExplainsItsFileAndItsCitizens() {
        String why = realMiraCase().find("mira_cartographer").orElseThrow().provenance();

        assertTrue(why.contains("npcs/mira_cartographer.yml"), why);
        assertTrue(why.contains("#9"), why);
    }

    @Test
    void anOrphanCreatedByAQuestReferenceSaysWhichQuest() {
        Map<String, Object> m = miraFirstMap();
        m.put("sources", List.of("QUEST_GIVER"));
        m.put("questsGiven", List.of("rpgquest:crystal_hunt"));
        m.put("dialogueId", null);
        m.put("hasDialogue", false);

        String why = NpcDirectory.from(Map.of("npcs", List.of(m))).find("mira_first_map")
                .orElseThrow().provenance();

        assertTrue(why.contains("donneur"), why);
        assertTrue(why.contains("rpgquest:crystal_hunt"), why);
    }

    // ---- Entrées apparentées -------------------------------------------------------------------

    /** Les deux Mira doivent se reconnaître : c'est ce qui rend le nettoyage explicable. */
    @Test
    void theTwoMiraEntriesAreSeenAsRelated() {
        NpcDirectory dir = realMiraCase();
        NpcView cartographer = dir.find("mira_cartographer").orElseThrow();

        List<NpcView> related = dir.relatedEntries(cartographer);

        assertEquals(1, related.size(), related.toString());
        assertEquals("mira_first_map", related.get(0).id());
    }

    /** Deux PNJ sans relation démontrable ne doivent pas être rapprochés : un faux lien est pire. */
    @Test
    void unrelatedNpcsAreNotBroughtTogether() {
        Map<String, Object> guide = miraCartographer();
        guide.put("id", "guide");
        guide.put("displayName", "Le Guide");
        guide.put("definedDialogueId", "rpgquest:guide");
        Map<String, Object> guard = miraCartographer();
        guard.put("id", "guard");
        guard.put("displayName", "Le Garde");
        guard.put("definedDialogueId", "rpgquest:guard");

        NpcDirectory dir = NpcDirectory.from(Map.of("npcs", List.of(guide, guard)));

        assertTrue(dir.relatedEntries(dir.find("guide").orElseThrow()).isEmpty());
    }

    // ---- Dialogue partagé ----------------------------------------------------------------------

    /**
     * La question qui décide d'un refus de suppression (#226) : deux définitions qui déclarent le
     * même dialogue le <strong>partagent</strong>, et supprimer l'une ne doit jamais l'emporter.
     */
    @Test
    void aDialogueDeclaredByTwoDefinitionsIsSeenAsShared() {
        Map<String, Object> other = miraCartographer();
        other.put("id", "mira_apprentice");
        other.put("citizensNumericId", 12);

        NpcDirectory dir = NpcDirectory.from(
                Map.of("npcs", List.of(miraCartographer(), other, miraFirstMap())));

        List<NpcView> holders = dir.npcsLinkedToDialogue("rpgquest:mira_first_map");

        assertEquals(2, holders.size(), holders.toString());
        assertEquals(1, dir.npcsLinkedToDialogue("rpgquest:inconnu").size() + 1,
                "un dialogue que personne ne déclare n'est partagé par personne");
        assertTrue(dir.npcsLinkedToDialogue("rpgquest:inconnu").isEmpty());
    }

    // ---- Absence de relevé ---------------------------------------------------------------------

    /**
     * « Aucune dépendance » et « on n'a jamais demandé » ne sont pas la même phrase. Devant un
     * bouton « Supprimer », confondre les deux est précisément ce qu'il ne faut pas faire.
     */
    @Test
    void withoutASurveyTheDirectoryIsExplicitlyUnavailable() {
        assertFalse(NpcDirectory.from(null).available());
        assertTrue(NpcDirectory.from(null).npcs().isEmpty());
        assertFalse(NpcDirectory.unavailable().available());
        // Un relevé présent mais vide, lui, EST une information : zéro PNJ.
        assertTrue(NpcDirectory.from(Map.of("npcs", List.of())).available());
    }

    /** La chaîne « null » du transport JSON ne veut dire qu'« absent ». */
    @Test
    void theStringNullIsTreatedAsAbsent() {
        Map<String, Object> m = miraCartographer();
        m.put("definedDialogueId", "null");
        m.put("dialogueId", "null");

        NpcView npc = NpcDirectory.from(Map.of("npcs", List.of(m))).find("mira_cartographer")
                .orElseThrow();

        assertFalse(npc.hasLinkedDialogue());
        assertEquals("", npc.linkedDialogueId());
    }
}
