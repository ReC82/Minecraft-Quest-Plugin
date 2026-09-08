package com.lodygames.rpgquest.panel.docs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recherche plein texte <strong>en mémoire</strong> sur la bibliothèque de fiches (issue #49).
 * Pas de moteur externe : un index inversé simple, reconstruit à partir de {@link DocLibrary}.
 * Portée : titre, catégorie, tags, commandes de la page, et corps Markdown (débarrassé de sa
 * syntaxe). Un résultat n'est retenu que si <strong>chaque</strong> mot de la requête apparaît
 * quelque part dans la page (ET logique) ; le classement pondère titre &gt; tag/catégorie &gt;
 * commande &gt; corps.
 */
public final class DocSearchIndex {

    /** Un résultat de recherche : la fiche + un extrait contextualisé + un score. */
    public record Hit(DocPage page, String snippet, int score) {
    }

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    private static final Pattern MD_NOISE = Pattern.compile("[#>*_`|\\-\\[\\]()]+");

    /** Bonus <em>plats</em> par présence d'un terme dans un champ : la présence prime sur la fréquence. */
    private static final int B_TITLE = 25;
    private static final int B_TAG = 12;
    private static final int B_CATEGORY = 7;
    private static final int B_COMMAND = 5;
    /** Le corps contribue peu et est plafonné : un long document ne doit pas gagner par volume. */
    private static final int BODY_CAP = 5;

    private final List<Entry> entries;

    private DocSearchIndex(List<Entry> entries) {
        this.entries = entries;
    }

    public static DocSearchIndex build(DocLibrary library) {
        List<Entry> entries = new ArrayList<>();
        for (DocPage page : library.all()) {
            String plainBody = MD_NOISE.matcher(page.markdown()).replaceAll(" ");
            entries.add(new Entry(
                    page,
                    counts(page.title()),
                    counts(String.join(" ", page.tags())),
                    counts(page.category()),
                    counts(String.join(" ", page.commands())),
                    counts(plainBody),
                    plainBody));
        }
        return new DocSearchIndex(entries);
    }

    /**
     * Recherche {@code query}. Requête vide → liste vide. Sinon, fiches contenant tous les mots,
     * classées par score décroissant puis titre.
     */
    public List<Hit> search(String query) {
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Entry e : entries) {
            boolean allPresent = true;
            int score = 0;
            for (String term : terms) {
                int termScore = 0;
                if (matchCount(e.title, term) > 0) {
                    termScore += B_TITLE;
                }
                if (matchCount(e.tags, term) > 0) {
                    termScore += B_TAG;
                }
                if (matchCount(e.category, term) > 0) {
                    termScore += B_CATEGORY;
                }
                if (matchCount(e.commands, term) > 0) {
                    termScore += B_COMMAND;
                }
                termScore += Math.min(matchCount(e.body, term), BODY_CAP);
                if (termScore == 0) {
                    allPresent = false;
                    break;
                }
                score += termScore;
            }
            if (allPresent) {
                hits.add(new Hit(e.page, snippet(e.plainBody, terms), score));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparingInt(h -> h.page().order())
                .thenComparing(h -> h.page().title().toLowerCase(Locale.ROOT)));
        return hits;
    }

    // ---- interne -----------------------------------------------------------------------

    private record Entry(DocPage page, Map<String, Integer> title, Map<String, Integer> tags,
                         Map<String, Integer> category, Map<String, Integer> commands,
                         Map<String, Integer> body, String plainBody) {
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        var m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String tok = m.group();
            if (tok.length() >= 2 || tok.matches("[a-z]")) {
                tokens.add(tok);
            }
        }
        return tokens;
    }

    private static Map<String, Integer> counts(String text) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String tok : tokenize(text)) {
            map.merge(tok, 1, Integer::sum);
        }
        return map;
    }

    /** Correspondance exacte OU préfixe (« citizen » trouve « citizens »). */
    private static int matchCount(Map<String, Integer> field, String term) {
        Integer exact = field.get(term);
        int total = exact == null ? 0 : exact;
        if (term.length() >= 4) {
            for (var e : field.entrySet()) {
                if (!e.getKey().equals(term) && e.getKey().startsWith(term)) {
                    total += e.getValue();
                }
            }
        }
        return total;
    }

    /** Extrait ~28 mots autour de la première occurrence d'un terme dans le corps. */
    static String snippet(String plainBody, List<String> terms) {
        String collapsed = plainBody.replaceAll("\\s+", " ").strip();
        String lower = collapsed.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String term : terms) {
            int p = lower.indexOf(term);
            if (p >= 0 && (at < 0 || p < at)) {
                at = p;
            }
        }
        if (collapsed.isEmpty()) {
            return "";
        }
        if (at < 0) {
            return trim(collapsed, 220);
        }
        int start = Math.max(0, collapsed.lastIndexOf(' ', Math.max(0, at - 90)));
        int end = Math.min(collapsed.length(), collapsed.indexOf(' ', Math.min(collapsed.length() - 1, at + 130)));
        if (end <= start) {
            end = Math.min(collapsed.length(), start + 220);
        }
        String frag = collapsed.substring(start, end).strip();
        return (start > 0 ? "… " : "") + frag + (end < collapsed.length() ? " …" : "");
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).strip() + " …";
    }
}
