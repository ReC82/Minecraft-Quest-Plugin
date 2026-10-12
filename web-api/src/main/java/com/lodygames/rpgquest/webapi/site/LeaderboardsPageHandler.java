package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** {@code GET /leaderboards} — classements en page HTML (mission point 8). */
public final class LeaderboardsPageHandler implements ApiHandler {

    private final SnapshotStore store;
    private final String siteTitle;

    public LeaderboardsPageHandler(SnapshotStore store, String siteTitle) {
        this.store = store;
        this.siteTitle = siteTitle;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        boolean online = ServerState.isOnline(state);

        Object leaderboardsRaw = state.get("leaderboards");
        Map<?, ?> leaderboards = leaderboardsRaw instanceof Map<?, ?> map ? map : Map.of();
        // #54 : un instantané de version 2 porte des statistiques par portée — on les préfère, elles
        // contiennent aussi les quêtes terminées. Version 1 : l'ancien affichage, inchangé.
        if (state.get("statistics") instanceof Map<?, ?> statistics
                && statistics.get("lifetime") instanceof Map<?, ?> lifetime && !lifetime.isEmpty()) {
            HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Classements", online,
                    lifetimeBoards(lifetime)));
            return 200;
        }

        StringBuilder body = new StringBuilder();
        if (leaderboards.isEmpty()) {
            body.append("<p><em>Aucun classement disponible pour le moment.</em></p>");
        }
        for (Map.Entry<?, ?> entry : leaderboards.entrySet()) {
            body.append("<h2>").append(Html.escape(String.valueOf(entry.getKey()))).append("</h2>");
            List<?> rows = entry.getValue() instanceof List<?> list ? list : List.of();
            if (rows.isEmpty()) {
                body.append("<p><em>Personne pour l'instant.</em></p>");
                continue;
            }
            body.append("<table><thead><tr><th>#</th><th>Joueur</th><th>XP</th></tr></thead><tbody>");
            int rank = 1;
            for (Object rowRaw : rows) {
                if (rowRaw instanceof Map<?, ?> row) {
                    body.append("<tr><td>").append(rank++).append("</td><td>")
                            .append(Html.escape(String.valueOf(row.get("name")))).append("</td><td>")
                            .append(Html.escape(String.valueOf(row.get("totalXp")))).append("</td></tr>");
                }
            }
            body.append("</tbody></table>");
        }

        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Classements", online, body.toString()));
        return 200;
    }

    private static String lifetimeBoards(Map<?, ?> lifetime) {
        StringBuilder body = new StringBuilder("<p class=\"note\">Classements « depuis toujours ». Les joueurs qui ont "
                + "masqué leur profil n'y figurent pas (<a href=\"/confidentialite\">confidentialité</a>).</p>");
        for (Map.Entry<?, ?> entry : lifetime.entrySet()) {
            String metric = String.valueOf(entry.getKey());
            body.append("<h2>").append(Html.escape(title(metric))).append("</h2>");
            List<?> rows = entry.getValue() instanceof List<?> list ? list : List.of();
            if (rows.isEmpty()) {
                body.append("<p><em>Personne pour l'instant.</em></p>");
                continue;
            }
            body.append("<table><thead><tr><th>#</th><th>Joueur</th><th>").append(Html.escape(unit(metric)))
                    .append("</th></tr></thead><tbody>");
            int rank = 1;
            for (Object rowRaw : rows) {
                if (rowRaw instanceof Map<?, ?> row) {
                    body.append("<tr><td>").append(rank++).append("</td><td>")
                            .append(PlayersPageHandler.link(String.valueOf(row.get("name")))).append("</td><td>")
                            .append(Html.escape(String.valueOf(row.get("value")))).append("</td></tr>");
                }
            }
            body.append("</tbody></table>");
        }
        return body.toString();
    }

    static String title(String metric) {
        if (metric.equals("QUESTS_COMPLETED")) {
            return "Quêtes terminées";
        }
        if (metric.startsWith("RPG_XP_")) {
            return "XP — " + label(metric.substring("RPG_XP_".length()));
        }
        return metric;
    }

    private static String unit(String metric) {
        return metric.equals("QUESTS_COMPLETED") ? "Quêtes" : "XP";
    }

    /** Libellé français d'une piste de progression ; la clé brute si elle est inconnue. */
    static String label(String skill) {
        return switch (skill) {
            case "GLOBAL" -> "Global";
            case "COMBAT" -> "Combat";
            case "MINING" -> "Minage";
            case "FARMING" -> "Agriculture";
            case "FISHING" -> "Pêche";
            case "EXPLORATION" -> "Exploration";
            default -> skill;
        };
    }
}
