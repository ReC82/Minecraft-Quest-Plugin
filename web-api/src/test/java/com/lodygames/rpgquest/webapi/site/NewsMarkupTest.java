package com.lodygames.rpgquest.webapi.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Mise en forme des actualités (#56) : rien du fichier n'atteint la page sans échappement. */
class NewsMarkupTest {

    @Test
    void paragraphsHeadingsListsAndBold() {
        String html = NewsMarkup.render("## Nouveau\n\nLigne 1\nligne **2**\n\n- un\n- `/quests`");

        assertEquals("<h2>Nouveau</h2><p>Ligne 1<br>ligne <strong>2</strong></p><ul><li>un</li><li><code>/quests</code></li></ul>",
                html);
    }

    @Test
    void htmlIsEscapedNeverInterpreted() {
        String html = NewsMarkup.render("<script>alert(1)</script> <img src=x onerror=alert(1)>");

        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void onlyHttpsAndSiteRelativeLinksBecomeLinks() {
        assertTrue(NewsMarkup.inline("[règles](/infos)").contains("<a href=\"/infos\">règles</a>"));
        assertTrue(NewsMarkup.inline("[doc](https://example.org/a?b=1&c=2)")
                .contains("href=\"https://example.org/a?b=1&amp;c=2\" rel=\"noopener nofollow\""));
        for (String unsafe : new String[] {"[x](javascript:alert(1))", "[x](//evil.example)", "[x](/\\evil.example)",
                "[x](http://example.org)", "[x](data:text/html,a)", "[x](https://e.org\" onclick=\"a)"}) {
            assertFalse(NewsMarkup.inline(unsafe).contains("<a "), unsafe + " → " + NewsMarkup.inline(unsafe));
        }
    }
}
