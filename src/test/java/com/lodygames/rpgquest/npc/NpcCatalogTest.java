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

    @Test
    void closestCanonicalIsSaneAndNullSafe() {
        assertEquals("guard", NpcCatalog.closestCanonical("guardz", Set.of("guard", "guide")));
        assertNull(NpcCatalog.closestCanonical("banker", Set.of("guard", "guide")));
        assertNull(NpcCatalog.closestCanonical("guard", Set.of("guard")));
    }
}
