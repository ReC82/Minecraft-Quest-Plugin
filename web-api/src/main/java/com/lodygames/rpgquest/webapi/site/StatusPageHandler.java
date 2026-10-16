package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.ServerStatus;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/** {@code GET /status} — statut serveur en page HTML (mission point 8), quatre états distincts (#421). */
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
        ServerStatus status = ServerStatus.of(state);

        String body = switch (status.kind()) {
            case ONLINE -> {
                Object serverRaw = state.get("server");
                Map<?, ?> server = serverRaw instanceof Map<?, ?> map ? map : Map.of();
                yield "<p>Serveur en ligne — " + Html.escape(String.valueOf(server.get("playerCount")))
                        + " / " + Html.escape(String.valueOf(server.get("maxPlayers"))) + " joueurs connectés.</p>"
                        + "<p><small>Dernière mise à jour : " + Html.escape(status.lastUpdateText()) + "</small></p>";
            }
            case OFFLINE -> "<p>Le serveur de jeu a annoncé son arrêt le " + Html.escape(status.lastUpdateText())
                    + ". Il redémarre peut-être : cette page se mettra à jour dès son retour.</p>";
            case STALE -> "<p>État du serveur de jeu inconnu : le dernier relevé date du "
                    + Html.escape(status.lastUpdateText()) + ". Le site, lui, fonctionne.</p>";
            case UNKNOWN -> "<p>État du serveur de jeu inconnu : aucune donnée reçue pour le moment. "
                    + "Le site, lui, fonctionne.</p>";
        };

        HttpResponses.sendHtml(exchange, 200, PageLayout.render(siteTitle, "Statut", status, body));
        return 200;
    }
}
