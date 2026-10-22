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
 */
public final class LeaderboardsPageHandler implements ApiHandler {

    static final int TOP = 10;
    private static final Pattern PERIOD_ID = Pattern.compile("[a-z0-9_]{1,64}");

    private final SnapshotStore store;
    private final Site site;

    public LeaderboardsPageHandler(SnapshotStore store, Site site) {
        this.store = store;
        this.site = site;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        ServerStatus serverStatus = ServerStatus.of(state);
        Map<String, Object> statistics = PublicPlayerData.statistics(state.get("statistics"));
        String requested = QueryParams.parse(exchange.getRequestURI().getRawQuery()).getOrDefault("periode", "");

        List<Map<String, Object>> periods = periods(statistics);
        Map<String, Object> period = null;
        if (!requested.isEmpty()) {
            period = PERIOD_ID.matcher(requested).matches() ? find(periods, requested) : null;
            if (period == null) {
                return send(exchange, 404, serverStatus, "<p>Ce classement n'existe pas ou n'est plus publié.</p>"
                        + "<p><a href=\"/classements\">Voir les classements</a></p>");
            }
        }

        StringBuilder body = new StringBuilder(tabs(periods, period));
        if (period == null) {
            body.append(lifetime(state, statistics));
        } else {
            body.append(periodBoards(period));
        }
        body.append(freshness(state));
        return send(exchange, 200, serverStatus, body.toString());
    }

    private int send(HttpExchange exchange, int status, ServerStatus serverStatus, String body) throws IOException {
        HttpResponses.sendHtml(exchange, status, PageLayout.render(site, new PageMeta(PageMeta.BOARDS,
                "Les meilleurs aventuriers de " + site.title() + " : expérience, quêtes terminées, saisons et événements.",
                PageMeta.BOARDS), "Classements", serverStatus, body));
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

    private static String tabs(List<Map<String, Object>> periods, Map<String, Object> selected) {
        if (periods.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<nav aria-label=\"Période\"><ul class=\"tabs\">");
        html.append("<li><a href=\"/classements\"").append(selected == null ? " aria-current=\"page\"" : "")
                .append(">Depuis toujours</a></li>");
        for (Map<String, Object> period : periods) {
            String id = String.valueOf(period.get("id"));
            html.append("<li><a href=\"/classements?periode=").append(URLEncoder.encode(id, StandardCharsets.UTF_8))
                    .append('"').append(period == selected ? " aria-current=\"page\"" : "").append('>')
                    .append(Html.escape(String.valueOf(period.getOrDefault("name", id)))).append("</a></li>");
        }
        return html.append("</ul></nav>").toString();
    }

    private static String lifetime(Map<String, Object> state, Map<String, Object> statistics) {
        StringBuilder html = new StringBuilder("<p>Classement <strong>depuis toujours</strong> : tout ce qui a été "
                + "gagné depuis l'ouverture du serveur. Seuls les joueurs qui ont choisi d'afficher leur profil "
                + "(<code>/profile web public</code> en jeu) y figurent.</p>");
        Map<String, List<Map<String, Object>>> boards = boards(statistics.get("lifetime"));
        if (boards.isEmpty()) {
            boards = legacyBoards(state.get("leaderboards"));
        }
        if (boards.isEmpty()) {
            return html.append(PortalIcons.empty("trophy", "Aucun classement disponible pour le moment.")).toString();
        }
        return html.append(cards(boards)).toString();
    }

    private static String periodBoards(Map<String, Object> period) {
        String status = String.valueOf(period.get("status"));
        StringBuilder html = new StringBuilder("<p><strong>")
                .append(Html.escape(String.valueOf(period.getOrDefault("name", period.get("id"))))).append("</strong> — ")
                .append(Html.escape(StatLabels.status(status))).append(" · du ")
                .append(Html.escape(StatLabels.dateTime(String.valueOf(period.get("start"))))).append(" au ")
                .append(Html.escape(StatLabels.dateTime(String.valueOf(period.get("end"))))).append(".</p>");
        html.append(countdown(status, period, Instant.now()));
        html.append(rules(period));
        if ("CANCELLED".equals(status)) {
            return html.append("<p class=\"note\">Cet événement a été annulé : aucun classement ni aucune récompense.</p>")
                    .toString();
        }
        if ("SCHEDULED".equals(status)) {
            return html.append("<p class=\"note\">Pas encore commencé : le classement s'affichera dès le début.</p>")
                    .toString();
        }
        html.append(community(period));
        Map<String, List<Map<String, Object>>> boards = boards(period.get("leaderboards"));
        if (boards.isEmpty()) {
            return html.append(PortalIcons.empty("trophy", "Aucun classement pour cette période.")).toString();
        }
        return html.append(cards(boards)).toString();
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

    private static String cards(Map<String, List<Map<String, Object>>> boards) {
        StringBuilder html = new StringBuilder("<div class=\"grid\">");
        for (Map.Entry<String, List<Map<String, Object>>> board : boards.entrySet()) {
            html.append("<section class=\"card\">")
                    .append(PortalIcons.heading("h2", PortalIcons.forMetric(board.getKey()), StatLabels.title(board.getKey())))
                    .append(BoardView.render(board.getValue(), board.getKey(), TOP)).append("</section>");
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

    static String freshness(Map<String, Object> state) {
        String when = StatLabels.dateTime(state.get("generatedAt") instanceof String text ? text : null);
        return when.isEmpty() ? "" : "<p class=\"note\">Données relevées sur le serveur le " + Html.escape(when) + ".</p>";
    }
}
