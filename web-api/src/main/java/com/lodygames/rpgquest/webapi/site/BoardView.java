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
            return "<p class=\"note\">Personne pour l'instant.</p>";
        }
        StringBuilder html = new StringBuilder("<ol class=\"board\">");
        int position = 0;
        for (Map<String, Object> row : rows) {
            if (position == limit) {
                break;
            }
            position++;
            Object rank = row.get("rank") instanceof Number number ? number : position;
            html.append("<li><span class=\"rank\" aria-label=\"Rang ").append(rank).append("\">").append(rank)
                    .append("</span><span class=\"who\">").append(PlayersPageHandler.link(String.valueOf(row.get("name"))))
                    .append("</span><span class=\"value\">").append(StatLabels.number(row.get("value"))).append(' ')
                    .append(Html.escape(StatLabels.unit(metric))).append("</span></li>");
        }
        return html.append("</ol>").toString();
    }
}
