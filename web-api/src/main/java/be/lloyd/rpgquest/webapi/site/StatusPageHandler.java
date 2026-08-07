package be.lloyd.rpgquest.webapi.site;

import be.lloyd.rpgquest.webapi.ServerState;
import be.lloyd.rpgquest.webapi.SnapshotStore;
import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/** {@code GET /status} — statut serveur en page HTML (mission point 8). */
public final class StatusPageHandler implements ApiHandler {

    private final SnapshotStore store;
    private final String siteTitle;

    public StatusPageHandler(SnapshotStore store, String siteTitle) {
        this.store = store;
        this.siteTitle = siteTitle;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        boolean online = ServerState.isOnline(state);

        String body;
        if (online) {
            Object serverRaw = state.get("server");
            Map<?, ?> server = serverRaw instanceof Map<?, ?> map ? map : Map.of();
            body = "<p>Serveur en ligne — " + Html.escape(String.valueOf(server.get("playerCount")))
                    + " / " + Html.escape(String.valueOf(server.get("maxPlayers"))) + " joueurs connectés.</p>"
                    + "<p><small>Dernière mise à jour : " + Html.escape(String.valueOf(state.get("generatedAt"))) + "</small></p>";
        } else {
            body = "<p>Aucune donnée de statut disponible pour le moment.</p>";
        }

        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Statut", online, body));
        return 200;
    }
}
