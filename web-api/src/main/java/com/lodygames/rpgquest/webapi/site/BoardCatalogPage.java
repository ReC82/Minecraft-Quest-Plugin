package com.lodygames.rpgquest.webapi.site;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /classements/tous} — le catalogue complet des classements (#734), servi par
 * {@link LeaderboardsPageHandler} (et sous {@code /demo/classements/tous} avec les joueurs fictifs).
 *
 * <p>Lit {@code statistics.boards} et {@code statistics.catalog} déjà filtrés par
 * {@code PublicPlayerData} : filtres par période ({@code ?periode=semaine}) et par thème
 * ({@code ?categorie=combat}), une carte par classement avec son podium, ses bornes exactes dans le
 * fuseau de la communauté, la provenance (« suivi depuis… ») et la période précédente figée. Les
 * mesures que le serveur ne suit pas encore sont listées comme telles, sans aucune valeur.</p>
 */
final class BoardCatalogPage {

    static final String SUFFIX = "/tous";

    private BoardCatalogPage() {
    }

    static String path(SiteRoutes routes) {
        return routes.boards() + SUFFIX;
    }

    /** Corps de la page ; {@code periodSlug}/{@code category} déjà lus de l'adresse (vides = tout). */
    @SuppressWarnings("unchecked")
    static String render(Map<String, Object> statistics, SiteRoutes routes, String periodSlug, String category) {
        List<Map<String, Object>> every = statistics.get("boards") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        // Les classements par type de créature (#734) ont leur propre page, avec recherche.
        List<Map<String, Object>> all = every.stream().filter(board -> board.get("split") == null).toList();
        boolean creatures = every.size() != all.size();
        Map<String, Object> catalog = statistics.get("catalog") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        String period = periodFromSlug(periodSlug);
        StringBuilder html = new StringBuilder(routes.banner());
        html.append("<p>Tous les classements du serveur, par période et par thème. Chaque classement indique "
                + "ses dates exactes (heure de Bruxelles) et depuis quand sa mesure est suivie : aucun score "
                + "n'est reconstitué avant cette date.</p>");
        html.append("<p><a href=\"").append(routes.boards()).append("\">← Classements principaux</a></p>");
        if (creatures) {
            html.append("<p><a class=\"button\" href=\"").append(CreatureBoardsPage.path(routes))
                    .append("\">Classements par créature : zombies, squelettes, araignées, boss…</a></p>");
        }
        if (all.isEmpty()) {
            html.append(PortalIcons.empty("trophy", "Aucun classement publié pour le moment."));
            return html.append(planned(catalog)).toString();
        }
        html.append(periodTabs(all, routes, period, category));
        html.append(categoryTabs(all, routes, period, category));
        List<Map<String, Object>> shown = all.stream()
                .filter(board -> period.isEmpty() || period.equals(board.get("period")))
                .filter(board -> category.isEmpty() || category.equals(board.get("category")))
                .toList();
        if (shown.isEmpty()) {
            html.append(PortalIcons.empty("trophy", "Aucun classement pour ce choix."));
        } else {
            html.append("<div class=\"grid boards\">");
            for (Map<String, Object> board : shown) {
                html.append(card(board, catalog, routes));
            }
            html.append("</div>");
        }
        return html.append(planned(catalog)).toString();
    }

    /** Identifiant d'adresse ({@code semaine}) → nom de période de l'instantané ({@code weekly}) ; vide si inconnu. */
    static String periodFromSlug(String slug) {
        for (Map.Entry<String, String[]> entry : StatLabels.BOARD_PERIODS.entrySet()) {
            if (entry.getValue()[0].equals(slug)) {
                return entry.getKey();
            }
        }
        return "";
    }

    private static String periodTabs(List<Map<String, Object>> boards, SiteRoutes routes, String selected,
                                     String category) {
        StringBuilder html = new StringBuilder("<p class=\"filters-label\" id=\"cat-periode-label\">Période</p>"
                + "<nav aria-labelledby=\"cat-periode-label\"><ul class=\"tabs\">");
        html.append(tab(href(routes, "", category), "Toutes", selected.isEmpty()));
        StatLabels.BOARD_PERIODS.forEach((period, label) -> {
            if (boards.stream().anyMatch(board -> period.equals(board.get("period")))) {
                html.append(tab(href(routes, label[0], category), label[1], period.equals(selected)));
            }
        });
        return html.append("</ul></nav>").toString();
    }

    private static String categoryTabs(List<Map<String, Object>> boards, SiteRoutes routes, String period,
                                       String selected) {
        String slug = period.isEmpty() ? "" : StatLabels.BOARD_PERIODS.get(period)[0];
        StringBuilder html = new StringBuilder("<p class=\"filters-label\" id=\"cat-theme-label\">Thème</p>"
                + "<nav aria-labelledby=\"cat-theme-label\"><ul class=\"tabs\">");
        html.append(tab(href(routes, slug, ""), "Tout", selected.isEmpty()));
        StatLabels.CATEGORIES.forEach((category, label) -> {
            if (boards.stream().anyMatch(board -> category.equals(board.get("category")))) {
                html.append("<li><a href=\"").append(href(routes, slug, category)).append('"')
                        .append(category.equals(selected) ? " aria-current=\"page\"" : "").append('>')
                        .append(PortalIcons.icon(label[1], 16)).append(' ').append(Html.escape(label[0]))
                        .append("</a></li>");
            }
        });
        return html.append("</ul></nav>").toString();
    }

    private static String tab(String href, String label, boolean current) {
        return "<li><a href=\"" + href + "\"" + (current ? " aria-current=\"page\"" : "") + ">"
                + Html.escape(label) + "</a></li>";
    }

    private static String href(SiteRoutes routes, String periodSlug, String category) {
        List<String> params = new ArrayList<>();
        if (!periodSlug.isEmpty()) {
            params.add("periode=" + periodSlug);
        }
        if (!category.isEmpty()) {
            params.add("categorie=" + category);
        }
        return path(routes) + (params.isEmpty() ? "" : "?" + String.join("&amp;", params));
    }

    @SuppressWarnings("unchecked")
    static String card(Map<String, Object> board, Map<String, Object> catalog, SiteRoutes routes) {
        String metric = String.valueOf(board.get("metric"));
        String icon = board.get("icon") instanceof String name ? name : PortalIcons.forMetric(metric);
        int size = board.get("size") instanceof Number number ? number.intValue() : LeaderboardsPageHandler.TOP;
        String period = String.valueOf(board.get("period"));
        StringBuilder html = new StringBuilder("<section class=\"card\" id=\"classement-")
                .append(Html.escape(String.valueOf(board.get("id")))).append("\">")
                .append(PortalIcons.heading("h2", icon, String.valueOf(board.get("name"))));
        if (board.get("description") instanceof String description && !description.isBlank()) {
            html.append("<p>").append(Html.escape(description)).append("</p>");
        }
        html.append("<p class=\"note\">").append(Html.escape(when(board, period)));
        String targets = targets(board, catalog);
        if (!targets.isEmpty()) {
            html.append(" · ").append(Html.escape(targets));
        }
        html.append("</p>");
        String status = String.valueOf(board.get("status"));
        if ("NO_SEASON".equals(status)) {
            html.append(PortalIcons.empty("trophy", "Aucune saison en cours : ce classement ouvrira avec la prochaine."));
        } else if ("NO_EVENT".equals(status)) {
            html.append(PortalIcons.empty("trophy", "Cet événement n'est pas programmé pour le moment."));
        } else if ("SCHEDULED".equals(status)) {
            html.append(PortalIcons.empty("trophy", "Pas encore commencé."));
        } else {
            List<Map<String, Object>> rows = board.get("leaderboard") instanceof List<?> list
                    ? (List<Map<String, Object>>) list : List.of();
            html.append(BoardView.render(rows, metric, size, routes));
        }
        html.append("<p class=\"note\">").append(Html.escape(provenance(board))).append("</p>");
        if (board.get("previous") instanceof Map<?, ?> previous) {
            html.append(previous((Map<String, Object>) previous, metric, period, routes));
        }
        return html.append("</section>").toString();
    }

    /** « Cette semaine — du 19 octobre 2026 au 25 octobre 2026 », « Depuis toujours »… */
    private static String when(Map<String, Object> board, String period) {
        String[] label = StatLabels.BOARD_PERIODS.getOrDefault(period, new String[] {"", "Période"});
        if (board.get("window") instanceof Map<?, ?> window && window.get("start") instanceof String start
                && window.get("end") instanceof String end) {
            String bounds = StatLabels.window(start, end);
            return bounds.isEmpty() ? label[1] : label[1] + " — " + bounds;
        }
        return label[1];
    }

    /** Cibles écrites dans le filtre, avec le libellé des familles quand il est connu. */
    @SuppressWarnings("unchecked")
    private static String targets(Map<String, Object> board, Map<String, Object> catalog) {
        if (!(board.get("filter") instanceof Map<?, ?> filter)) {
            return "";
        }
        Map<String, String> familyLabels = new LinkedHashMap<>();
        if (catalog.get("families") instanceof Map<?, ?> families) {
            for (Object list : families.values()) {
                for (Object item : (List<Object>) list) {
                    Map<String, Object> family = (Map<String, Object>) item;
                    familyLabels.putIfAbsent(String.valueOf(family.get("id")), String.valueOf(family.get("label")));
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<?, ?> entry : filter.entrySet()) {
            boolean family = String.valueOf(entry.getKey()).endsWith("-family");
            for (Object value : (List<Object>) entry.getValue()) {
                String text = String.valueOf(value);
                names.add(family ? familyLabels.getOrDefault(text, text) : StatLabels.constant(text));
            }
        }
        return names.isEmpty() ? "" : "Cibles : " + String.join(", ", names);
    }

    /** Provenance du score : depuis quand la mesure est suivie, jamais une date inventée. */
    private static String provenance(Map<String, Object> board) {
        if ("QUESTS_COMPLETED".equals(board.get("metric"))) {
            return "Toutes les quêtes terminées depuis l'ouverture du serveur.";
        }
        String since = StatLabels.date(board.get("trackedSince") instanceof String text ? text : null);
        return since.isEmpty() ? "Mesure prête, pas encore de score enregistré."
                : "Mesure suivie depuis le " + since + " : rien n'est compté avant.";
    }

    @SuppressWarnings("unchecked")
    private static String previous(Map<String, Object> previous, String metric, String period, SiteRoutes routes) {
        List<Map<String, Object>> rows = previous.get("leaderboard") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        String bounds = previous.get("start") instanceof String start && previous.get("end") instanceof String end
                ? StatLabels.window(start, end) : "";
        return "<details><summary>" + Html.escape(StatLabels.previousLabel(period))
                + (bounds.isEmpty() ? "" : " (" + Html.escape(bounds) + ")") + "</summary>"
                + BoardView.render(rows, metric, BoardView.PODIUM, routes) + "</details>";
    }

    /** Mesures demandées que le serveur ne suit pas encore : affichées comme indisponibles, sans valeur. */
    @SuppressWarnings("unchecked")
    private static String planned(Map<String, Object> catalog) {
        if (!(catalog.get("planned") instanceof List<?> planned) || planned.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<section class=\"card\">")
                .append(PortalIcons.heading("h2", "unknown", "Mesures pas encore suivies"))
                .append("<p>Ces classements arriveront quand le serveur saura les mesurer de façon fiable. "
                        + "Aucun score ne sera reconstitué pour le passé.</p><ul>");
        for (Object item : planned) {
            Map<String, Object> entry = (Map<String, Object>) item;
            html.append("<li>").append(Html.escape(String.valueOf(entry.get("reason")))).append("</li>");
        }
        return html.append("</ul></section>").toString();
    }
}
