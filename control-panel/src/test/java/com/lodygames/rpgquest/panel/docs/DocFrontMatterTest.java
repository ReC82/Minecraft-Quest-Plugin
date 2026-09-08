package com.lodygames.rpgquest.panel.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocFrontMatterTest {

    @Test
    void parsesInlineFrontMatter() {
        String raw = """
                ---
                title: Taguer un PNJ
                category: PNJ / Citizens
                tags: [citizens, NPC, tag]
                order: 2
                ---
                # Contenu

                Corps.
                """;
        DocFrontMatter.Parsed p = DocFrontMatter.parse(raw);
        assertEquals("Taguer un PNJ", p.meta().title());
        assertEquals("PNJ / Citizens", p.meta().category());
        assertEquals(java.util.List.of("citizens", "npc", "tag"), p.meta().tags());
        assertEquals(2, p.meta().order());
        assertTrue(p.body().startsWith("# Contenu"));
        assertTrue(p.body().contains("Corps."));
    }

    @Test
    void parsesListStyleTags() {
        String raw = """
                ---
                title: X
                tags:
                  - alpha
                  - beta
                ---
                body
                """;
        DocFrontMatter.Parsed p = DocFrontMatter.parse(raw);
        assertEquals(java.util.List.of("alpha", "beta"), p.meta().tags());
    }

    @Test
    void markdownWithoutFrontMatterKeepsFullBody() {
        DocFrontMatter.Parsed p = DocFrontMatter.parse("# Titre\n\ntexte");
        assertEquals(null, p.meta().title());
        assertEquals(1_000, p.meta().order());
        assertTrue(p.meta().tags().isEmpty());
        assertEquals("# Titre\n\ntexte", p.body());
    }

    @Test
    void unterminatedFrontMatterIsIgnoredRatherThanLosingContent() {
        DocFrontMatter.Parsed p = DocFrontMatter.parse("---\ntitle: X\n# pas de fermeture\ncorps");
        assertTrue(p.body().contains("corps"));
    }

    @Test
    void stripsQuotesAndIgnoresUnknownKeys() {
        DocFrontMatter.Parsed p = DocFrontMatter.parse("---\ntitle: \"Avec guillemets\"\nweird: nope\n---\nx");
        assertEquals("Avec guillemets", p.meta().title());
    }
}
