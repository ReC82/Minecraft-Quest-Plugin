package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Sérialisation / relecture ciblée des dialogues du Control Panel (issue #145). */
class DialogueYamlTest {

    private static DialogueDraft skeleton() {
        DialogueDraft d = new DialogueDraft();
        d.id = "lily_intro";
        d.start = "start";
        DialogueDraft.Node n = new DialogueDraft.Node("start");
        n.speaker = "Lily";
        n.text = "<yellow>Bonjour.</yellow>";
        n.choices.add(new DialogueDraft.Choice("Au revoir", "", true));
        d.nodes.add(n);
        return d;
    }

    @Test
    void writesCanonicalSkeletonMatchingTheEngineFormat() {
        String yaml = DialogueYaml.write(skeleton());
        assertTrue(yaml.contains("id: rpgquest:lily_intro"));
        assertTrue(yaml.contains("start: start"));
        assertTrue(yaml.contains("  start:\n"));
        assertTrue(yaml.contains("    speaker: \"Lily\"\n"));
        assertTrue(yaml.contains("    text: \"<yellow>Bonjour.</yellow>\"\n"));
        assertTrue(yaml.contains("      - text: \"Au revoir\"\n"));
        assertTrue(yaml.contains("        actions:\n          - type: CLOSE\n"));
    }

    @Test
    void skeletonRoundTripsWithoutProblems() {
        String yaml = DialogueYaml.write(skeleton());
        assertTrue(DialogueYaml.roundTripProblems(yaml).isEmpty(), "le squelette se relit à l'identique");
        DialogueYaml.ReadResult r = DialogueYaml.read(yaml);
        assertTrue(r.ok());
        assertEquals("lily_intro", r.draft().id);
        assertEquals(1, r.draft().nodes.size());
        DialogueDraft.Node n = r.draft().startNode();
        assertEquals("Lily", n.speaker);
        assertEquals(1, n.choices.size());
        assertTrue(n.choices.get(0).close);
    }

    @Test
    void readsHandWrittenFileWithNextAndConditions() {
        String yaml = "id: rpgquest:guard\nstart: greeting\n\nnodes:\n"
                + "  greeting:\n    speaker: \"Garde\"\n    text: \"Bonjour.\"\n    choices:\n"
                + "      - text: \"J'accepte\"\n        conditions:\n          - type: QUEST_STATE\n"
                + "            quest: rpgquest:first_steps\n            state: NOT_STARTED\n"
                + "        actions:\n          - type: START_QUEST\n            quest: rpgquest:first_steps\n"
                + "        next: accepted\n"
                + "      - text: \"Non merci\"\n        actions:\n          - type: CLOSE\n"
                + "  accepted:\n    speaker: \"Garde\"\n    text: \"Bien.\"\n    choices:\n"
                + "      - text: \"OK\"\n        next: greeting\n";
        DialogueYaml.ReadResult r = DialogueYaml.read(yaml);
        assertEquals("guard", r.draft().id);
        assertEquals("greeting", r.draft().start);
        assertEquals(2, r.draft().nodes.size());
        DialogueDraft.Node greeting = r.draft().startNode();
        assertEquals(2, greeting.choices.size());
        assertEquals("accepted", greeting.choices.get(0).next);
        assertFalse(greeting.choices.get(0).simple, "un choix avec condition/action n'est pas « simple »");
        assertTrue(greeting.choices.get(1).close);
    }

    @Test
    void validatorFlagsMissingStartNodeAndDanglingNext() {
        DialogueDraft d = new DialogueDraft();
        d.id = "x";
        d.start = "nowhere";
        DialogueDraft.Node n = new DialogueDraft.Node("start");
        n.speaker = "A";
        n.text = "t";
        n.choices.add(new DialogueDraft.Choice("go", "ghost", false));
        d.nodes.add(n);
        List<Diagnostic> diags = DialogueValidator.validate(d);
        assertTrue(diags.stream().anyMatch(x -> x.field().equals("start") && x.level() == Diagnostic.Level.ERROR));
        assertTrue(diags.stream().anyMatch(x -> x.message().contains("ghost")));
    }

    @Test
    void validatorAcceptsACleanSkeleton() {
        assertTrue(DialogueValidator.validate(skeleton()).isEmpty());
    }
}
