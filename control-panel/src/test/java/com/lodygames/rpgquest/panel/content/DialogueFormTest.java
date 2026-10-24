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

    // ---- Issue #815 : supprimer une réplique orpheline sans casser les liens ----------------

    /** start → a → b ; a → c ; orphan → b ; loner (se cite lui-même) ; entries → gated. */
    private static final String GRAPH = """
            id: rpgquest:graphe
            start: start
            entries:
              - node: gated
                conditions:
                  - type: QUEST_STATE
                    quest: rpgquest:x
                    state: COMPLETED
            nodes:
              start:
                speaker: "T"
                text: "Début"
                choices:
                  - text: "Vers A"
                    next: a
              a:
                speaker: "T"
                text: "A"
                choices:
                  - text: "Vers B"
                    next: b
                  - text: "Vers C"
                    next: c
              b:
                speaker: "T"
                text: "B"
                choices:
                  - text: "Fin"
                    actions:
                      - type: CLOSE
              c:
                speaker: "T"
                text: "C"
                choices:
                  - text: "Retour à A"
                    next: a
              orphan:
                speaker: "T"
                text: "Plus personne ne vient ici"
                choices:
                  - text: "OK"
                    next: b
                  - text: "Fin"
                    actions:
                      - type: CLOSE
              loner:
                speaker: "T"
                text: "Seul"
                choices:
                  - text: "Encore"
                    next: loner
              gated:
                speaker: "T"
                text: "Après la quête"
                choices:
                  - text: "Fin"
                    actions:
                      - type: CLOSE
            """;

    private static DialogueDraft graph() {
        DialogueYaml.ReadResult r = DialogueYaml.read(GRAPH);
        assertTrue(r.ok(), () -> String.join("; ", r.problems()));
        return r.draft();
    }

    @Test
    void anIsolatedLineIsRemovedWithItsRepliesAndNothingElseMoves() {
        DialogueDraft d = graph();
        String before = DialogueYaml.write(graph());
        DialogueForm.Removal r = DialogueForm.removeOrphanNode(d, "orphan");
        assertTrue(r.allowed());
        assertTrue(d.nodes.stream().noneMatch(n -> n.id.equals("orphan")));
        assertEquals(List.of("start", "a", "b", "c", "loner", "gated"), d.nodes.stream().map(n -> n.id).toList(),
                "les identifiants des autres répliques ne changent pas");
        assertEquals("b", d.nodes.get(1).choices.get(0).next, "la cible de la réplique supprimée reste en place");
        assertTrue(DialogueValidator.validate(d).stream().noneMatch(x -> x.level() == Diagnostic.Level.ERROR),
                "aucune référence cassée");
        DialogueDraft expected = DialogueYaml.read(before).draft();
        expected.nodes.removeIf(n -> n.id.equals("orphan"));
        assertEquals(DialogueYaml.write(expected), DialogueYaml.write(d), "seule la réplique orpheline disparaît");
    }

    @Test
    void aReferencedLineIsRefusedAndEveryIncomingLinkIsListed() {
        DialogueDraft d = graph();
        DialogueForm.Removal r = DialogueForm.removeOrphanNode(d, "b");
        assertEquals(DialogueForm.RemovalBlock.REFERENCED, r.block());
        assertEquals(List.of(new DialogueForm.Incoming(DialogueForm.Incoming.Kind.CHOICE, "a", 0, -1),
                new DialogueForm.Incoming(DialogueForm.Incoming.Kind.CHOICE, "orphan", 0, -1)), r.incoming(),
                "toutes les réponses qui y mènent, même depuis une réplique elle-même inatteignable");
        assertEquals(7, d.nodes.size(), "rien n'a été retiré");
    }

    @Test
    void cyclesAndMultipleBranchesKeepTheirTargets() {
        DialogueDraft d = graph();
        assertEquals(DialogueForm.RemovalBlock.REFERENCED, DialogueForm.removal(d, "a").block(), "atteinte par start et par c");
        assertEquals(2, DialogueForm.incoming(d, "a").size());
        assertEquals(DialogueForm.RemovalBlock.REFERENCED, DialogueForm.removal(d, "c").block());
    }

    @Test
    void theStartLineIsNeverRemoved() {
        DialogueDraft d = graph();
        DialogueForm.Removal r = DialogueForm.removeOrphanNode(d, "start");
        assertEquals(DialogueForm.RemovalBlock.START, r.block());
        assertEquals(7, d.nodes.size());
    }

    @Test
    void aConditionalEntryIsAnIncomingLink() {
        DialogueDraft d = graph();
        DialogueForm.Removal r = DialogueForm.removeOrphanNode(d, "gated");
        assertEquals(DialogueForm.RemovalBlock.REFERENCED, r.block());
        assertEquals(DialogueForm.Incoming.Kind.ENTRY, r.incoming().get(0).kind());
        assertFalse(DialogueForm.unreachable(d).contains("gated"), "une entrée atteint sa réplique : pas de badge");
    }

    @Test
    void aLineThatOnlyPointsToItselfIsStillAnOrphan() {
        DialogueDraft d = graph();
        assertTrue(DialogueForm.removeOrphanNode(d, "loner").allowed(), "sa propre réponse part avec elle");
        assertTrue(DialogueValidator.validate(d).stream().noneMatch(x -> x.level() == Diagnostic.Level.ERROR));
    }

    @Test
    void unknownLinesAreReportedMissing() {
        assertEquals(DialogueForm.RemovalBlock.MISSING, DialogueForm.removal(graph(), "nope").block());
        assertEquals(DialogueForm.RemovalBlock.MISSING, DialogueForm.removal(graph(), "").block());
    }

    @Test
    void anEntryToAMissingLineIsAValidationError() {
        DialogueDraft d = graph();
        d.nodes.removeIf(n -> n.id.equals("gated"));
        assertTrue(DialogueValidator.validate(d).stream()
                .anyMatch(x -> x.level() == Diagnostic.Level.ERROR && x.field().equals("entries[0]")));
    }

    @Test
    void theFormKeepsEntriesAndVariantsItDoesNotShow() {
        DialogueDraft saved = graph();
        saved.nodes.get(1).variants.add("A bis");
        DialogueDraft fromForm = DialogueForm.parse(DialogueForm.fields(saved)).draft();
        DialogueForm.keepUnshown(fromForm, saved);
        assertEquals(DialogueYaml.write(saved), DialogueYaml.write(fromForm));
    }

    @Test
    void displayNumbersStartAtTheFirstLine() {
        DialogueDraft d = DialogueYaml.read(COMPLEX).draft();
        assertEquals(1, DialogueForm.number(d, "hello"));
        assertEquals(2, DialogueForm.number(d, "story"));
        assertEquals("Bonjour voyageur.", DialogueForm.excerpt(d.nodes.get(0).text, 40));
        assertTrue(DialogueForm.unreachable(d).isEmpty());
    }

    // ---- Issue #430 : « Proposer une quête au hasard dans une liste » -----------------------

    private static final String CATALOG = """
            id: rpgquest:brigand
            start: hello
            nodes:
              hello:
                speaker: "Brigand"
                text: "Salut."
                choices:
                  - text: "Du travail ?"
                    actions:
                      - type: OFFER_CATALOG_QUEST
                  - text: "Autre liste"
                    actions:
                      - type: SET_VARIABLE
                        key: "vu"
                        value: "1"
                      - type: OFFER_CATALOG_QUEST
                        catalog: brigands
                  - text: "Écrit à la main"
                    actions:
                      - type: OFFER_CATALOG_QUEST
                        catalog: ""
                  - text: "Commencer"
                    actions:
                      - type: START_QUEST
                        quest: rpgquest:spiders
                    next: hello
            """;

    @Test
    void aCatalogOfferIsReadAsTheSimpleChoiceAndComesBackIdentical() {
        DialogueYaml.ReadResult r = DialogueYaml.read(CATALOG);
        assertTrue(r.ok(), r.problems().toString());
        DialogueDraft d = r.draft();
        DialogueForm.Reply none = DialogueForm.reply(d.nodes.get(0).choices.get(0));
        assertEquals(DialogueForm.CATALOG_ACTION, none.questAction());
        assertEquals("", none.catalogId(), "vide = les listes du PNJ récurrent");
        DialogueForm.Reply named = DialogueForm.reply(d.nodes.get(0).choices.get(1));
        assertEquals("brigands", named.catalogId());
        assertEquals(1, named.otherEffects().size(), "la variable reste un autre effet");
        DialogueForm.Reply blank = DialogueForm.reply(d.nodes.get(0).choices.get(2));
        assertEquals("", blank.questAction(), "un catalog écrit vide reste tel quel dans les autres effets");
        assertEquals(DialogueYaml.write(d), roundTrip(d), "rien ne bouge sans modification");
    }

    @Test
    void switchingAPreciseQuestToAListKeepsItsPlaceAndDropsTheQuest() {
        DialogueDraft d = DialogueYaml.read(CATALOG).draft();
        Map<String, String> f = new java.util.LinkedHashMap<>(DialogueForm.fields(d));
        f.put(DialogueForm.reply(0, 3, "qa"), DialogueForm.CATALOG_ACTION);
        f.put(DialogueForm.reply(0, 3, "qcat"), "brigands");
        DialogueDraft.Choice c = DialogueForm.parse(f).draft().nodes.get(0).choices.get(3);
        assertEquals(1, c.actions.size());
        assertEquals(Map.of("type", "OFFER_CATALOG_QUEST", "catalog", "brigands"), c.actions.get(0));
        assertEquals("hello", c.next, "la suite ne change pas");

        f.put(DialogueForm.reply(0, 3, "qcat"), "");
        c = DialogueForm.parse(f).draft().nodes.get(0).choices.get(3);
        assertEquals(Map.of("type", "OFFER_CATALOG_QUEST"), c.actions.get(0), "liste vide = pas de clé catalog");

        // Et retour : la quête précise revient, sans clé catalogue héritée.
        Map<String, String> g = new java.util.LinkedHashMap<>(DialogueForm.fields(d));
        g.put(DialogueForm.reply(0, 1, "qa"), "START_QUEST");
        g.put(DialogueForm.reply(0, 1, "qid"), "rpgquest:spiders");
        DialogueDraft.Choice back = DialogueForm.parse(g).draft().nodes.get(0).choices.get(1);
        assertEquals("SET_VARIABLE", back.actions.get(0).get("type"));
        assertEquals(Map.of("type", "START_QUEST", "quest", "rpgquest:spiders"), back.actions.get(1));
    }
}
