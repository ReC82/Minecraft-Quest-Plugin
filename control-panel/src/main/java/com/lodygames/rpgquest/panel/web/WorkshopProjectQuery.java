package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.building.BuildingProject;
import com.lodygames.rpgquest.panel.building.ProjectUsage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Recherche, filtre et tri de la liste des projets de l'Atelier (issue #881) — tout côté serveur, par
 * des paramètres d'URL : la liste reste utilisable sans JavaScript, et un lien partagé redonne la même vue.
 *
 * @param text   texte cherché dans le nom, l'identifiant du bâtiment, la description et l'identifiant du
 *               projet — casse et accents ignorés
 * @param filter un des {@link Filter}
 * @param sort   un des {@link Sort}
 */
public record WorkshopProjectQuery(String text, Filter filter, Sort sort) {

    public enum Filter {
        ALL("tous", "Tous", r -> true),
        DRAFTS("brouillons", "Brouillons", r -> r.usage().draftOnly()),
        LIBRARY("bibliotheque", "En bibliothèque", r -> r.usage().catalog().inLibrary()),
        USED("utilises", "Utilisés en jeu", r -> r.usage().usage() == ProjectUsage.Usage.USED),
        UNUSED("non_utilises", "Non utilisés", r -> r.usage().usage() == ProjectUsage.Usage.UNUSED),
        UNCERTAIN("a_verifier", "Utilisation à vérifier", r -> r.usage().usage() == ProjectUsage.Usage.UNCERTAIN),
        TO_FIX("a_corriger", "À corriger", r -> "À corriger".equals(r.state())
                || "Enregistrement échoué".equals(r.state()));

        private final String key;
        private final String label;
        private final Predicate<WorkshopPages.ProjectRow> test;

        Filter(String key, String label, Predicate<WorkshopPages.ProjectRow> test) {
            this.key = key;
            this.label = label;
            this.test = test;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        static Filter parse(String raw) {
            for (Filter f : values()) {
                if (f.key.equals(raw)) {
                    return f;
                }
            }
            return ALL;
        }
    }

    public enum Sort {
        RECENT("recents", "Plus récents"),
        OLDEST("anciens", "Plus anciens"),
        NAME("nom", "Nom (A → Z)");

        private final String key;
        private final String label;

        Sort(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        static Sort parse(String raw) {
            for (Sort s : values()) {
                if (s.key.equals(raw)) {
                    return s;
                }
            }
            return RECENT;
        }
    }

    /** Borne du texte cherché : au-delà, c'est une saisie accidentelle, pas une recherche. */
    static final int MAX_TEXT = 120;

    public WorkshopProjectQuery {
        text = text == null ? "" : text.strip();
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        filter = filter == null ? Filter.ALL : filter;
        sort = sort == null ? Sort.RECENT : sort;
    }

    public static WorkshopProjectQuery from(Map<String, String> q) {
        return new WorkshopProjectQuery(q.get("q"), Filter.parse(q.getOrDefault("filtre", "")),
                Sort.parse(q.getOrDefault("tri", "")));
    }

    public boolean narrowed() {
        return !text.isEmpty() || filter != Filter.ALL;
    }

    public List<WorkshopPages.ProjectRow> apply(List<WorkshopPages.ProjectRow> rows) {
        String wanted = fold(text);
        return rows.stream()
                .filter(filter.test)
                .filter(r -> wanted.isEmpty() || haystack(r.project()).contains(wanted))
                .sorted(comparator())
                .toList();
    }

    /** Combien de projets chaque filtre retiendrait avec la recherche en cours. */
    public int count(List<WorkshopPages.ProjectRow> rows, Filter f) {
        return new WorkshopProjectQuery(text, f, sort).apply(rows).size();
    }

    private Comparator<WorkshopPages.ProjectRow> comparator() {
        Comparator<WorkshopPages.ProjectRow> byUpdate = Comparator.comparing(r -> r.project().updatedAt());
        return switch (sort) {
            case RECENT -> byUpdate.reversed();
            case OLDEST -> byUpdate;
            case NAME -> Comparator.comparing((WorkshopPages.ProjectRow r) -> fold(r.project().name()))
                    .thenComparing(byUpdate.reversed());
        };
    }

    private static String haystack(BuildingProject p) {
        return fold(p.name() + "\n" + p.buildingId() + "\n" + p.brief() + "\n" + p.id());
    }

    /** Casse, accents et espaces multiples ignorés : « Église » = « eglise ». */
    static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** L'adresse de la liste avec ces réglages, un seul changé. */
    public String url(Filter f) {
        return url(text, f, sort);
    }

    static String url(String text, Filter f, Sort s) {
        StringBuilder sb = new StringBuilder(WorkshopPages.BASE);
        String sep = "?";
        if (!text.isEmpty()) {
            sb.append(sep).append("q=").append(URLEncoder.encode(text, StandardCharsets.UTF_8));
            sep = "&";
        }
        if (f != Filter.ALL) {
            sb.append(sep).append("filtre=").append(f.key());
            sep = "&";
        }
        if (s != Sort.RECENT) {
            sb.append(sep).append("tri=").append(s.key());
        }
        return sb.toString();
    }
}
