package com.lodygames.rpgquest.webapi.site;

import java.util.Map;

/** Pagination des listes publiques (#56) : {@code ?page=N}, une valeur invalide vaut une page introuvable. */
final class SitePaging {

    /** Au-delà, la page est refusée : aucune liste publique n'en compte autant. */
    static final int MAX_PAGE = 1000;

    private SitePaging() {
    }

    /** Numéro de page demandé, à partir de 1 ; {@link Integer#MAX_VALUE} si invalide (→ 404 chez l'appelant). */
    static int page(Map<String, String> query) {
        String raw = query.get("page");
        if (raw == null) {
            return 1;
        }
        if (!raw.matches("[0-9]{1,4}")) {
            return Integer.MAX_VALUE;
        }
        int page = Integer.parseInt(raw);
        return page < 1 || page > MAX_PAGE ? Integer.MAX_VALUE : page;
    }

    static int pageCount(int items, int pageSize) {
        return Math.max(1, (items + pageSize - 1) / pageSize);
    }

    /** Liens précédent / suivant ; {@code base} se termine par {@code ?} ou {@code &}. */
    static String pager(String base, int page, int pages) {
        if (pages <= 1) {
            return "";
        }
        StringBuilder html = new StringBuilder("<nav class=\"pager\" aria-label=\"Pages\"><span>");
        if (page > 1) {
            html.append("<a href=\"").append(base).append("page=").append(page - 1).append("\">← Précédent</a>");
        }
        html.append("</span><span class=\"note\">Page ").append(page).append(" / ").append(pages).append("</span><span>");
        if (page < pages) {
            html.append("<a href=\"").append(base).append("page=").append(page + 1).append("\">Suivant →</a>");
        }
        return html.append("</span></nav>").toString();
    }
}
