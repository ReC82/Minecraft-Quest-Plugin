package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.NpcCatalog.CitizensBinding;
import com.lodygames.rpgquest.npc.NpcCatalog.DialogueLink;
import com.lodygames.rpgquest.npc.NpcCatalog.LogicalDefinition;
import com.lodygames.rpgquest.npc.NpcCatalog.NpcRow;
import com.lodygames.rpgquest.npc.NpcCatalog.QuestLink;
import com.lodygames.rpgquest.npc.NpcCatalog.Result;
import com.lodygames.rpgquest.npc.NpcCatalog.Warning;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Dérivation V2 : définition logique vs binding Citizens, états, anomalies de références. JUnit pur. */
class NpcCatalogTest {

    private static LogicalDefinition def(String id, String dialogueId, boolean enabled) {
        return new LogicalDefinition(id, cap(id), null, dialogueId, "quest_giver", enabled);
    }

    private static DialogueLink dialogue(String npcId, String speaker, String... startsQuests) {
        return new DialogueLink(npcId, "rpgquest:" + npcId, 5, 8, List.of(startsQuests), speaker);
    }

    private static NpcRow row(Result r, String id) {
        return r.npcs().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    private static Optional<Warning> warn(NpcRow row, String code) {
        return row.warnings().stream().filter(w -> w.code().equals(code)).findFirst();
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Test
    void definitionPlusBindingPlusDialogue_isLinkedAndClean() {
        Result r = NpcCatalog.build(
                List.of(def("guard", "rpgquest:guard", true)),
                List.of(dialogue("guard", "Garde", "rpgquest:first_steps")),
                List.of(new QuestLink("rpgquest:crystal_hunt", "guard", List.of("guard"))),
                List.of(new CitizensBinding("guard", 7)),
                true);

        NpcRow guard = row(r, "guard");
        assertTrue(guard.logicalDefinitionPresent());
        assertTrue(guard.citizensBindingPresent());
        assertEquals("LINKED", guard.state());
        assertEquals("Guard", guard.displayName());
        assertEquals(Integer.valueOf(7), guard.citizensNumericId());
        assertTrue(guard.warnings().isEmpty(), () -> guard.warnings().toString());
        assertTrue(guard.sources().contains("DEFINITION"));
        assertEquals(List.of("guard"), r.definedIds());
    }

    @Test
    void definitionWithoutBinding_isNotLinked_operationalInfo() {
        Result r = NpcCatalog.build(
                List.of(def("bob", null, true)),
                List.of(), List.of(), List.of(), true);
        NpcRow bob = row(r, "bob");
        assertEquals("NOT_LINKED", bob.state());
        assertEquals("info", warn(bob, "NOT_LINKED").orElseThrow().severity());
    }

    @Test
    void referenceWithoutDefinition_isAContentError() {
        Result r = NpcCatalog.build(
                List.of(),
                List.of(),
                List.of(new QuestLink("rpgquest:woodcutters_request", null, List.of("woodcutter_bob"))),
                List.of(),
                true);
        NpcRow bob = row(r, "woodcutter_bob");
        assertFalse(bob.logicalDefinitionPresent());
        assertEquals("UNDEFINED_REFERENCE", bob.state());
        Warning w = warn(bob, "NO_DEFINITION").orElseThrow();
        assertEquals("error", w.severity());
        assertTrue(w.message().contains("objectif « parler à »"));
    }

    @Test
    void bindingWithoutDefinition_isError_withClosestDefinedIdHint() {
        Result r = NpcCatalog.build(
                List.of(def("guard", "rpgquest:guard", true)),
                List.of(dialogue("guard", "Garde")),
                List.of(),
                List.of(new CitizensBinding("garde", 3)),
                true);
        NpcRow garde = row(r, "garde");
        assertEquals("CITIZENS_ORPHAN", garde.state());
        Warning w = warn(garde, "BINDING_NO_DEFINITION").orElseThrow();
        assertEquals("error", w.severity());
        assertTrue(w.message().contains("guard"), w.message());
    }

    @Test
    void definitionPointingAtAMissingDialogue_isBroken() {
        Result r = NpcCatalog.build(
                List.of(def("mystic", "rpgquest:mystic", true)),
                List.of(), List.of(), List.of(new CitizensBinding("mystic", 9)), true);
        NpcRow mystic = row(r, "mystic");
        assertEquals("BROKEN", mystic.state());
        assertEquals("error", warn(mystic, "DIALOGUE_MISSING").orElseThrow().severity());
    }

    @Test
    void disabledDefinition_isDisabledState() {
        Result r = NpcCatalog.build(
                List.of(def("old", null, false)), List.of(), List.of(),
                List.of(new CitizensBinding("old", 1)), true);
        NpcRow old = row(r, "old");
        assertEquals("DISABLED", old.state());
        assertEquals("info", warn(old, "DISABLED").orElseThrow().severity());
    }

    @Test
    void duplicateBindingIsAnError() {
        Result r = NpcCatalog.build(
                List.of(def("guide", null, true)), List.of(), List.of(),
                List.of(new CitizensBinding("guide", 1), new CitizensBinding("guide", 2)), true);
        assertEquals(2, row(r, "guide").bindingCount());
        assertEquals("error", warn(row(r, "guide"), "DUPLICATE_BINDING").orElseThrow().severity());
    }

    @Test
    void canonicalIdsPreferDefinitionsAndDefinedIdsAreSeparate() {
        Result r = NpcCatalog.build(
                List.of(def("guard", "rpgquest:guard", true), def("guide", null, true)),
                List.of(dialogue("guard", "Garde")),
                List.of(new QuestLink("rpgquest:q", null, List.of("alchemist"))),
                List.of(new CitizensBinding("garde", 3)),
                true);
        assertEquals(List.of("alchemist", "guard", "guide"), r.canonicalIds());
        assertEquals(List.of("guard", "guide"), r.definedIds());
    }

    @Test
    void countsAndOrdering() {
        Result r = NpcCatalog.build(
                List.of(def("guard", "rpgquest:guard", true)),
                List.of(dialogue("guard", "Garde")),
                List.of(new QuestLink("rpgquest:c", "missing", List.of("guard"))),
                List.of(new CitizensBinding("guard", 7), new CitizensBinding("dup", 1), new CitizensBinding("dup", 2)),
                true);

        assertEquals(3, r.total());     // guard, missing, dup
        assertEquals(1, r.withDefinition());
        assertEquals(2, r.withoutDefinition());
        assertEquals(2, r.bound());     // guard + dup
        // erreurs d'abord (dup: DUPLICATE_BINDING, missing: NO_DEFINITION), guard propre en dernier
        assertEquals("guard", r.npcs().get(r.npcs().size() - 1).id());
    }

    // ---- Issue #225 : le dialogue déclaré, et l'entrée fantôme qu'il fabriquait ---------------

    /**
     * Le cas Mira, reproduit à l'identique depuis le relevé réel du 2026-10-08 :
     * {@code mira_cartographer} (définition, Citizens #9) déclare {@code rpgquest:mira_first_map}.
     *
     * <p>Avant le correctif, le catalogue rendait <strong>deux</strong> lignes : Mira sans dialogue
     * (aucun ne porte son nom) et un « PNJ » {@code mira_first_map} sans définition, déduit du
     * dialogue. Les deux étaient faux.</p>
     */
    @Test
    void aDeclaredDialogueNamedDifferentlyIsResolvedAndCreatesNoGhostNpc() {
        Result r = NpcCatalog.build(
                List.of(def("mira_cartographer", "rpgquest:mira_first_map", true)),
                List.of(new DialogueLink("mira_first_map", "rpgquest:mira_first_map", 2, 3,
                        List.of(), "Mira")),
                List.of(),
                List.of(new CitizensBinding("mira_cartographer", 9)),
                true);

        assertEquals(1, r.total(), () -> "une seule Mira : " + r.npcs());
        NpcRow mira = row(r, "mira_cartographer");
        assertTrue(mira.hasDialogue(), "le dialogue déclaré doit être résolu");
        assertEquals("rpgquest:mira_first_map", mira.dialogueId());
        assertEquals("rpgquest:mira_first_map", mira.definedDialogueId());
        assertEquals(2, mira.dialogueNodes());
        assertEquals(3, mira.dialogueChoices());
        assertEquals("LINKED", mira.state());
        assertTrue(mira.warnings().isEmpty(), () -> mira.warnings().toString());
        assertTrue(r.npcs().stream().noneMatch(n -> n.id().equals("mira_first_map")),
                "le dialogue revendiqué ne doit plus produire d'entrée PNJ");
    }

    /** La convention de nom reste le défaut quand la définition ne déclare rien. */
    @Test
    void theNameConventionStillAppliesWhenNothingIsDeclared() {
        Result r = NpcCatalog.build(
                List.of(def("guide", null, true)),
                List.of(dialogue("guide", "Guide")),
                List.of(), List.of(new CitizensBinding("guide", 1)), true);

        NpcRow guide = row(r, "guide");
        assertTrue(guide.hasDialogue());
        assertEquals("rpgquest:guide", guide.dialogueId());
        assertNull(guide.definedDialogueId());
    }

    /**
     * Un dialogue que <strong>personne</strong> ne revendique reste déduit en PNJ — c'est le cas de
     * transition légitime — mais son anomalie dit maintenant d'où vient l'entrée et comment la
     * corriger, au lieu d'un « à migrer » sans cause.
     */
    @Test
    void anUnclaimedDialogueStillShowsUpButExplainsItself() {
        Result r = NpcCatalog.build(
                List.of(),
                List.of(new DialogueLink("mira_first_map", "rpgquest:mira_first_map", 2, 3,
                        List.of(), "Mira")),
                List.of(), List.of(), true);

        NpcRow ghost = row(r, "mira_first_map");
        assertFalse(ghost.logicalDefinitionPresent());
        assertEquals("UNDEFINED_REFERENCE", ghost.state());
        Warning w = warn(ghost, "DIALOGUE_WITHOUT_NPC").orElseThrow();
        assertEquals("error", w.severity());
        assertTrue(w.message().contains("rpgquest:mira_first_map"), w.message());
        assertTrue(w.message().contains("rattacher ce dialogue"), w.message());
        assertTrue(warn(ghost, "NO_DEFINITION").isEmpty(),
                "l'anomalie générique est remplacée par celle qui nomme la cause");
    }

    /** Un id référencé par une quête garde son anomalie d'origine : la cause n'est pas la même. */
    @Test
    void aQuestReferenceKeepsTheGenericMissingDefinitionWarning() {
        Result r = NpcCatalog.build(
                List.of(),
                List.of(new DialogueLink("bob", "rpgquest:bob", 1, 1, List.of(), "Bob")),
                List.of(new QuestLink("rpgquest:q", "bob", List.of())),
                List.of(), true);

        assertTrue(warn(row(r, "bob"), "NO_DEFINITION").isPresent());
        assertTrue(warn(row(r, "bob"), "DIALOGUE_WITHOUT_NPC").isEmpty());
    }

    /**
     * Deux définitions qui déclarent le même dialogue : les deux le voient, et aucune entrée
     * fantôme n'apparaît. C'est ce partage que la suppression d'un PNJ ne doit jamais ignorer
     * (#226).
     */
    @Test
    void aDialogueDeclaredByTwoDefinitionsIsSeenByBoth() {
        Result r = NpcCatalog.build(
                List.of(def("mira_cartographer", "rpgquest:mira_first_map", true),
                        def("mira_apprentice", "rpgquest:mira_first_map", true)),
                List.of(new DialogueLink("mira_first_map", "rpgquest:mira_first_map", 2, 3,
                        List.of(), "Mira")),
                List.of(), List.of(), true);

        assertEquals(2, r.total(), () -> r.npcs().toString());
        assertEquals("rpgquest:mira_first_map", row(r, "mira_cartographer").dialogueId());
        assertEquals("rpgquest:mira_first_map", row(r, "mira_apprentice").dialogueId());
    }

    /**
     * Une définition qui déclare un dialogue absent reste cassée : résoudre le déclaré ne doit pas
     * masquer une référence morte.
     */
    @Test
    void aDeclaredDialogueThatIsNotLoadedStillBreaksTheDefinition() {
        Result r = NpcCatalog.build(
                List.of(def("mira_cartographer", "rpgquest:disparu", true)),
                List.of(new DialogueLink("mira_first_map", "rpgquest:mira_first_map", 2, 3,
                        List.of(), "Mira")),
                List.of(), List.of(new CitizensBinding("mira_cartographer", 9)), true);

        NpcRow mira = row(r, "mira_cartographer");
        assertEquals("BROKEN", mira.state());
        assertFalse(mira.hasDialogue(), "un dialogue déclaré mais absent n'est pas un dialogue");
        assertEquals("error", warn(mira, "DIALOGUE_MISSING").orElseThrow().severity());
        // Le dialogue que personne ne revendique reste visible, avec sa propre explication.
        assertTrue(warn(row(r, "mira_first_map"), "DIALOGUE_WITHOUT_NPC").isPresent());
    }

    @Test
    void closestCanonicalIsSaneAndNullSafe() {
        assertEquals("guard", NpcCatalog.closestCanonical("guardz", Set.of("guard", "guide")));
        assertNull(NpcCatalog.closestCanonical("banker", Set.of("guard", "guide")));
        assertNull(NpcCatalog.closestCanonical("guard", Set.of("guard")));
    }
}
