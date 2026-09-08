package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.NpcCatalog.CitizensBinding;
import com.lodygames.rpgquest.npc.NpcCatalog.DialogueLink;
import com.lodygames.rpgquest.npc.NpcCatalog.NpcRow;
import com.lodygames.rpgquest.npc.NpcCatalog.QuestLink;
import com.lodygames.rpgquest.npc.NpcCatalog.Result;
import com.lodygames.rpgquest.npc.NpcCatalog.Warning;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Dérivation du catalogue PNJ (croisement Citizens ↔ dialogues ↔ quêtes) — JUnit pur, sans Bukkit. */
class NpcCatalogTest {

    private static DialogueLink dialogue(String npcId, String speaker, String... startsQuests) {
        return new DialogueLink(npcId, "rpgquest:" + npcId, 5, 8, List.of(startsQuests), speaker);
    }

    private static NpcRow row(Result r, String id) {
        return r.npcs().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    private static Optional<Warning> warning(NpcRow row, String code) {
        return row.warnings().stream().filter(w -> w.code().equals(code)).findFirst();
    }

    @Test
    void fullyConsistentNpcHasNoWarningAndDerivesDisplayName() {
        Result r = NpcCatalog.build(
                List.of(dialogue("guard", "Garde", "rpgquest:first_steps")),
                List.of(new QuestLink("rpgquest:crystal_hunt", "guard", List.of("guard"))),
                List.of(new CitizensBinding("guard", 7)),
                true);

        NpcRow guard = row(r, "guard");
        assertEquals("Garde", guard.displayName());
        assertEquals(Integer.valueOf(7), guard.citizensNumericId());
        assertTrue(guard.bound());
        assertTrue(guard.hasDialogue());
        assertEquals(List.of("rpgquest:crystal_hunt"), guard.questsGiven());
        assertEquals(List.of("rpgquest:crystal_hunt"), guard.questsReferenced());
        assertEquals(List.of("rpgquest:first_steps"), guard.dialogueStartsQuests());
        assertTrue(guard.warnings().isEmpty(), () -> guard.warnings().toString());
        assertTrue(guard.sources().containsAll(List.of("BINDING", "DIALOGUE", "QUEST_GIVER", "QUEST_TALK")));
    }

    @Test
    void questReferencesAnIdWithNoBinding_isAWarning() {
        Result r = NpcCatalog.build(
                List.of(dialogue("guard", "Garde")),
                List.of(new QuestLink("rpgquest:crystal_hunt", "guard", List.of("guard"))),
                List.of(),
                true);

        Warning w = warning(row(r, "guard"), "QUEST_REF_NO_NPC").orElseThrow();
        assertEquals("warning", w.severity());
        assertTrue(w.message().contains("aucun PNJ Citizens"));
    }

    @Test
    void citizensInactiveDowngradesTheNoBindingWarningToInfo() {
        Result r = NpcCatalog.build(
                List.of(),
                List.of(new QuestLink("rpgquest:crystal_hunt", "guard", List.of())),
                List.of(),
                false);
        assertEquals("info", warning(row(r, "guard"), "QUEST_REF_NO_NPC").orElseThrow().severity());
        assertFalse(r.citizensAvailable());
    }

    @Test
    void unusedTagSuggestsTheClosestCanonicalId_gardeVsGuard() {
        Result r = NpcCatalog.build(
                List.of(dialogue("guard", "Garde")),
                List.of(new QuestLink("rpgquest:crystal_hunt", "guard", List.of("guard"))),
                List.of(new CitizensBinding("garde", 3)),
                true);

        NpcRow garde = row(r, "garde");
        Warning w = warning(garde, "TAGGED_UNUSED").orElseThrow();
        assertEquals("info", w.severity());
        assertTrue(w.message().contains("guard"), w.message());
        // 'guard' de son côté est bien vu comme référencé sans PNJ
        assertTrue(warning(row(r, "guard"), "QUEST_REF_NO_NPC").isPresent());
    }

    @Test
    void duplicateBindingIsAnError() {
        Result r = NpcCatalog.build(
                List.of(dialogue("guide", "Guide")),
                List.of(),
                List.of(new CitizensBinding("guide", 1), new CitizensBinding("guide", 2)),
                true);
        NpcRow guide = row(r, "guide");
        assertEquals(2, guide.bindingCount());
        assertEquals("error", warning(guide, "DUPLICATE_BINDING").orElseThrow().severity());
    }

    @Test
    void dialogueOnlyIdIsInfoOnly_andGiverWithoutDialogueIsFlagged() {
        Result r = NpcCatalog.build(
                List.of(dialogue("jo", "Jo")),
                List.of(new QuestLink("rpgquest:q1", "merchant", List.of())),
                List.of(new CitizensBinding("merchant", 4)),
                true);

        assertEquals("info", warning(row(r, "jo"), "DIALOGUE_NO_NPC").orElseThrow().severity());
        assertTrue(warning(row(r, "merchant"), "GIVER_NO_DIALOGUE").isPresent());
    }

    @Test
    void canonicalIdsExcludeBindingOnlyTags_andAreSorted() {
        Result r = NpcCatalog.build(
                List.of(dialogue("guard", "Garde"), dialogue("guide", "Guide")),
                List.of(new QuestLink("rpgquest:q", "alchemist", List.of("guard"))),
                List.of(new CitizensBinding("garde", 3)),
                true);
        assertEquals(List.of("alchemist", "guard", "guide"), r.canonicalIds());
    }

    @Test
    void countsAndOrdering() {
        Result r = NpcCatalog.build(
                List.of(dialogue("guard", "Garde")),
                List.of(new QuestLink("rpgquest:c", "missing", List.of("guard"))),
                List.of(new CitizensBinding("guard", 7), new CitizensBinding("dup", 1), new CitizensBinding("dup", 2)),
                true);

        assertEquals(3, r.total()); // guard, missing, dup (guard referenced by both dialogue + talk = 1 row)
        assertEquals(2, r.bound()); // guard (1 binding) + dup (2 bindings, 1 row)
        assertEquals(1, r.unbound()); // missing
        assertEquals(2, r.withWarnings()); // dup (error), missing (warning)
        assertEquals("dup", r.npcs().get(0).id(), "erreur en premier");
        assertEquals("missing", r.npcs().get(1).id(), "avertissement ensuite");
        assertEquals("guard", r.npcs().get(2).id(), "propre en dernier");
    }

    @Test
    void closestCanonicalReturnsNullWhenNothingIsNear() {
        assertEquals("guard", NpcCatalog.closestCanonical("guardz", java.util.Set.of("guard", "guide")));
        assertEquals(null, NpcCatalog.closestCanonical("banker", java.util.Set.of("guard", "guide")));
        assertEquals(null, NpcCatalog.closestCanonical("guard", java.util.Set.of("guard")));
    }
}
