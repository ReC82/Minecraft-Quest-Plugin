package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.ServerStatus;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.QueryParams;
import com.lodygames.rpgquest.webapi.privacy.PublicPlayerData;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code GET /classements} (alias historique {@code /leaderboards}) — classements publics (#56).
 *
 * <p>Lit uniquement le modèle de statistiques #54, filtré par {@link PublicPlayerData} : onglet
 * « Depuis toujours », puis la saison et les événements quand {@code statistics.yml} en définit
 * ({@code ?periode=<id>}). Top {@value #TOP} par métrique, rang venu du plugin (ex aequo compris),
 * portée et fraîcheur affichées. Un instantané v1 garde l'ancien classement d'XP.</p>
 *
 * <p>Refonte (#56) : podium par classement, filtre par thème ({@code ?categorie=combat}, voir
 * {@link StatLabels#category}) combinable avec la période. Servie aussi sous {@code /demo/classements}
 * avec les joueurs fictifs de {@link DemoData} ({@link SiteRoutes#DEMO}) : même rendu, bandeau,
 * {@code noindex} ; l'état du serveur affiché reste toujours le vrai.</p>
 */
public final class LeaderboardsPageHandler implements ApiHandler {

    static final int TOP = 10;
    private static final Pattern PERIOD_ID = Pattern.compile("[a-z0-9_]{1,64}");

    private final SnapshotStore store;
    private final Site site;
    private final SiteRoutes routes;

    public LeaderboardsPageHandler(SnapshotStore store, Site site) {
        this(store, site, SiteRoutes.LIVE);
    }

    public LeaderboardsPageHandler(SnapshotStore store, Site site, SiteRoutes routes) {
        this.store = store;
        this.site = site;
        this.routes = routes;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        if (BoardCatalogPage.path(routes).equals(exchange.getRequestURI().getPath())) {
            return catalog(exchange);
        }
        if (CreatureBoardsPage.path(routes).equals(exchange.getRequestURI().getPath())) {
            return creatures(exchange);
        }
        if (!routes.boards().equals(exchange.getRequestURI().getPath()) && !"/leaderboards".equals(exchange.getRequestURI().getPath())) {
            return send(exchange, 404, ServerStatus.of(ServerState.resolve(store)), "<p>Cette page n'existe pas.</p>");
        }
        Map<String, Object> live = ServerState.resolve(store);
        ServerStatus serverStatus = ServerStatus.of(live);
        Map<String, Object> state = routes.demo() ? DemoData.state(Instant.now()) : live;
        Map<String, Object> statistics = PublicPlayerData.statistics(state.get("statistics"));
        Map<String, String> query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
        String requested = query.getOrDefault("periode", "");
        String category = StatLabels.CATEGORIES.containsKey(query.getOrDefault("categorie", ""))
                ? query.get("categorie") : "";

        List<Map<String, Object>> periods = periods(statistics);
        Map<String, Object> period = null;
        if (!requested.isEmpty()) {
            period = PERIOD_ID.matcher(requested).matches() ? find(periods, requested) : null;
            if (period == null) {
                return send(exchange, 404, serverStatus, "<p>Ce classement n'existe pas ou n'est plus publié.</p>"
                        + "<p><a href=\"" + routes.boards() + "\">Voir les classements</a></p>");
            }
        }

        StringBuilder body = new StringBuilder(routes.banner()).append(routes.demo() ? "" : unavailable(state))
                .append(tabs(periods, period, category));
        Map<String, List<Map<String, Object>>> boards;
        if (period == null) {
            body.append(lifetimeIntro());
            boards = boards(statistics.get("lifetime"));
            if (boards.isEmpty()) {
                boards = legacyBoards(state.get("leaderboards"));
            }
        } else {
            String blocked = periodIntro(body, period);
            boards = blocked == null ? boards(period.get("leaderboards")) : null;
            if (blocked != null) {
                body.append(blocked);
            }
        }
        if (boards != null) {
            body.append(categories(boards, period, category));
            body.append(cards(filter(boards, category)));
        }
        if (statistics.get("boards") instanceof List<?> catalog && !catalog.isEmpty()) {
            body.append("<p><a class=\"button\" href=\"").append(BoardCatalogPage.path(routes))
                    .append("\">Voir tous les classements : jour, semaine, mois, monstres, blocs, exploration…</a></p>");
        }
        if (!CreatureBoardsPage.splitBoards(statistics).isEmpty()) {
            body.append("<p><a class=\"button\" href=\"").append(CreatureBoardsPage.path(routes))
                    .append("\">Classements par créature : zombies, squelettes, araignées…</a></p>");
        }
        body.append(routes.demo() ? "" : freshness(state));
        return send(exchange, 200, serverStatus, body.toString());
    }

    /** {@code /classements/tous} (#734) : le catalogue complet, mêmes filtres de thème, plus la période. */
    private int catalog(HttpExchange exchange) throws IOException {
        Map<String, Object> live = ServerState.resolve(store);
        Map<String, Object> state = routes.demo() ? DemoData.state(Instant.now()) : live;
        Map<String, String> query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
        String category = StatLabels.CATEGORIES.containsKey(query.getOrDefault("categorie", ""))
                ? query.get("categorie") : "";
        String body = (routes.demo() ? "" : unavailable(state)) + BoardCatalogPage.render(PublicPlayerData.statistics(state.get("statistics")), routes,
                query.getOrDefault("periode", ""), category) + (routes.demo() ? "" : freshness(state));
        String description = "Tous les classements de " + site.title()
                + " : monstres, blocs, exploration, quêtes, par jour, semaine, mois et saison.";
        PageMeta meta = routes.demo() ? PageMeta.demo(description, PageMeta.BOARDS)
                : new PageMeta(BoardCatalogPage.path(routes), description, PageMeta.BOARDS);
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, meta,
                routes.demo() ? "Tous les classements (démonstration)" : "Tous les classements",
                ServerStatus.of(live), body));
        return 200;
    }

    /** {@code /classements/creatures} (#734) : un classement par type de créature tuée, avec recherche. */
    private int creatures(HttpExchange exchange) throws IOException {
        Map<String, Object> live = ServerState.resolve(store);
        Map<String, Object> state = routes.demo() ? DemoData.state(Instant.now()) : live;
        Map<String, String> query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
        String type = CreatureBoardsPage.CATEGORIES.containsKey(query.getOrDefault("type", "")) ? query.get("type") : "";
        String body = (routes.demo() ? "" : unavailable(state))
                + CreatureBoardsPage.render(PublicPlayerData.statistics(state.get("statistics")), routes,
                query.getOrDefault("periode", ""), type, query.getOrDefault("q", "").strip())
                + (routes.demo() ? "" : freshness(state));
        String description = "Classements par créature de " + site.title()
                + " : zombies, squelettes, araignées, boss et mobs spéciaux, par semaine, mois et depuis toujours.";
        PageMeta meta = routes.demo() ? PageMeta.demo(description, PageMeta.BOARDS)
                : new PageMeta(CreatureBoardsPage.path(routes), description, PageMeta.BOARDS);
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, meta,
                routes.demo() ? "Classements par créature (démonstration)" : "Classements par créature",
                ServerStatus.of(live), body));
        return 200;
    }

    private int send(HttpExchange exchange, int status, ServerStatus serverStatus, String body) throws IOException {
        String description = "Les meilleurs aventuriers de " + site.title()
                + " : expérience, quêtes terminées, saisons et événements.";
        PageMeta meta = routes.demo() ? PageMeta.demo(description, PageMeta.BOARDS)
                : new PageMeta(PageMeta.BOARDS, description, PageMeta.BOARDS);
        HttpResponses.sendHtml(exchange, status, PageLayout.render(site, meta,
                routes.demo() ? "Classements (démonstration)" : "Classements", serverStatus, body));
        return status;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> periods(Map<String, Object> statistics) {
        List<Map<String, Object>> periods = new ArrayList<>();
        if (statistics.get("season") instanceof Map<?, ?> season) {
            periods.add((Map<String, Object>) season);
        }
        if (statistics.get("events") instanceof List<?> events) {
            for (Object event : events) {
                periods.add((Map<String, Object>) event);
            }
        }
        return periods;
    }

    private static Map<String, Object> find(List<Map<String, Object>> periods, String id) {
        return periods.stream().filter(period -> id.equals(period.get("id"))).findFirst().orElse(null);
    }

    /** Onglets de période : « Depuis toujours », la saison, les événements (le thème choisi est conservé). */
    private String tabs(List<Map<String, Object>> periods, Map<String, Object> selected, String category) {
        if (periods.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<p class=\"filters-label\" id=\"periode-label\">Période</p>"
                + "<nav aria-labelledby=\"periode-label\"><ul class=\"tabs\">");
        html.append("<li><a href=\"").append(href(null, category)).append('"')
                .append(selected == null ? " aria-current=\"page\"" : "").append(">Depuis toujours</a></li>");
        for (Map<String, Object> period : periods) {
            String id = String.valueOf(period.get("id"));
            html.append("<li><a href=\"").append(href(id, category)).append('"')
                    .append(period == selected ? " aria-current=\"page\"" : "").append('>')
                    .append(Html.escape(String.valueOf(period.getOrDefault("name", id)))).append("</a></li>");
        }
        return html.append("</ul></nav>").toString();
    }

    /** Thèmes présents dans les classements affichés ; aucun filtre s'il n'y en a qu'un. */
    private String categories(Map<String, List<Map<String, Object>>> boards, Map<String, Object> period, String selected) {
        List<String> present = new ArrayList<>();
        for (String category : StatLabels.CATEGORIES.keySet()) {
            if (boards.keySet().stream().anyMatch(metric -> StatLabels.category(metric).equals(category))) {
                present.add(category);
            }
        }
        if (present.size() < 2) {
            return "";
        }
        String periodId = period == null ? null : String.valueOf(period.get("id"));
        StringBuilder html = new StringBuilder("<p class=\"filters-label\" id=\"theme-label\">Thème</p>"
                + "<nav aria-labelledby=\"theme-label\"><ul class=\"tabs\">");
        html.append("<li><a href=\"").append(href(periodId, "")).append('"')
                .append(selected.isEmpty() ? " aria-current=\"page\"" : "").append(">Tout</a></li>");
        for (String category : present) {
            String[] label = StatLabels.CATEGORIES.get(category);
            html.append("<li><a href=\"").append(href(periodId, category)).append('"')
                    .append(category.equals(selected) ? " aria-current=\"page\"" : "").append('>')
                    .append(PortalIcons.icon(label[1], 16)).append(' ').append(Html.escape(label[0])).append("</a></li>");
        }
        return html.append("</ul></nav>").toString();
    }

    private String href(String periodId, String category) {
        List<String> params = new ArrayList<>();
        if (periodId != null) {
            params.add("periode=" + URLEncoder.encode(periodId, StandardCharsets.UTF_8));
        }
        if (!category.isEmpty()) {
            params.add("categorie=" + category);
        }
        return routes.boards() + (params.isEmpty() ? "" : "?" + String.join("&amp;", params));
    }

    private static Map<String, List<Map<String, Object>>> filter(Map<String, List<Map<String, Object>>> boards,
                                                                  String category) {
        if (category.isEmpty()) {
            return boards;
        }
        Map<String, List<Map<String, Object>>> kept = new LinkedHashMap<>();
        boards.forEach((metric, rows) -> {
            if (StatLabels.category(metric).equals(category)) {
                kept.put(metric, rows);
            }
        });
        return kept;
    }

    private String lifetimeIntro() {
        return routes.demo()
                ? "<p>Classement <strong>depuis toujours</strong>, ici avec des joueurs fictifs.</p>"
                : "<p>Classement <strong>depuis toujours</strong> : tout ce qui a été gagné depuis l'ouverture du "
                        + "serveur. Seuls les joueurs qui ont choisi d'afficher leur profil (<code>/profile web "
                        + "public</code> en jeu) y figurent.</p>";
    }

    /** En-tête d'une saison ou d'un événement ; renvoie le message qui remplace les classements, ou {@code null}. */
    private static String periodIntro(StringBuilder html, Map<String, Object> period) {
        String status = String.valueOf(period.get("status"));
        html.append("<p><strong>")
                .append(Html.escape(String.valueOf(period.getOrDefault("name", period.get("id"))))).append("</strong> — ")
                .append(Html.escape(StatLabels.status(status))).append(" · du ")
                .append(Html.escape(StatLabels.dateTime(String.valueOf(period.get("start"))))).append(" au ")
                .append(Html.escape(StatLabels.dateTime(String.valueOf(period.get("end"))))).append(".</p>");
        html.append(countdown(status, period, Instant.now()));
        html.append(rules(period));
        if ("CANCELLED".equals(status)) {
            return "<p class=\"note\">Cet événement a été annulé : aucun classement ni aucune récompense.</p>";
        }
        if ("SCHEDULED".equals(status)) {
            return "<p class=\"note\">Pas encore commencé : le classement s'affichera dès le début.</p>";
        }
        html.append(community(period));
        return null;
    }

    /** Compte à rebours (#57) : avant le début, puis jusqu'à la fin. */
    static String countdown(String status, Map<String, Object> period, Instant now) {
        String left = switch (status) {
            case "SCHEDULED" -> StatLabels.remaining(String.valueOf(period.get("start")), now);
            case "ACTIVE" -> StatLabels.remaining(String.valueOf(period.get("end")), now);
            default -> "";
        };
        if (left.isEmpty()) {
            return "";
        }
        return "<p><strong>" + ("SCHEDULED".equals(status) ? "Commence dans " : "Se termine dans ")
                + Html.escape(left) + "</strong></p>";
    }

    /** Règles d'un événement (#57) : sens du classement, cibles, récompenses annoncées. */
    @SuppressWarnings("unchecked")
    static String rules(Map<String, Object> period) {
        StringBuilder items = new StringBuilder();
        String ranking = StatLabels.ranking(period.get("ranking") instanceof String text ? text : null);
        if (!ranking.isEmpty() && !"HIGHEST_TOTAL".equals(period.get("ranking"))) {
            items.append("<li>").append(Html.escape(ranking)).append("</li>");
        }
        if (period.get("filter") instanceof Map<?, ?> filter) {
            for (Map.Entry<?, ?> entry : filter.entrySet()) {
                List<String> values = new ArrayList<>();
                for (Object value : (List<Object>) entry.getValue()) {
                    values.add(StatLabels.constant(String.valueOf(value)));
                }
                items.append("<li>").append(Html.escape(StatLabels.filter(String.valueOf(entry.getKey())))).append(" : ")
                        .append(Html.escape(String.join(", ", values))).append("</li>");
            }
        }
        if (period.get("rewards") instanceof List<?> rewards) {
            for (Object item : rewards) {
                Map<String, Object> reward = (Map<String, Object>) item;
                items.append("<li>Récompense ").append(Html.escape(rewardWho(reward))).append(" : ")
                        .append(Html.escape(String.valueOf(reward.get("label")))).append("</li>");
            }
        }
        return items.isEmpty() ? "" : "<ul>" + items + "</ul>";
    }

    private static String rewardWho(Map<String, Object> reward) {
        long value = reward.get("value") instanceof Number number ? number.longValue() : 0L;
        return switch (String.valueOf(reward.get("kind"))) {
            case "RANK" -> value == 1 ? "du premier" : "du top " + value;
            case "THRESHOLD" -> "à partir de " + StatLabels.number(value);
            default -> "si l'objectif commun est atteint";
        };
    }

    /** Barre de progression d'un objectif commun (#57) ; vide pour un autre événement. */
    static String community(Map<String, Object> period) {
        if (!(period.get("community") instanceof Map<?, ?> community)
                || !(community.get("goal") instanceof Number goal) || !(community.get("total") instanceof Number total)) {
            return "";
        }
        long shown = Math.min(total.longValue(), goal.longValue());
        return "<section class=\"card\">" + PortalIcons.heading("h2", "heart", "Objectif commun") + "<p><progress max=\"" + goal.longValue() + "\" value=\""
                + shown + "\"></progress> " + Html.escape(StatLabels.number(total)) + " / "
                + Html.escape(StatLabels.number(goal)) + "</p>"
                + (Boolean.TRUE.equals(community.get("reached")) ? "<p><strong>Objectif atteint, bravo à tous !</strong></p>" : "")
                + "</section>";
    }

    private String cards(Map<String, List<Map<String, Object>>> boards) {
        if (boards.isEmpty()) {
            return PortalIcons.empty("trophy", "Aucun classement disponible pour le moment.");
        }
        StringBuilder html = new StringBuilder("<div class=\"grid boards\">");
        for (Map.Entry<String, List<Map<String, Object>>> board : boards.entrySet()) {
            html.append("<section class=\"card\">")
                    .append(PortalIcons.heading("h2", PortalIcons.forMetric(board.getKey()), StatLabels.title(board.getKey())))
                    .append(BoardView.render(board.getValue(), board.getKey(), TOP, routes)).append("</section>");
        }
        return html.append("</div>").toString();
    }

    /** Métriques d'un bloc {@code leaderboards}, dans l'ordre {@link StatLabels#PREFERRED}. */
    @SuppressWarnings("unchecked")
    static Map<String, List<Map<String, Object>>> boards(Object raw) {
        Map<String, List<Map<String, Object>>> sorted = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> boards)) {
            return sorted;
        }
        for (String metric : StatLabels.PREFERRED) {
            if (boards.get(metric) instanceof List<?> rows) {
                sorted.put(metric, (List<Map<String, Object>>) rows);
            }
        }
        for (Map.Entry<?, ?> entry : boards.entrySet()) {
            if (entry.getValue() instanceof List<?> rows) {
                sorted.putIfAbsent(String.valueOf(entry.getKey()), (List<Map<String, Object>>) rows);
            }
        }
        return sorted;
    }

    /** Instantané v1 : {@code leaderboards.<piste>} en {@code totalXp}, présenté comme une métrique d'XP. */
    private static Map<String, List<Map<String, Object>>> legacyBoards(Object raw) {
        Map<String, List<Map<String, Object>>> boards = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> legacy) {
            for (Map.Entry<?, ?> entry : legacy.entrySet()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Map<String, Object> row : PublicPlayerData.rankingRows(entry.getValue(), "totalXp")) {
                    rows.add(Map.of("name", row.get("name"), "value", row.get("totalXp")));
                }
                boards.put("RPG_XP_" + entry.getKey(), rows);
            }
        }
        return boards;
    }

    /**
     * Avis affiché en tête des vraies pages Joueurs et Classements quand aucune donnée de jeu n'est affichée
     * (#56) : sans relevé complet récent ({@link ServerState#SYNCED_AT} absent), les listes vides ne veulent pas
     * dire « personne », mais « pas de données pour l'instant ». Vide quand les données sont là.
     */
    static String unavailable(Map<String, Object> state) {
        return state.get(ServerState.SYNCED_AT) != null ? ""
                : "<p class=\"unknown\">" + UNAVAILABLE + "</p>";
    }

    static final String UNAVAILABLE = "Profils et classements momentanément indisponibles : le site n'a pas de "
            + "relevé récent des joueurs. Ils reviendront d'eux-mêmes au prochain relevé du serveur de jeu.";

    static String freshness(Map<String, Object> state) {
        String when = StatLabels.dateTime(state.get("generatedAt") instanceof String text ? text : null);
        return when.isEmpty() ? "" : "<p class=\"note\">Données relevées sur le serveur le " + Html.escape(when) + ".</p>";
    }
}
