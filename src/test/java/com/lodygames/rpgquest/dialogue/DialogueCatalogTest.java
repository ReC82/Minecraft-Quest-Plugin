package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.DialogueCatalog.Action;
import com.lodygames.rpgquest.dialogue.DialogueCatalog.Choice;
import com.lodygames.rpgquest.dialogue.DialogueCatalog.Condition;
import com.lodygames.rpgquest.dialogue.DialogueCatalog.LogicalDialogue;
import com.lodygames.rpgquest.dialogue.DialogueCatalog.Node;
import com.lodygames.rpgquest.dialogue.DialogueCatalog.NpcDialogueDecl;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Dérivation pure du catalogue de dialogues (V1 /dialogues) : graphe, actions typées, warnings. */
class DialogueCatalogTest {

    private static Choice choice(String text, String next, List<Action> actions, List<Condition> conditions) {
        return new Choice(text, next, actions, conditions);
    }

    private static Node node(String id, String... nextTargets) {
        List<Choice> choices = new java.util.ArrayList<>();
        for (String n : nextTargets) {
            choices.add(choice("choix", n.isEmpty() ? null : n, List.of(), List.of()));
        }
        if (choices.isEmpty()) {
            choices.add(choice("fin", null, List.of(), List.of()));
        }
        return new Node(id, "Garde", "<white>texte</white>", choices);
    }

    /** Issue #158 : un nœud d'entrée conditionnelle (et sa suite) n'est pas orphelin. */
    @Test
    void entryNodesAndTheirSuccessorsAreReachable() {
        LogicalDialogue d = new LogicalDialogue("rpgquest:guard", "start",
                List.of(node("start", ""), node("thanks", "after"), node("after", ""), node("orphan", "")),
                List.of("thanks"));
        var view = DialogueCatalog.build(List.of(d), List.of(), List.of(), Set.of("guard"), Set.of());

        var byId = view.dialogues().get(0).nodes().stream()
                .collect(java.util.stream.Collectors.toMap(n -> n.id(), n -> n));
        assertTrue(byId.get("thanks").reachable());
        assertTrue(byId.get("after").reachable());
        assertFalse(byId.get("orphan").reachable());
    }

    /**
     * Issue #129 : le badge « sans PNJ » de /dialogues suit la référence {@code dialogue} des
     * définitions PNJ — rattacher, remplacer puis détacher donne chaque fois le bon résultat, à
     * condition que le relevé soit recalculé (le panel le ré-enfile après npc.definition.update).
     */
    @Test
    void linkedNpcsFollowTheDialogueDeclaredByTheNpcDefinition() {
        List<LogicalDialogue> dialogues = List.of(
                new LogicalDialogue("rpgquest:mira_first_map", "start", List.of(node("start", ""))),
                new LogicalDialogue("rpgquest:mira_second_map", "start", List.of(node("start", ""))));
        Set<String> canonical = Set.of("mira_cartographer");

        var detached = linkedByDialogue(DialogueCatalog.build(dialogues, List.of(), List.of(), canonical, Set.of()));
        assertEquals(List.of(), detached.get("rpgquest:mira_first_map"), "avant : sans PNJ");

        var attached = linkedByDialogue(DialogueCatalog.build(dialogues, List.of(),
                List.of(new NpcDialogueDecl("mira_cartographer", "rpgquest:mira_first_map")), canonical, Set.of()));
        assertEquals(List.of("mira_cartographer"), attached.get("rpgquest:mira_first_map"), "rattaché");
        assertEquals(List.of(), attached.get("rpgquest:mira_second_map"));

        var replaced = linkedByDialogue(DialogueCatalog.build(dialogues, List.of(),
                List.of(new NpcDialogueDecl("mira_cartographer", "rpgquest:mira_second_map")), canonical, Set.of()));
        assertEquals(List.of(), replaced.get("rpgquest:mira_first_map"), "l'ancien redevient sans PNJ");
        assertEquals(List.of("mira_cartographer"), replaced.get("rpgquest:mira_second_map"), "le nouveau est lié");

        var removed = linkedByDialogue(DialogueCatalog.build(dialogues, List.of(), List.of(), canonical, Set.of()));
        assertEquals(List.of(), removed.get("rpgquest:mira_second_map"), "détaché : sans PNJ");
    }

    private static java.util.Map<String, List<String>> linkedByDialogue(DialogueCatalog.View view) {
        return view.dialogues().stream().collect(java.util.stream.Collectors.toMap(
                DialogueCatalog.DialogueSummary::id, DialogueCatalog.DialogueSummary::linkedNpcIds));
    }

    @Test
    void reachabilityMarksOrphanNodes() {
        LogicalDialogue d = new LogicalDialogue("rpgquest:guard", "start",
                List.of(node("start", "mid"), node("mid", ""), node("orphan", "")));
        var view = DialogueCatalog.build(List.of(d), List.of(), List.of(), Set.of("guard"), Set.of());

        var summary = view.dialogues().get(0);
        assertEquals(3, summary.nodeCount());
        var byId = summary.nodes().stream().collect(java.util.stream.Collectors.toMap(n -> n.id(), n -> n));
        assertTrue(byId.get("start").start());
        assertTrue(byId.get("start").reachable());
        assertTrue(byId.get("mid").reachable());
        assertFalse(byId.get("orphan").reachable());
        assertTrue(summary.warnings().stream().anyMatch(w -> w.code().equals("NODE_UNREACHABLE")));
    }

    @Test
    void startQuestActionIsTypedAndReferencedQuestsCollected() {
        Action startQuest = new Action("START_QUEST", "rpgquest:first_steps", null, "START_QUEST rpgquest:first_steps");
        Node start = new Node("start", "Garde", "hi",
                List.of(choice("accepter", null, List.of(startQuest), List.of())));
        LogicalDialogue d = new LogicalDialogue("rpgquest:guard", "start", List.of(start));

        var view = DialogueCatalog.build(List.of(d), List.of(), List.of(), Set.of("guard"),
                Set.of("rpgquest:first_steps"));
        var summary = view.dialogues().get(0);
        assertEquals(List.of("rpgquest:first_steps"), summary.startsQuestIds());
        assertEquals(List.of("rpgquest:first_steps"), summary.referencedQuestIds());
        assertEquals("START_QUEST", summary.nodes().get(0).choices().get(0).actions().get(0).kind());
        assertTrue(summary.warnings().isEmpty());
    }

    @Test
    void startQuestToUnknownQuestIsWarned() {
        Action startQuest = new Action("START_QUEST", "rpgquest:ghost_quest", null, "raw");
        Node start = new Node("start", "Garde", "hi",
                List.of(choice("x", null, List.of(startQuest), List.of())));
        var view = DialogueCatalog.build(
                List.of(new LogicalDialogue("rpgquest:guard", "start", List.of(start))),
                List.of(), List.of(), Set.of("guard"), Set.of("rpgquest:first_steps"));
        assertTrue(view.dialogues().get(0).warnings().stream()
                .anyMatch(w -> w.code().equals("QUEST_REF_UNKNOWN") && w.message().contains("ghost_quest")));
    }

    @Test
    void questStateConditionAlsoCountsAsReferencedQuest() {
        Condition cond = new Condition("QUEST_STATE", "rpgquest:first_steps", "COMPLETED", "raw", false);
        Node start = new Node("start", "Garde", "hi", List.of(choice("x", null, List.of(), List.of(cond))));
        var view = DialogueCatalog.build(
                List.of(new LogicalDialogue("rpgquest:guard", "start", List.of(start))),
                List.of(), List.of(), Set.of("guard"), Set.of("rpgquest:first_steps"));
        assertEquals(List.of("rpgquest:first_steps"), view.dialogues().get(0).referencedQuestIds());
    }

    @Test
    void dialogueLinkedByConventionAndByDefinition() {
        LogicalDialogue guard = new LogicalDialogue("rpgquest:guard", "start", List.of(node("start")));
        // « guard » canonique -> lié par convention ; « libraire » déclaré par une définition.
        LogicalDialogue lib = new LogicalDialogue("rpgquest:libraire", "start", List.of(node("start")));
        var view = DialogueCatalog.build(List.of(guard, lib), List.of(),
                List.of(new NpcDialogueDecl("libraire", "rpgquest:libraire")),
                Set.of("guard"), Set.of());
        var byId = view.dialogues().stream().collect(java.util.stream.Collectors.toMap(x -> x.id(), x -> x));
        assertEquals(List.of("guard"), byId.get("rpgquest:guard").linkedNpcIds());
        assertEquals(List.of("libraire"), byId.get("rpgquest:libraire").linkedNpcIds());
    }

    @Test
    void dialogueWiredToNoNpcIsAnInfoWarning() {
        var view = DialogueCatalog.build(
                List.of(new LogicalDialogue("rpgquest:floating", "start", List.of(node("start")))),
                List.of(), List.of(), Set.of("guard"), Set.of());
        assertTrue(view.dialogues().get(0).warnings().stream().anyMatch(w -> w.code().equals("DIALOGUE_NO_NPC")));
    }

    @Test
    void definitionDeclaringADifferentDialogueDiverges() {
        LogicalDialogue guard = new LogicalDialogue("rpgquest:guard", "start", List.of(node("start")));
        var view = DialogueCatalog.build(List.of(guard), List.of(),
                List.of(new NpcDialogueDecl("guard", "rpgquest:autre")), Set.of("guard"), Set.of());
        assertTrue(view.dialogues().get(0).warnings().stream()
                .anyMatch(w -> w.code().equals("DEFINITION_DIALOGUE_DIVERGES")));
    }

    @Test
    void loadIssuesAndMissingDeclaredArePassedThrough() {
        var view = DialogueCatalog.build(List.of(),
                List.of(new DialogueCatalog.LoadIssue("broken.yml", "« nodes » est obligatoire.")),
                List.of(new NpcDialogueDecl("ghost", "rpgquest:ghost")), Set.of(), Set.of());
        assertEquals(1, view.loadIssues().size());
        assertEquals("broken.yml", view.loadIssues().get(0).file());
        assertEquals(1, view.declaredButMissing().size());
        assertEquals("ghost", view.declaredButMissing().get(0).npcId());
        assertEquals(0, view.total());
    }

    @Test
    void warnedDialoguesAreSortedFirst() {
        LogicalDialogue clean = new LogicalDialogue("rpgquest:guard", "start", List.of(node("start")));
        LogicalDialogue warned = new LogicalDialogue("rpgquest:floating", "start", List.of(node("start")));
        var view = DialogueCatalog.build(List.of(clean, warned), List.of(), List.of(), Set.of("guard"), Set.of());
        assertEquals("rpgquest:floating", view.dialogues().get(0).id(), "le dialogue avec warning d'abord");
    }
}
