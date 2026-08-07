package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** {@code GET /wiki} — catalogue public d'objets en page HTML (mission point 8). */
public final class WikiPageHandler implements ApiHandler {

    private final SnapshotStore store;
    private final String siteTitle;

    public WikiPageHandler(SnapshotStore store, String siteTitle) {
        this.store = store;
        this.siteTitle = siteTitle;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        boolean online = ServerState.isOnline(state);

        Object catalogRaw = state.get("catalog");
        List<?> catalog = catalogRaw instanceof List<?> list ? list : List.of();

        StringBuilder body = new StringBuilder();
        if (catalog.isEmpty()) {
            body.append("<p><em>Aucun objet au catalogue pour le moment.</em></p>");
        } else {
            body.append("<table><thead><tr><th>Objet</th><th>Rareté</th></tr></thead><tbody>");
            for (Object itemRaw : catalog) {
                if (itemRaw instanceof Map<?, ?> item) {
                    body.append("<tr><td>").append(Html.escape(String.valueOf(item.get("name")))).append("</td><td>")
                            .append(Html.escape(String.valueOf(item.get("rarity")))).append("</td></tr>");
                }
            }
            body.append("</tbody></table>");
        }

        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Wiki", online, body.toString()));
        return 200;
    }
}
