package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Nettoyage / interprétation MiniMessage pour l'affichage web (issue #74). */
class MiniTextTest {

    @Test
    void plainStripsEveryTag() {
        assertEquals("La chasse aux cristaux", MiniText.plain("<gold>La chasse aux cristaux</gold>"));
        assertEquals("[TEST] Histoire de test", MiniText.plain("<red>[TEST]</red> Histoire de test"));
        assertEquals("x", MiniText.plain("<gradient:red:blue><bold>x</bold></gradient>"));
        assertEquals("", MiniText.plain(null));
    }

    @Test
    void htmlNeverLeavesRawTags() {
        for (String raw : new String[] {
                "<gold>La chasse aux cristaux</gold>",
                "<red>[TEST]</red> Histoire de test",
                "<gradient:#ff0000:#00ff00>arc</gradient>",
                "<hover:show_text:'x'><click:run_command:/op>piège</click></hover>",
                "<lang:some.key>",
                "<b><i>gras italique</i></b> suite"}) {
            String html = MiniText.html(raw);
            assertFalse(html.contains("<gold>") || html.contains("<red>") || html.contains("<gradient")
                    || html.contains("<hover") || html.contains("<click") || html.contains("<lang"),
                    "balise MiniMessage brute dans: " + html);
        }
    }

    @Test
    void htmlInterpretsSafeColourAndDecorationSubset() {
        String html = MiniText.html("<gold>La chasse aux cristaux</gold>");
        assertTrue(html.contains("<span style=\"color:"), "couleur nommée interprétée : " + html);
        assertTrue(html.contains("La chasse aux cristaux"));
        assertTrue(html.endsWith("</span>"));

        assertTrue(MiniText.html("<bold>x</bold>").contains("<b>x</b>"));
        assertTrue(MiniText.html("<italic>x</italic>").contains("<i>x</i>"));
        assertTrue(MiniText.html("<#4c8dff>bleu</#4c8dff>").contains("color:#4c8dff"));
    }

    @Test
    void htmlEscapesTextContentSoItCannotInjectMarkup() {
        String html = MiniText.html("<green>a & b < c > d \" ' </green><script>alert(1)</script>");
        assertFalse(html.toLowerCase().contains("<script"), html);
        assertTrue(html.contains("&amp;") && html.contains("&lt;") && html.contains("&gt;"));
    }

    @Test
    void htmlClosesUnbalancedTags() {
        String html = MiniText.html("<gold>jamais fermé");
        assertEquals(1, countOccurrences(html, "<span"));
        assertEquals(1, countOccurrences(html, "</span>"));
    }

    @Test
    void prettifyIdMakesTechnicalKeysReadable() {
        assertEquals("Crystal Hunt", MiniText.prettifyId("rpgquest:crystal_hunt"));
        assertEquals("Hunt Spiders", MiniText.prettifyId("hunt_spiders"));
        assertEquals("Amethyst Shard", MiniText.prettifyId("AMETHYST_SHARD"));
        assertEquals("Main Story", MiniText.prettifyId("main_story"));
    }

    @Test
    void prettifyTokensOnlyTouchesScreamingCaseTokens() {
        assertEquals("Tuer Spider (x5)", MiniText.prettifyTokens("Tuer SPIDER (x5)"));
        assertEquals("Collecter Amethyst Shard (x2)", MiniText.prettifyTokens("Collecter AMETHYST_SHARD (x2)"));
        assertEquals("+100 XP", MiniText.prettifyTokens("+100 XP"));            // court / connu : inchangé
        assertEquals("parler à guard", MiniText.prettifyTokens("parler à guard")); // minuscules : inchangé
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
