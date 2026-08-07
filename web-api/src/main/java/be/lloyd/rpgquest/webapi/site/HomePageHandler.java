package be.lloyd.rpgquest.webapi.site;

import be.lloyd.rpgquest.webapi.ServerState;
import be.lloyd.rpgquest.webapi.SnapshotStore;
import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** {@code GET /} — accueil (mission point 8), reprend les annonces configurées. */
public final class HomePageHandler implements ApiHandler {

    private final SnapshotStore store;
    private final String siteTitle;

    public HomePageHandler(SnapshotStore store, String siteTitle) {
        this.store = store;
        this.siteTitle = siteTitle;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        // "/" est enregistrée comme préfixe le plus court : elle sert aussi de retombée
        // pour toute page inconnue non capturée par les autres contextes plus spécifiques.
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            HttpResponses.sendHtml(exchange, 404,
                    PageLayout.render(siteTitle, "Introuvable", true, "<p>Page introuvable.</p>"));
            return 404;
        }

        Map<String, Object> state = ServerState.resolve(store);
        boolean online = ServerState.isOnline(state);

        Object announcementsRaw = state.get("announcements");
        List<?> announcements = announcementsRaw instanceof List<?> list ? list : List.of();

        StringBuilder body = new StringBuilder();
        body.append("<p>Bienvenue sur le portail de ").append(Html.escape(siteTitle)).append(".</p>");
        if (announcements.isEmpty()) {
            body.append("<p><em>Aucune actualité pour le moment.</em></p>");
        } else {
            body.append("<h2>Actualités</h2>");
            for (Object item : announcements) {
                if (item instanceof Map<?, ?> announcement) {
                    body.append("<h3>").append(Html.escape(String.valueOf(announcement.get("title")))).append("</h3>");
                    body.append("<p>").append(Html.escape(String.valueOf(announcement.get("body")))).append("</p>");
                }
            }
        }

        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Accueil", online, body.toString()));
        return 200;
    }
}
