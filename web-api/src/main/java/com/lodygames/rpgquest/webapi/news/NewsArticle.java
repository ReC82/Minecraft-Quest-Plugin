package com.lodygames.rpgquest.webapi.news;

import java.time.Instant;
import java.util.List;

/**
 * Une actualité du portail (#56), lue depuis un fichier du dossier {@code news-dir}.
 *
 * @param slug        identifiant stable, nom du fichier sans {@code .md}, utilisé dans l'URL
 * @param body        texte brut ; mis en forme et échappé à l'affichage (jamais de HTML accepté)
 * @param publishedAt date de publication ; une actualité datée dans le futur reste invisible jusque-là
 * @param author      signature affichée, vide si absente
 */
public record NewsArticle(
        String slug,
        String title,
        String summary,
        String body,
        Instant publishedAt,
        String author,
        boolean published,
        NewsCategory category,
        List<String> tags
) {

    public NewsArticle {
        tags = List.copyOf(tags);
    }

    /** Visible du public à {@code now} : publiée et date atteinte. Un brouillon ne l'est jamais. */
    public boolean visibleAt(Instant now) {
        return published && !publishedAt.isAfter(now);
    }
}
