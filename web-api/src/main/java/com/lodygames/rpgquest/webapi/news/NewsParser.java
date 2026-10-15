package com.lodygames.rpgquest.webapi.news;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lit un fichier d'actualité (#56) : un en-tête {@code clé: valeur}, une ligne {@code ---}, puis le texte.
 *
 * <pre>
 * title: Le portail ouvre ses portes
 * summary: Classements et profils arrivent bientôt.
 * date: 2026-10-16T18:00:00+02:00
 * status: published          # published | draft
 * category: announcement     # news | announcement | maintenance | event | release | story
 * author: L'équipe LodyQuests  # facultatif
 * tags: portail, nouveautés    # facultatif
 * ---
 * Premier paragraphe…
 * </pre>
 *
 * <p>Validation stricte : clé inconnue, clé obligatoire absente, date sans fuseau, valeur trop longue
 * ou statut inconnu → fichier refusé entier. Le texte n'est jamais interprété comme du HTML.</p>
 */
public final class NewsParser {

    /** Nom de fichier sans extension : devient l'URL {@code /actualites/<slug>}. */
    public static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    static final int MAX_SLUG = 80;
    static final int MAX_TITLE = 120;
    static final int MAX_SUMMARY = 300;
    static final int MAX_AUTHOR = 60;
    static final int MAX_TAGS = 8;
    static final int MAX_TAG = 30;
    static final int MAX_BODY = 20_000;

    private static final Set<String> KEYS = Set.of("title", "summary", "date", "status", "category", "author", "tags");
    private static final Set<String> REQUIRED = Set.of("title", "summary", "date", "status", "category");

    private NewsParser() {
    }

    public static NewsArticle parse(String slug, String content) throws NewsFormatException {
        if (slug.length() > MAX_SLUG || !SLUG.matcher(slug).matches()) {
            throw new NewsFormatException("nom de fichier invalide (minuscules, chiffres et tirets, "
                    + MAX_SLUG + " caractères au plus)");
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("﻿")) {
            normalized = normalized.substring(1);
        }
        int separator = separatorIndex(normalized);
        if (separator < 0) {
            throw new NewsFormatException("ligne « --- » absente entre l'en-tête et le texte");
        }
        Map<String, String> header = header(normalized.substring(0, separator));
        int separatorEnd = normalized.indexOf('\n', separator);
        String body = separatorEnd < 0 ? "" : normalized.substring(separatorEnd + 1).strip();
        if (body.length() > MAX_BODY) {
            throw new NewsFormatException("texte trop long (" + MAX_BODY + " caractères au plus)");
        }
        for (String key : REQUIRED) {
            if (!header.containsKey(key) || header.get(key).isEmpty()) {
                throw new NewsFormatException("clé obligatoire absente : " + key);
            }
        }
        return new NewsArticle(slug,
                bounded(header, "title", MAX_TITLE),
                bounded(header, "summary", MAX_SUMMARY),
                body,
                date(header.get("date")),
                header.containsKey("author") ? bounded(header, "author", MAX_AUTHOR) : "",
                status(header.get("status")),
                NewsCategory.fromId(header.get("category")).orElseThrow(
                        () -> new NewsFormatException("catégorie inconnue : news, announcement, maintenance, event, "
                                + "release ou story")),
                tags(header.getOrDefault("tags", "")));
    }

    private static int separatorIndex(String content) {
        int lineStart = 0;
        while (lineStart <= content.length()) {
            int lineEnd = content.indexOf('\n', lineStart);
            String line = content.substring(lineStart, lineEnd < 0 ? content.length() : lineEnd);
            if (line.strip().equals("---")) {
                return lineStart;
            }
            if (lineEnd < 0) {
                return -1;
            }
            lineStart = lineEnd + 1;
        }
        return -1;
    }

    private static Map<String, String> header(String text) throws NewsFormatException {
        Map<String, String> header = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : text.split("\n", -1)) {
            lineNumber++;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                throw new NewsFormatException("ligne " + lineNumber + " de l'en-tête : « clé: valeur » attendu");
            }
            String key = trimmed.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            if (!KEYS.contains(key)) {
                throw new NewsFormatException("clé inconnue ligne " + lineNumber + " : " + key);
            }
            if (header.containsKey(key)) {
                throw new NewsFormatException("clé en double : " + key);
            }
            header.put(key, unquote(withoutComment(trimmed.substring(colon + 1)).strip()));
        }
        return header;
    }

    /** Un commentaire de fin de ligne commence par « # » précédé d'une espace (un titre peut contenir « #1 »). */
    private static String withoutComment(String value) {
        int index = value.indexOf(" #");
        return index < 0 ? value : value.substring(0, index);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    private static String bounded(Map<String, String> header, String key, int max) throws NewsFormatException {
        String value = header.get(key);
        if (value.length() > max) {
            throw new NewsFormatException(key + " trop long (" + max + " caractères au plus)");
        }
        return value;
    }

    private static Instant date(String raw) throws NewsFormatException {
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            throw new NewsFormatException("date invalide : format 2026-10-16T18:00:00+02:00 (fuseau obligatoire)");
        }
    }

    private static boolean status(String raw) throws NewsFormatException {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "published" -> true;
            case "draft" -> false;
            default -> throw new NewsFormatException("status doit valoir published ou draft");
        };
    }

    private static List<String> tags(String raw) throws NewsFormatException {
        List<String> tags = new ArrayList<>();
        for (String part : raw.split(",")) {
            String tag = part.strip();
            if (tag.isEmpty()) {
                continue;
            }
            if (tag.length() > MAX_TAG) {
                throw new NewsFormatException("tag trop long (" + MAX_TAG + " caractères au plus)");
            }
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }
        if (tags.size() > MAX_TAGS) {
            throw new NewsFormatException("trop de tags (" + MAX_TAGS + " au plus)");
        }
        return tags;
    }
}
