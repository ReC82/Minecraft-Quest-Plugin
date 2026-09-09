package com.lodygames.rpgquest.panel.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void copyButtonIsCompactTextInsideTheCodeWrapperWithNoBrokenIcon() {
        String html = Markdown.render("```\n/npc create Garde --type player\n```");
        // bouton = texte seul « Copier », DANS le wrapper .doc-cmd, juste avant <pre>
        assertTrue(html.startsWith("<div class=\"doc-cmd\"><button type=\"button\" class=\"doc-copy\" data-copy=\""), html);
        assertTrue(html.contains("\">Copier</button><pre><code>"), html);
        // plus aucun SVG / référence de sprite morte (cause du grand rectangle sombre vide)
        assertFalse(html.contains("<svg"), html);
        assertFalse(html.contains("#i-copy") || html.contains("<use "), html);
    }

    @Test
    void leadingH1IsStrippedButHeadingsAndSecondH1AreKept() {
        // Le centre de doc rend déjà le titre de fiche dans son propre <h1> : le # d'ouverture ferait doublon.
        String html = Markdown.render("# Résoudre les problèmes de PNJ\n\nTexte.\n\n## Une section\n\ncontenu");
        assertFalse(html.contains("<h1"), html);
        assertTrue(html.contains("<h2 id=\"une-section\">Une section</h2>"), html);
        assertTrue(html.contains("<p>Texte.</p>"), html);

        // rien à retirer si le corps ne commence pas par un H1
        assertTrue(Markdown.render("## Direct\n\nx").contains("<h2 id=\"direct\">Direct</h2>"));
        // un H1 plus bas dans le document n'est PAS retiré
        String two = Markdown.render("# Titre\n\ntexte\n\n# Autre titre\n\nx");
        assertFalse(two.contains(">Titre<"), two);
        assertTrue(two.contains("<h1 id=\"autre-titre\">Autre titre</h1>"), two);
    }

    @Test
    void stripLeadingH1IsNullSafeAndConservative() {
        assertEquals("", Markdown.stripLeadingH1(null));
        assertEquals("   ", Markdown.stripLeadingH1("   "));
        // pas un vrai ATX H1 (pas d'espace) -> intact
        assertTrue(Markdown.stripLeadingH1("#pas-un-titre\nx").startsWith("#pas-un-titre"));
        // H2 en tête -> intact
        assertTrue(Markdown.stripLeadingH1("## sous-titre\nx").startsWith("## sous-titre"));
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
