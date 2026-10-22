package com.lodygames.rpgquest.webapi.site;

import java.util.List;
import java.util.Map;

/**
 * Un classement en liste ordonnée (#56) : rang, pseudo lié au profil, valeur. Pas de tableau : la
 * ligne tient sur un écran de téléphone, sans défilement horizontal.
 */
final class BoardView {

    private BoardView() {
    }

    /**
     * @param rows   lignes déjà filtrées par {@code PublicPlayerData} ({@code name}, {@code value}, {@code rank} facultatif)
     * @param metric métrique, pour l'unité
     * @param limit  nombre de lignes affichées au plus
     */
    static String render(List<Map<String, Object>> rows, String metric, int limit) {
        if (rows.isEmpty()) {
            return PortalIcons.empty("npc", "Personne pour l'instant.");
        }
        StringBuilder html = new StringBuilder("<ol class=\"board\">");
        int position = 0;
        for (Map<String, Object> row : rows) {
            if (position == limit) {
                break;
            }
            position++;
            Object rank = row.get("rank") instanceof Number number ? number : position;
            html.append("<li><span class=\"rank").append(podium(rank)).append("\" aria-label=\"Rang ").append(rank).append("\">").append(rank)
                    .append("</span><span class=\"who\">").append(PlayersPageHandler.link(String.valueOf(row.get("name"))))
                    .append("</span><span class=\"value\">").append(StatLabels.number(row.get("value"))).append(' ')
                    .append(Html.escape(StatLabels.unit(metric))).append("</span></li>");
        }
        return html.append("</ol>").toString();
    }

    /** Or, argent, bronze pour les trois premiers rangs (ex aequo compris, #428). */
    private static String podium(Object rank) {
        long value = rank instanceof Number number ? number.longValue() : 0L;
        return value >= 1 && value <= 3 ? " podium-" + value : "";
    }
}
