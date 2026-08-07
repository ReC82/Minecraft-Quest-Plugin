package be.lloyd.rpgquest.webapi.api;

import be.lloyd.rpgquest.webapi.ServerState;
import be.lloyd.rpgquest.webapi.SnapshotStore;
import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code GET /api/status} — statut serveur (mission point 3). */
public final class StatusHandler implements ApiHandler {

    private final SnapshotStore store;

    public StatusHandler(SnapshotStore store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        Object serverRaw = state.get("server");
        Map<?, ?> server = serverRaw instanceof Map<?, ?> map ? map : Map.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("online", server.get("online"));
        if (Boolean.TRUE.equals(server.get("online"))) {
            body.put("playerCount", server.get("playerCount"));
            body.put("maxPlayers", server.get("maxPlayers"));
            body.put("generatedAt", state.get("generatedAt"));
        } else {
            body.put("reason", server.get("reason"));
        }

        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }
}
