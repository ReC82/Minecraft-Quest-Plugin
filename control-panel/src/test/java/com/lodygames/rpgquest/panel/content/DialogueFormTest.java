package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Issue #332 — l'éditeur simple ne perd rien : un dialogue passé par le formulaire sans être
 * modifié se réécrit à l'identique, conditions et effets compris.
 */
class DialogueFormTest {

    private static final String COMPLEX = """
            id: rpgquest:sage
            start: hello
            nodes:
              hello:
                speaker: "Sage"
                text: "<gold>Bonjour</gold> voyageur."
                choices:
                  - text: "Une quête ?"
                    conditions:
                      - type: QUEST_STATE
                        quest: rpgquest:herbes
                        state: NOT_STARTED
                      - type: HAS_ITEM
                        negate: false
                        material: STONE
                        amount: 3
                    actions:
                      - type: GIVE_ITEM
                        material: BREAD
                        amount: 2
                      - type: START_QUEST
                        quest: rpgquest:herbes
                      - type: CLOSE
                  - text: "Je reviens"
                    conditions:
                      - type: HAS_PENDING_DELIVERY
                        negate: true
                    actions:
                      - type: CLOSE
                      - type: SET_VARIABLE
                        key: "vu_sage"
                        value: "true"
                  - text: "Raconte"
                    next: story
                  - text: "Rien"
              story:
                speaker: "Sage"
                text: "Il était une fois…"
                choices:
                  - text: "Retour"
                    actions:
                      - type: START_QUEST
                        quest: rpgquest:a
                      - type: TURN_IN_QUEST
                        quest: rpgquest:b
                    next: hello
            """;

    private static String roundTrip(DialogueDraft d) {
        return DialogueYaml.write(DialogueForm.parse(DialogueForm.fields(d)).draft());
    }

    @Test
    void anUntouchedComplexDialogueComesBackIdentical() {
        DialogueYaml.ReadResult r = DialogueYaml.read(COMPLEX);
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(DialogueYaml.write(r.draft()), roundTrip(r.draft()));
    }

    @Test
    void everyDialogueOfTheRepositoryComesBackIdentical() throws Exception {
        Path dir = Path.of("..", "src", "main", "resources", "dialogues");
        Assumptions.assumeTrue(Files.isDirectory(dir), "dialogues du plugin introuvables");
        int checked = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                DialogueYaml.ReadResult r = DialogueYaml.read(Files.readString(p));
                if (r.draft() == null || !r.ok()) {
                    continue; // l'éditeur ouvre ces fichiers en lecture seule
                }
                assertEquals(DialogueYaml.write(r.draft()), roundTrip(r.draft()), p.getFileName().toString());
                checked++;
            }
        }
        assertTrue(checked >= 4, "guide, garde, Jo, libraire… : " + checked + " fichier(s) vérifié(s)");
    }

    @Test
    void theQuestOfferIsReadAsTheSimpleChoiceAndKeepsItsPlace() {
        DialogueDraft d = DialogueYaml.read(COMPLEX).draft();
        DialogueForm.Reply first = DialogueForm.reply(d.nodes.get(0).choices.get(0));
        assertEquals("START_QUEST", first.questAction());
        assertEquals("rpgquest:herbes", first.questId());
        assertEquals(DialogueForm.END, first.next());
        assertEquals(1, first.otherEffects().size(), "le pain reste un autre effet");
        assertEquals(2, first.conditions().size());
    }

    @Test
    void offeringAQuestOnASimpleReplyPutsItBeforeTheClose() {
        DialogueDraft d = DialogueYaml.read(COMPLEX).draft();
        Map<String, String> f = new java.util.LinkedHashMap<>(DialogueForm.fields(d));
        // « Rien » : aucune action, fin implicite. On y propose une quête.
        f.put(DialogueForm.reply(0, 3, "qa"), "START_QUEST");
        f.put(DialogueForm.reply(0, 3, "qid"), "rpgquest:c");
        DialogueDraft.Choice c = DialogueForm.parse(f).draft().nodes.get(0).choices.get(3);
        assertEquals("START_QUEST", c.actions.get(0).get("type"));
        assertEquals("rpgquest:c", c.actions.get(0).get("quest"));
        assertEquals("CLOSE", c.actions.get(1).get("type"), "la conversation se ferme en dernier");
    }

    @Test
    void pointingAReplyToANewLineCreatesItSpokenByTheSameCharacter() {
        DialogueDraft d = DialogueYaml.read(COMPLEX).draft();
        Map<String, String> f = new java.util.LinkedHashMap<>(DialogueForm.fields(d));
        f.put(DialogueForm.reply(0, 1, "next"), DialogueForm.NEW);
        DialogueForm.Parsed p = DialogueForm.parse(f);
        assertEquals(List.of("r2"), p.createdNodes());
        DialogueDraft.Choice c = p.draft().nodes.get(0).choices.get(1);
        assertEquals("r2", c.next);
        assertFalse(c.actions.stream().anyMatch(a -> "CLOSE".equals(a.get("type"))),
                "elle ne ferme plus la conversation");
        assertEquals("SET_VARIABLE", c.actions.get(0).get("type"), "l'autre effet est conservé");
        assertEquals("Sage", p.draft().nodes.get(2).speaker);
    }

    @Test
    void removingAReplyOrAnEffectIsExplicit() {
        DialogueDraft d = DialogueYaml.read(COMPLEX).draft();
        Map<String, String> f = new java.util.LinkedHashMap<>(DialogueForm.fields(d));
        f.put(DialogueForm.reply(0, 2, "del"), "1");
        f.put(DialogueForm.entry(0, 0, "fx", 0, "del"), "1");
        DialogueDraft out = DialogueForm.parse(f).draft();
        assertEquals(3, out.nodes.get(0).choices.size());
        assertEquals(List.of("START_QUEST", "CLOSE"),
                out.nodes.get(0).choices.get(0).actions.stream().map(a -> a.get("type")).toList());
    }

    @Test
    void unTickingNegateRemovesItAndAHandWrittenFalseSurvives() {
        DialogueDraft d = DialogueYaml.read(COMPLEX).draft();
        Map<String, String> f = new java.util.LinkedHashMap<>(DialogueForm.fields(d));
        f.remove(DialogueForm.entry(0, 1, "cd", 0, "v_negate"));
        DialogueDraft out = DialogueForm.parse(f).draft();
        assertFalse(out.nodes.get(0).choices.get(1).conditions.get(0).containsKey("negate"));
        assertEquals("false", out.nodes.get(0).choices.get(0).conditions.get(1).get("negate"));
    }

    @Test
    void changingAConditionTypeKeepsOnlyTheFieldsOfTheNewType() {
        DialogueDraft d = DialogueYaml.read(COMPLEX).draft();
        Map<String, String> f = new java.util.LinkedHashMap<>(DialogueForm.fields(d));
        f.put(DialogueForm.entry(0, 0, "cd", 0, "v_type"), "HAS_MAIN_CLAIM");
        Map<String, String> cond = DialogueForm.parse(f).draft().nodes.get(0).choices.get(0).conditions.get(0);
        assertEquals(Map.of("type", "HAS_MAIN_CLAIM"), cond);
    }

    @Test
    void removingALineRedirectsItsRepliesToTheEndAndSaysHowMany() {
        DialogueDraft d = DialogueYaml.read(COMPLEX).draft();
        assertEquals(-1, DialogueForm.removeNode(d, 0), "la réplique de départ ne se retire pas");
        assertEquals(1, DialogueForm.removeNode(d, 1));
        DialogueDraft.Choice c = d.nodes.get(0).choices.get(2);
        assertEquals("", c.next);
        assertTrue(c.close);
    }

    @Test
    void displayNumbersStartAtTheFirstLine() {
        DialogueDraft d = DialogueYaml.read(COMPLEX).draft();
        assertEquals(1, DialogueForm.number(d, "hello"));
        assertEquals(2, DialogueForm.number(d, "story"));
        assertEquals("Bonjour voyageur.", DialogueForm.excerpt(d.nodes.get(0).text, 40));
        assertTrue(DialogueForm.unreachable(d).isEmpty());
    }
}
