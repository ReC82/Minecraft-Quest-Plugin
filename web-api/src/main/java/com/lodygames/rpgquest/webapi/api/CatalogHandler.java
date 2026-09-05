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

/** {@code GET /api/catalog} — catalogue public d'objets (mission point 3). */
public final class CatalogHandler implements ApiHandler {

    private final SnapshotStore store;

    public CatalogHandler(SnapshotStore store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        Object catalogRaw = state.get("catalog");
        List<?> catalog = catalogRaw instanceof List<?> list ? list : List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("online", ServerState.isOnline(state));
        body.put("items", catalog);

        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }
}
