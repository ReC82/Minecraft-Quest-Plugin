package com.lodygames.rpgquest.webapi.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Format des fichiers d'actualité (#56) : en-tête strict, texte libre jamais interprété. */
class NewsParserTest {

    static final String VALID = """
            # Commentaire ignoré
            title: Le portail ouvre
            summary: "Classements et profils arrivent bientôt."
            date: 2026-10-16T18:00:00+02:00
            status: published   # ou draft
            category: announcement
            author: L'équipe
            tags: portail, nouveautés, portail
            ---
            Premier paragraphe.

            ---
            Le second séparateur fait partie du texte.
            """;

    @Test
    void aValidFileGivesEveryField() throws Exception {
        NewsArticle article = NewsParser.parse("portail-ouvre", VALID);

        assertEquals("portail-ouvre", article.slug());
        assertEquals("Le portail ouvre", article.title());
        assertEquals("Classements et profils arrivent bientôt.", article.summary());
        assertEquals(Instant.parse("2026-10-16T16:00:00Z"), article.publishedAt());
        assertTrue(article.published());
        assertEquals(NewsCategory.ANNOUNCEMENT, article.category());
        assertEquals("L'équipe", article.author());
        assertEquals(List.of("portail", "nouveautés"), article.tags(), "tags dédoublonnés");
        assertTrue(article.body().startsWith("Premier paragraphe."));
        assertTrue(article.body().contains("Le second séparateur"));
    }

    @Test
    void aDraftIsNeverVisible() throws Exception {
        NewsArticle draft = NewsParser.parse("brouillon", VALID.replace("status: published", "status: draft"));

        assertFalse(draft.visibleAt(Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void aPublishedArticleWaitsForItsDate() throws Exception {
        NewsArticle article = NewsParser.parse("programmee", VALID);

        assertFalse(article.visibleAt(Instant.parse("2026-10-16T15:59:59Z")));
        assertTrue(article.visibleAt(Instant.parse("2026-10-16T16:00:00Z")));
    }

    @Test
    void windowsLineEndingsAndBomAreAccepted() throws Exception {
        NewsArticle article = NewsParser.parse("windows", "﻿" + VALID.replace("\n", "\r\n"));

        assertEquals("Le portail ouvre", article.title());
    }

    @Test
    void invalidFilesAreRejectedWithAReadableReason() {
        assertReason("ligne « --- »", "a", VALID.replace("---\n", ""));
        assertReason("clé inconnue", "a", "image: x.png\n" + VALID);
        assertReason("clé en double", "a", "title: Autre\n" + VALID);
        assertReason("clé obligatoire absente : summary", "a", VALID.replace("summary:", "# summary:"));
        assertReason("fuseau obligatoire", "a", VALID.replace("+02:00", ""));
        assertReason("published ou draft", "a", VALID.replace("status: published", "status: publie"));
        assertReason("catégorie inconnue", "a", VALID.replace("announcement", "annonce"));
        assertReason("title trop long", "a", VALID.replace("Le portail ouvre", "x".repeat(121)));
        assertReason("nom de fichier invalide", "Mon_Article", VALID);
        assertReason("nom de fichier invalide", "../secret", VALID);
    }

    @Test
    void theBundledExampleIsAValidDraft() throws Exception {
        String content = java.nio.file.Files.readString(java.nio.file.Path.of("../scripts/web-portal/news/bienvenue.md"));
        NewsArticle example = NewsParser.parse("bienvenue", content);

        assertFalse(example.published(), "l'exemple installé par install.sh ne doit jamais se publier seul");
    }

    private static void assertReason(String expected, String slug, String content) {
        NewsFormatException error = assertThrows(NewsFormatException.class, () -> NewsParser.parse(slug, content));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }
}
