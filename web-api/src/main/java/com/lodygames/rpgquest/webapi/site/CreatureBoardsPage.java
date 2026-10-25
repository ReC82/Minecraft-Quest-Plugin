package com.lodygames.rpgquest.webapi.site;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /classements/creatures} — un classement par type de créature tuée (#734, priorité du
 * 25/10/2026), servi par {@link LeaderboardsPageHandler} (et sous {@code /demo/classements/creatures}
 * avec les joueurs fictifs).
 *
 * <p>Lit les classements {@code split: entity} de {@code statistics.boards}, déjà filtrés par
 * {@code PublicPlayerData}. Seuls les types déjà vaincus depuis le début du suivi sont publiés par le
 * plugin : la page ne liste jamais des centaines de classements vides. Filtres sans JavaScript :
 * période ({@code ?periode=semaine}), catégorie ({@code ?type=hostile}) et recherche par nom
 * ({@code ?q=zomb}). Les types rencontrés mais sans joueur sur la période sont résumés en une ligne.</p>
 */
final class CreatureBoardsPage {

    static final String SUFFIX = "/creatures";
    /** Unité des lignes : « 1 créature », « 12 créatures » ({@link StatLabels#unit}). */
    static final String UNIT_METRIC = "CREATURE_KILL";
    private static final int QUERY_MAX = 40;

    /** Catégories publiées par le plugin, dans l'ordre d'affichage, avec leur libellé. */
    static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("boss", "Boss");
        CATEGORIES.put("special", "Mobs spéciaux");
        CATEGORIES.put("hostile", "Hostiles");
        CATEGORIES.put("passive", "Non hostiles");
    }

    private CreatureBoardsPage() {
    }

    static String path(SiteRoutes routes) {
        return routes.boards() + SUFFIX;
    }

    /** Classements découpés par type de créature de l'instantané filtré. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> splitBoards(Map<String, Object> statistics) {
        List<Map<String, Object>> boards = new ArrayList<>();
        if (statistics.get("boards") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> board && "entity".equals(board.get("split"))) {
                    boards.add((Map<String, Object>) board);
                }
            }
        }
        return boards;
    }

    /**
     * Corps de la page.
     *
     * @param periodSlug période demandée ({@code semaine}…), vide = la première publiée
     * @param category   catégorie demandée ({@code hostile}…), vide = toutes
     * @param query      texte cherché dans le nom des créatures, vide = aucun
     */
    @SuppressWarnings("unchecked")
    static String render(Map<String, Object> statistics, SiteRoutes routes, String periodSlug, String category,
                         String query) {
        StringBuilder html = new StringBuilder(routes.banner());
        html.append("<p>Un classement pour chaque créature vaincue : zombies, squelettes, araignées… Un mob "
                + "spécial RPGQuest a son propre classement, distinct de sa créature de base.</p>");
        html.append("<p><a href=\"").append(BoardCatalogPage.path(routes)).append("\">← Tous les classements</a></p>");
        List<Map<String, Object>> boards = splitBoards(statistics);
        if (boards.isEmpty()) {
            html.append(PortalIcons.empty("skull", "Aucun classement par créature publié pour le moment."));
            return html.toString();
        }
        String period = BoardCatalogPage.periodFromSlug(periodSlug);
        Map<String, Object> board = boards.stream().filter(b -> period.equals(b.get("period"))).findFirst()
                .orElse(boards.get(0));
        String selectedPeriod = String.valueOf(board.get("period"));
        String slug = StatLabels.BOARD_PERIODS.getOrDefault(selectedPeriod, new String[] {"", ""})[0];
        String search = query.length() > QUERY_MAX ? query.substring(0, QUERY_MAX) : query;
        List<Map<String, Object>> targets = board.get("targets") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();

        html.append(periodTabs(boards, routes, selectedPeriod, category, search));
        html.append(categoryTabs(targets, routes, slug, category, search));
        html.append(searchForm(routes, slug, category, search));
        html.append("<p class=\"note\">").append(Html.escape(when(board, selectedPeriod))).append("</p>");
        String status = String.valueOf(board.get("status"));
        if (!"ACTIVE".equals(status) && !"ENDED".equals(status)) {
            html.append(PortalIcons.empty("trophy", "Ce classement n'est pas ouvert pour le moment."));
            return html.append(rules()).toString();
        }

        List<Map<String, Object>> matching = targets.stream()
                .filter(target -> category.isEmpty() || category.equals(target.get("category")))
                .filter(target -> search.isEmpty() || matches(target, search))
                .toList();
        List<Map<String, Object>> withRows = matching.stream().filter(CreatureBoardsPage::hasRows).toList();
        List<Map<String, Object>> quiet = matching.stream().filter(target -> !hasRows(target)).toList();
        int size = board.get("size") instanceof Number number ? number.intValue() : LeaderboardsPageHandler.TOP;
        Map<String, Map<String, Object>> previous = previousTargets(board);

        if (targets.isEmpty()) {
            html.append(PortalIcons.empty("skull", "Aucune créature vaincue depuis le début du suivi."));
        } else if (matching.isEmpty()) {
            html.append(PortalIcons.empty("skull", "Aucune créature ne correspond à ce choix."));
        } else if (withRows.isEmpty()) {
            html.append(PortalIcons.empty("skull", "Personne au classement sur cette période pour ce choix."));
        } else {
            html.append("<div class=\"grid boards\">");
            for (Map<String, Object> target : withRows) {
                html.append(card(target, size, routes, selectedPeriod, previous.get(String.valueOf(target.get("key")))));
            }
            html.append("</div>");
        }
        if (!quiet.isEmpty()) {
            List<String> names = quiet.stream().map(target -> String.valueOf(target.get("label"))).toList();
            html.append("<p class=\"note\">Déjà vaincus, mais personne sur cette période : ")
                    .append(Html.escape(String.join(", ", names))).append(".</p>");
        }
        return html.append(rules()).toString();
    }

    /** Recherche insensible à la casse et aux accents, sur le nom et la créature de base. */
    static boolean matches(Map<String, Object> target, String query) {
        String wanted = fold(query);
        return fold(String.valueOf(target.get("label"))).contains(wanted)
                || target.get("base") instanceof String base && fold(base).contains(wanted);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).trim();
    }

    private static boolean hasRows(Map<String, Object> target) {
        return target.get("leaderboard") instanceof List<?> rows && !rows.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static String card(Map<String, Object> target, int size, SiteRoutes routes, String period,
                               Map<String, Object> previous) {
        String key = String.valueOf(target.get("key"));
        String category = String.valueOf(target.get("category"));
        StringBuilder html = new StringBuilder("<section class=\"card\" id=\"creature-")
                .append(Html.escape(anchor(key))).append("\">")
                .append(PortalIcons.heading("h2", "boss".equals(category) || "special".equals(category) ? "boss" : "skull",
                        String.valueOf(target.get("label"))));
        html.append("<p class=\"note\">").append(Html.escape(CATEGORIES.getOrDefault(category, "Créature")));
        if (target.get("base") instanceof String base) {
            html.append(" · variante de ").append(Html.escape(base));
        }
        String since = StatLabels.date(target.get("trackedSince") instanceof String text ? text : null);
        if (!since.isEmpty()) {
            html.append(" · suivi depuis le ").append(Html.escape(since));
        }
        html.append("</p>");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) target.get("leaderboard");
        html.append(BoardView.render(rows, UNIT_METRIC, size, routes));
        if (previous != null && hasRows(previous)) {
            html.append("<details><summary>").append(Html.escape(StatLabels.previousLabel(period))).append("</summary>")
                    .append(BoardView.render((List<Map<String, Object>>) previous.get("leaderboard"), UNIT_METRIC,
                            BoardView.PODIUM, routes))
                    .append("</details>");
        }
        return html.append("</section>").toString();
    }

    /** Ancre HTML stable d'un type : {@code special:rpgquest:roi} → {@code special-rpgquest-roi}. */
    static String anchor(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> previousTargets(Map<String, Object> board) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        if (board.get("previous") instanceof Map<?, ?> previous && previous.get("targets") instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> target = (Map<String, Object>) item;
                byKey.put(String.valueOf(target.get("key")), target);
            }
        }
        return byKey;
    }

    private static String when(Map<String, Object> board, String period) {
        String[] label = StatLabels.BOARD_PERIODS.getOrDefault(period, new String[] {"", "Période"});
        if (board.get("window") instanceof Map<?, ?> window && window.get("start") instanceof String start
                && window.get("end") instanceof String end) {
            String bounds = StatLabels.window(start, end);
            return bounds.isEmpty() ? label[1] : label[1] + " — " + bounds;
        }
        return label[1];
    }

    private static String periodTabs(List<Map<String, Object>> boards, SiteRoutes routes, String selected,
                                     String category, String search) {
        if (boards.size() < 2) {
            return "";
        }
        StringBuilder html = new StringBuilder("<p class=\"filters-label\" id=\"creature-periode-label\">Période</p>"
                + "<nav aria-labelledby=\"creature-periode-label\"><ul class=\"tabs\">");
        for (Map<String, Object> board : boards) {
            String period = String.valueOf(board.get("period"));
            String[] label = StatLabels.BOARD_PERIODS.getOrDefault(period, new String[] {"", period});
            html.append(tab(href(routes, label[0], category, search), label[1], period.equals(selected)));
        }
        return html.append("</ul></nav>").toString();
    }

    private static String categoryTabs(List<Map<String, Object>> targets, SiteRoutes routes, String slug,
                                       String selected, String search) {
        List<String> present = CATEGORIES.keySet().stream()
                .filter(category -> targets.stream().anyMatch(target -> category.equals(target.get("category"))))
                .toList();
        if (present.size() < 2 && selected.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<p class=\"filters-label\" id=\"creature-type-label\">Catégorie</p>"
                + "<nav aria-labelledby=\"creature-type-label\"><ul class=\"tabs\">");
        html.append(tab(href(routes, slug, "", search), "Toutes (" + targets.size() + ")", selected.isEmpty()));
        for (String category : present) {
            long count = targets.stream().filter(target -> category.equals(target.get("category"))).count();
            html.append(tab(href(routes, slug, category, search), CATEGORIES.get(category) + " (" + count + ")",
                    category.equals(selected)));
        }
        return html.append("</ul></nav>").toString();
    }

    private static String searchForm(SiteRoutes routes, String slug, String category, String search) {
        StringBuilder html = new StringBuilder("<form method=\"get\" action=\"").append(path(routes))
                .append("\" role=\"search\">");
        if (!slug.isEmpty()) {
            html.append("<input type=\"hidden\" name=\"periode\" value=\"").append(Html.escape(slug)).append("\">");
        }
        if (!category.isEmpty()) {
            html.append("<input type=\"hidden\" name=\"type\" value=\"").append(Html.escape(category)).append("\">");
        }
        return html.append("<label for=\"creature-q\">Chercher une créature</label> <input id=\"creature-q\" ")
                .append("name=\"q\" type=\"search\" maxlength=\"").append(QUERY_MAX)
                .append("\" autocomplete=\"off\" placeholder=\"zombie, araignée…\" value=\"")
                .append(Html.escape(search)).append("\"> <button type=\"submit\">Chercher</button></form>").toString();
    }

    private static String tab(String href, String label, boolean current) {
        return "<li><a href=\"" + href + "\"" + (current ? " aria-current=\"page\"" : "") + ">"
                + Html.escape(label) + "</a></li>";
    }

    private static String href(SiteRoutes routes, String slug, String category, String search) {
        List<String> params = new ArrayList<>();
        if (!slug.isEmpty()) {
            params.add("periode=" + slug);
        }
        if (!category.isEmpty()) {
            params.add("type=" + category);
        }
        if (!search.isEmpty()) {
            params.add("q=" + Html.escape(java.net.URLEncoder.encode(search, java.nio.charset.StandardCharsets.UTF_8)));
        }
        return path(routes) + (params.isEmpty() ? "" : "?" + String.join("&amp;", params));
    }

    /** Ce qui compte, pour que personne ne cherche un score qui n'existe pas. */
    private static String rules() {
        return "<section class=\"card\">" + PortalIcons.heading("h2", "unknown", "Ce qui est compté")
                + "<ul><li>Une créature tuée par un joueur en survie ou en aventure, avec sa date réelle.</li>"
                + "<li>Pas comptés : créatures de spawner ou issues d'une division, mobs de test, joueurs (PvP), "
                + "mode créatif et mode administrateur.</li>"
                + "<li>Seules les créatures déjà vaincues depuis le début du suivi ont un classement ; les autres "
                + "apparaîtront dès leur premier combat. Aucun score n'est reconstitué pour le passé.</li>"
                + "<li>Seuls les joueurs qui affichent leur profil figurent dans les classements.</li></ul></section>";
    }
}
