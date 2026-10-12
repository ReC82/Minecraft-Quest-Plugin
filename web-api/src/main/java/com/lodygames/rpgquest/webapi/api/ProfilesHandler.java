package com.lodygames.rpgquest.webapi.api;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/profiles} — profils publics limités et statistiques par portée (#54 / #56).
 * Exactement ce que l'instantané publie ; vide si le serveur ne répond pas.
 */
public final class ProfilesHandler implements ApiHandler {

    private final SnapshotStore store;

    public ProfilesHandler(SnapshotStore store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("online", ServerState.isOnline(state));
        body.put("profiles", state.get("profiles") instanceof List<?> list ? list : List.of());
        body.put("statistics", state.get("statistics") instanceof Map<?, ?> map ? map : Map.of());
        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }
}
