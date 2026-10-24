package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.web.DialoguePages;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #730 — le panel refuse, comme le moteur, une réplique sans aucune réponse. */
class DialogueNoExitValidationTest {

    private static DialogueDraft musty(boolean withExit) {
        DialogueDraft d = new DialogueDraft();
        d.id = "rpgquest:musty_test";
        d.start = "start";
        DialogueDraft.Node start = new DialogueDraft.Node("start");
        start.speaker = "Musty";
        start.text = "Salut !";
        start.choices.add(new DialogueDraft.Choice("Tu vends quoi ?", "r2", false));
        DialogueDraft.Node r2 = new DialogueDraft.Node("r2");
        r2.speaker = "Musty";
        r2.text = "Du pain.";
        if (withExit) {
            r2.choices.add(new DialogueDraft.Choice("Au revoir.", "", true));
        }
        d.nodes.add(start);
        d.nodes.add(r2);
        return d;
    }

    @Test
    void aLineWithoutAnyReplyIsAnErrorOnThatLineOnly() {
        List<Diagnostic> diags = DialogueValidator.validate(musty(false));
        List<Diagnostic> errors = diags.stream().filter(x -> x.level() == Diagnostic.Level.ERROR).toList();
        assertEquals(1, errors.size(), errors::toString);
        assertEquals("nodes[r2].choices", errors.get(0).field());
        assertEquals(List.of("r2"), DialogueValidator.nodesWithoutChoice(musty(false)));
    }

    @Test
    void aTerminalReplyMakesTheStartToR2DialogueValid() {
        assertTrue(DialogueValidator.validate(musty(true)).stream().noneMatch(x -> x.level() == Diagnostic.Level.ERROR));
        assertTrue(DialogueValidator.nodesWithoutChoice(musty(true)).isEmpty());
    }

    @Test
    void aNewLineAlwaysComesWithAReplyThatEndsTheConversation() {
        DialogueDraft d = musty(true);
        String id = DialogueForm.addNode(d);
        DialogueDraft.Node fresh = d.nodes.get(d.nodes.size() - 1);
        assertEquals(id, fresh.id);
        assertEquals(1, fresh.choices.size());
        assertTrue(fresh.choices.get(0).close);
    }

    /** Lot C du 24/10 — chaque lien vers une réplique absente, que le moteur refuse, est nommé. */
    @Test
    void everyLinkToAMissingLineIsNamedLikeTheEditorDoes() {
        assertEquals("", DialoguePages.brokenLinkNotice(musty(true)));

        DialogueDraft reply = musty(true);
        reply.nodes.get(1).choices.add(new DialogueDraft.Choice("Et après ?", "r9", false));
        assertTrue(DialoguePages.brokenLinkNotice(reply).startsWith(
                "Mise en jeu impossible : la réponse 2 de la réplique 2 mène à une réplique qui n'existe plus (« r9 »)"),
                DialoguePages.brokenLinkNotice(reply));

        DialogueDraft closing = musty(true);
        closing.nodes.get(1).choices.get(0).next = "r9";
        assertTrue(DialoguePages.brokenLinkNotice(closing).contains("« r9 »"),
                "le moteur juge le lien même d'une réponse qui termine la conversation");

        DialogueDraft entry = musty(true);
        DialogueDraft.Entry e = new DialogueDraft.Entry();
        e.node = "gone";
        entry.entries.add(e);
        assertTrue(DialoguePages.brokenLinkNotice(entry).contains("entrée conditionnelle"));

        DialogueDraft start = musty(true);
        start.start = "gone";
        assertTrue(DialoguePages.brokenLinkNotice(start).startsWith("Mise en jeu impossible : le début de la conversation"));
    }
}
