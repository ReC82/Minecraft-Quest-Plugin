package com.lodygames.rpgquest.webapi.site;

import java.util.List;
import java.util.Map;

/**
 * Un classement (#56) : podium des trois premiers, puis liste ordonnée — rang, pseudo lié au profil,
 * valeur. Pas de tableau : tout tient sur un écran de téléphone, sans défilement horizontal.
 *
 * <p>Le podium garde l'ordre des rangs dans le HTML (lecteurs d'écran) et ne le change qu'à l'écran
 * (deuxième, premier, troisième). Hauteur et couleur suivent le rang du plugin : deux ex aequo à la
 * première place ont tous deux la marche d'or.</p>
 */
final class BoardView {

    static final int PODIUM = 3;

    private BoardView() {
    }

    /**
     * @param rows   lignes déjà filtrées par {@code PublicPlayerData} ({@code name}, {@code value}, {@code rank} facultatif)
     * @param metric métrique, pour l'unité
     * @param limit  nombre de lignes affichées au plus, podium compris
     * @param routes vraies pages ou pages de démonstration, pour les liens de profil
     */
    static String render(List<Map<String, Object>> rows, String metric, int limit, SiteRoutes routes) {
        if (rows.isEmpty()) {
            return PortalIcons.empty("npc", "Personne pour l'instant.");
        }
        int shown = Math.min(limit, rows.size());
        StringBuilder html = new StringBuilder("<ol class=\"podium\" aria-label=\"Podium\">");
        for (int position = 1; position <= Math.min(PODIUM, shown); position++) {
            Map<String, Object> row = rows.get(position - 1);
            long rank = rank(row, position);
            html.append("<li class=\"p").append(Math.min(rank, PODIUM)).append(" pos").append(position).append("\">")
                    .append(rank == 1 ? "<span class=\"crown\">" + PortalIcons.icon("trophy", 32) + "</span>" : "")
                    .append("<span class=\"who\">").append(PlayersPageHandler.link(routes, String.valueOf(row.get("name"))))
                    .append("</span><span class=\"value\">").append(value(row, metric)).append("</span>")
                    .append("<span class=\"step\" aria-label=\"Rang ").append(rank).append("\">").append(rank)
                    .append("</span></li>");
        }
        html.append("</ol>");
        if (shown > PODIUM) {
            html.append("<ol class=\"board\" start=\"").append(PODIUM + 1).append("\">");
            for (int position = PODIUM + 1; position <= shown; position++) {
                Map<String, Object> row = rows.get(position - 1);
                long rank = rank(row, position);
                html.append("<li><span class=\"rank").append(podium(rank)).append("\" aria-label=\"Rang ").append(rank)
                        .append("\">").append(rank).append("</span><span class=\"who\">")
                        .append(PlayersPageHandler.link(routes, String.valueOf(row.get("name"))))
                        .append("</span><span class=\"value\">").append(value(row, metric)).append("</span></li>");
            }
            html.append("</ol>");
        }
        return html.toString();
    }

    private static long rank(Map<String, Object> row, int position) {
        return row.get("rank") instanceof Number number ? number.longValue() : position;
    }

    private static String value(Map<String, Object> row, String metric) {
        return StatLabels.number(row.get("value")) + ' ' + Html.escape(StatLabels.unit(metric));
    }

    /** Or, argent, bronze pour les trois premiers rangs (ex aequo compris, #428). */
    static String podium(long rank) {
        return rank >= 1 && rank <= 3 ? " podium-" + rank : "";
    }
}
