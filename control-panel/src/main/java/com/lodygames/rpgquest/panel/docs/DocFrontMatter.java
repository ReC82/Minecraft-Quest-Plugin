package com.lodygames.rpgquest.panel.docs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parseur minimal du <em>front matter</em> d'un document Markdown du centre de documentation
 * (issue #49). Volontairement pauvre — aucune dépendance YAML : seules les clés {@code title},
 * {@code category}, {@code tags}, {@code order} sont reconnues. Un Markdown <strong>sans</strong>
 * bloc front matter reste valide ({@link #parse} renvoie des métadonnées vides + le corps entier).
 *
 * <p>Format attendu :</p>
 * <pre>
 * ---
 * title: Créer et configurer un PNJ
 * category: PNJ / Citizens
 * tags: [citizens, npc, tag]
 * order: 1
 * ---
 * # Contenu Markdown…
 * </pre>
 * {@code tags} accepte aussi la forme liste sur plusieurs lignes ({@code - citizens}).
 */
public final class DocFrontMatter {

    /** Métadonnées extraites. Champs absents → {@code null} / liste vide / {@code order == 1_000}. */
    public record Meta(String title, String category, List<String> tags, int order) {
        public Meta {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** Résultat : métadonnées + corps Markdown (sans le bloc front matter). */
    public record Parsed(Meta meta, String body) {
    }

    private static final int DEFAULT_ORDER = 1_000;

    private DocFrontMatter() {
    }

    public static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        if (!text.startsWith("---\n")) {
            return new Parsed(new Meta(null, null, List.of(), DEFAULT_ORDER), text.strip());
        }
        int end = text.indexOf("\n---", 4);
        if (end < 0) {
            // Bloc ouvert mais jamais fermé : on ignore le front matter plutôt que de tout perdre.
            return new Parsed(new Meta(null, null, List.of(), DEFAULT_ORDER), text.strip());
        }
        String block = text.substring(4, end);
        int bodyStart = text.indexOf('\n', end + 1);
        String body = bodyStart < 0 ? "" : text.substring(bodyStart + 1);

        String title = null;
        String category = null;
        List<String> tags = new ArrayList<>();
        int order = DEFAULT_ORDER;

        String pendingListKey = null;
        for (String line : block.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (pendingListKey != null && trimmed.startsWith("- ")) {
                String value = clean(trimmed.substring(2));
                if ("tags".equals(pendingListKey) && !value.isEmpty()) {
                    tags.add(value.toLowerCase(Locale.ROOT));
                }
                continue;
            }
            pendingListKey = null;
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = clean(line.substring(colon + 1));
            switch (key) {
                case "title" -> title = value.isEmpty() ? null : value;
                case "category" -> category = value.isEmpty() ? null : value;
                case "order" -> {
                    try {
                        order = Integer.parseInt(value.strip());
                    } catch (NumberFormatException ignored) {
                        // garde la valeur par défaut
                    }
                }
                case "tags" -> {
                    if (value.isEmpty()) {
                        pendingListKey = "tags"; // liste sur les lignes suivantes
                    } else {
                        for (String t : value.replace("[", "").replace("]", "").split(",")) {
                            String tag = clean(t).toLowerCase(Locale.ROOT);
                            if (!tag.isEmpty()) {
                                tags.add(tag);
                            }
                        }
                    }
                }
                default -> {
                    // clé non reconnue : ignorée sans erreur
                }
            }
        }
        return new Parsed(new Meta(title, category, tags, order), body.strip());
    }

    /** Retire espaces, guillemets simples/doubles encadrants. */
    private static String clean(String value) {
        String v = value.strip();
        if (v.length() >= 2 && ((v.charAt(0) == '"' && v.endsWith("\""))
                || (v.charAt(0) == '\'' && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1).strip();
        }
        return v;
    }
}
