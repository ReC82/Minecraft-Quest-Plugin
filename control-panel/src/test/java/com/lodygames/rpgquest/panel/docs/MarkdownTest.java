package com.lodygames.rpgquest.panel.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkdownTest {

    @Test
    void headingsGetAnchorIds() {
        String html = Markdown.render("## Mettre un skin");
        assertTrue(html.contains("<h2 id=\"mettre-un-skin\">Mettre un skin</h2>"), html);
    }

    @Test
    void listsAndInlineFormatting() {
        String html = Markdown.render("- un **gras**\n- deux `code`\n");
        assertTrue(html.contains("<ul><li>un <strong>gras</strong></li>"), html);
        assertTrue(html.contains("<li>deux <code>code</code></li></ul>"), html);
    }

    @Test
    void orderedListAndNesting() {
        String html = Markdown.render("1. premier\n2. second\n   - sous a\n");
        assertTrue(html.contains("<ol><li>premier</li>"), html);
        assertTrue(html.contains("<li>second<ul><li>sous a</li></ul></li></ol>"), html);
    }

    @Test
    void simpleTable() {
        String html = Markdown.render("| Cmd | Effet |\n|---|---|\n| `/npc select` | choisit |\n");
        assertTrue(html.contains("<table><thead><tr><th>Cmd</th><th>Effet</th></tr></thead>"), html);
        assertTrue(html.contains("<td><code>/npc select</code></td><td>choisit</td>"), html);
    }

    @Test
    void fencedCodeBlockGetsCopyButtonAndIsEscaped() {
        String html = Markdown.render("```\n/rpgadmin npc tag guard\n```");
        assertTrue(html.contains("class=\"doc-cmd\""), html);
        assertTrue(html.contains("data-copy=\"/rpgadmin npc tag guard\""), html);
        assertTrue(html.contains("<pre><code>/rpgadmin npc tag guard</code></pre>"), html);
    }

    @Test
    void calloutFromBlockquote() {
        String html = Markdown.render("> [!WARNING]\n> Ne jamais faire ça.");
        assertTrue(html.contains("doc-callout doc-warn"), html);
        assertTrue(html.contains("Ne jamais faire ça."), html);
    }

    @Test
    void internalLinksKeptExternalHttpsKeptOthersDropped() {
        assertTrue(Markdown.render("[fiche](/docs/pnj-citizens)").contains("<a href=\"/docs/pnj-citizens\">fiche</a>"));
        assertTrue(Markdown.render("[ext](https://example.com/x)").contains("<a href=\"https://example.com/x\">ext</a>"));
        // lien non sûr : seul le libellé subsiste, jamais l'URL
        String js = Markdown.render("[clique](javascript:alert(1))");
        assertTrue(js.contains("clique"), js);
        assertFalse(js.toLowerCase().contains("javascript:"), js);
        assertFalse(js.contains("href="), js);
        String rel = Markdown.render("[cfg](../../etc/passwd)");
        assertFalse(rel.contains("href="), rel);
        assertFalse(rel.contains(".."), rel);
    }

    @Test
    void rawHtmlAndScriptTagsAreNeverPassedThrough() {
        String html = Markdown.render("Bonjour <script>alert('xss')</script> <img src=x onerror=alert(1)> fin");
        // Aucune vraie balise n'atteint la sortie : tout est neutralisé en entités.
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("<img"), html);
        assertFalse(html.contains("<script>alert"), html);
        assertTrue(html.contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"), html);
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
    }

    @Test
    void inlineCodeContentIsLiteralNotReparsed() {
        String html = Markdown.render("voir `**pas gras**` et `<b>pas de balise</b>`");
        assertTrue(html.contains("<code>**pas gras**</code>"), html);
        assertTrue(html.contains("<code>&lt;b&gt;pas de balise&lt;/b&gt;</code>"), html);
        assertFalse(html.contains("<strong>pas gras"), html);
    }

    @Test
    void codeBlockWithHtmlAndQuotesIsSafeInAttributeAndBody() {
        String html = Markdown.render("```\n<script>a=\"b\"</script>\n```");
        assertFalse(html.contains("<script>a="), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
        assertTrue(html.contains("data-copy=\"&lt;script&gt;a=&quot;b&quot;&lt;/script&gt;\""), html);
    }
}
