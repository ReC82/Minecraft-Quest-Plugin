package com.lodygames.rpgquest.panel.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Issue #780 : listes imbriquées, suites de ligne, liens dans les suites, ancres et contrôle de structure. */
class MarkdownStructureTest {

    /** Le motif exact de l'ancienne page « Faire parler un personnage » qui s'affichait cassé. */
    private static final String THREE_LEVELS = String.join("\n",
            "1. **Le bandeau** : enregistré ou non (voir",
            "   [Enregistrer](#enregistrer-puis-mettre-en-jeu)).",
            "2. **Une carte par réplique.** Dans chaque carte :",
            "   - **Le personnage dit…** : sa phrase (voir",
            "     [Aller à la ligne](#aller-a-la-ligne-dans-une-replique)) ;",
            "   - **Le joueur peut répondre…** : pour chacune :",
            "     - **Ensuite…** : *Terminer*, ou une autre",
            "       réplique [détails](dialogues-depannage#x) ;",
            "     - **Supprimer cette réponse**.",
            "   - **Réglages de la réplique** (replié) : le nom, et **Retirer",
            "     cette réplique**.",
            "3. **Ajouter une réplique**.");

    @Test
    void threeLevelListKeepsEachContinuationAndLinkInItsOwnItem() {
        String html = Markdown.render(THREE_LEVELS);
        assertTrue(html.startsWith("<ol><li><strong>Le bandeau</strong> : enregistré ou non (voir "
                + "<a href=\"#enregistrer-puis-mettre-en-jeu\">Enregistrer</a>).</li>"), html);
        assertTrue(html.contains("<li><strong>Le personnage dit…</strong> : sa phrase (voir "
                + "<a href=\"#aller-a-la-ligne-dans-une-replique\">Aller à la ligne</a>) ;</li>"), html);
        // 3e niveau : vraie sous-liste, et la suite (avec son lien) reste dans « Ensuite… »
        assertTrue(html.contains("pour chacune :<ul><li><strong>Ensuite…</strong> : <em>Terminer</em>, ou une autre "
                + "réplique <a href=\"/docs/dialogues-depannage#x\">détails</a> ;</li>"
                + "<li><strong>Supprimer cette réponse</strong>.</li></ul></li>"), html);
        // la suite après la sous-liste revient au bon élément de niveau 2
        assertTrue(html.contains("<li><strong>Réglages de la réplique</strong> (replié) : le nom, et <strong>Retirer "
                + "cette réplique</strong>.</li></ul></li><li><strong>Ajouter une réplique</strong>.</li></ol>"), html);
        assertEquals(1, count(html, "<ol>"));
        assertEquals(2, count(html, "<ul>"));
    }

    @Test
    void textAfterANestedListBecomesAParagraphOfTheParentItem() {
        String html = Markdown.render("- parent\n  - enfant\n  suite du parent\n- deux");
        assertEquals("<ul><li>parent<ul><li>enfant</li></ul><p>suite du parent</p></li><li>deux</li></ul>", html);
    }

    @Test
    void looseListWithBlankLinesStaysOneListAndKeepsNumbering() {
        String html = Markdown.render("1. un\n\n   détail\n\n2. deux");
        assertEquals("<ol><li>un<p>détail</p></li><li>deux</li></ol>", html);
        assertTrue(Markdown.render("3. trois\n4. quatre").startsWith("<ol start=\"3\">"));
    }

    @Test
    void unindentedContinuationJoinsTheItemInsteadOfBreakingTheSentence() {
        assertEquals("<ul><li>une phrase coupée en deux</li></ul>", Markdown.render("- une phrase\ncoupée en deux"));
        assertTrue(Markdown.lint("- une phrase\ncoupée en deux").get(0).contains("non indentée"));
    }

    @Test
    void bulletStartingWithANumberKeepsTheNumber() {
        assertEquals("<ul><li>2024. une année</li></ul>", Markdown.render("- 2024. une année"));
    }

    @Test
    void calloutContentIsRenderedLikeTheRestIncludingLists() {
        String html = Markdown.render("> [!WARNING]\n> Avant :\n> - un\n> - deux");
        assertTrue(html.contains("<div class=\"doc-callout doc-warn\"><span class=\"doc-callout-h\">Attention</span>"
                + "<p>Avant :</p><ul><li>un</li><li>deux</li></ul></div>"), html);
    }

    @Test
    void linkToAnotherSheetByItsIdIsRewrittenUnsafeOnesStillDropped() {
        assertTrue(Markdown.render("[x](dialogues-depannage)").contains("<a href=\"/docs/dialogues-depannage\">x</a>"));
        assertTrue(Markdown.render("[x](quetes#une-section)").contains("<a href=\"/docs/quetes#une-section\">x</a>"));
        for (String bad : List.of("javascript:alert(1)", "../secret", "fiche.md", "Fiche", "//evil.example",
                "http://example.com", "data:text/html,x")) {
            String html = Markdown.render("[x](" + bad + ")");
            assertFalse(html.contains("href="), bad + " -> " + html);
        }
    }

    @Test
    void headingsExposeTheRenderedIdsDuplicatesAreNumberedFirstUnchanged() {
        String md = "## L'aperçu\n## Vérification\ntexte\n## Vérification\n### Code `x`";
        String html = Markdown.render(md);
        List<Markdown.Heading> hs = Markdown.headings(md);
        assertEquals(List.of("lapercu", "verification", "verification-2", "code-x"),
                hs.stream().map(Markdown.Heading::id).toList());
        assertEquals("L'aperçu", hs.get(0).text());
        assertEquals("Code x", hs.get(3).text());
        Matcher m = Pattern.compile(" id=\"([^\"]+)\"").matcher(html);
        for (Markdown.Heading h : hs) {
            assertTrue(m.find());
            assertEquals(h.id(), m.group(1));
        }
    }

    @Test
    void lintAcceptsWellFormedSheets() {
        assertEquals(List.of(), Markdown.lint(THREE_LEVELS));
        assertEquals(List.of(), Markdown.lint("Intro.\n\n## A\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```\n<br>\n```\n"));
    }

    @Test
    void lintRefusesWhatTheEngineCannotRenderCleanly() {
        assertProblem("```\ncode", "jamais refermé");
        assertProblem("- a\n  ```\n  x\n  ```", "bloc de code indenté");
        assertProblem("- a\n  - b\n    - c\n      - d", "4 niveaux");
        assertProblem("- a\n - b", "un seul espace");
        assertProblem("| a | b |\n|---|---|\n| 1 | 2 | 3 |", "3 cellules au lieu de 2");
        assertProblem("## A\n#### B", "un niveau a été sauté");
        assertProblem("Texte <br> ici", "balise HTML");
        assertProblem("[x](../secret)", "non autorisé");
        assertProblem("  - liste indentée", "commence indentée");
        // un paramètre entre chevrons n'est pas du HTML
        assertEquals(List.of(), Markdown.lint("Tapez /wild <joueur>."));
    }

    private static void assertProblem(String md, String expected) {
        List<String> problems = Markdown.lint(md);
        assertTrue(problems.stream().anyMatch(p -> p.contains(expected)), md + " -> " + problems);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int at = s.indexOf(needle); at >= 0; at = s.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
