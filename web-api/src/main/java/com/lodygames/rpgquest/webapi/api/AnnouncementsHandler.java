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

/** {@code GET /api/announcements} — actualités/annonces configurées (mission point 3). */
public final class AnnouncementsHandler implements ApiHandler {

    private final SnapshotStore store;

    public AnnouncementsHandler(SnapshotStore store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        Object announcementsRaw = state.get("announcements");
        List<?> announcements = announcementsRaw instanceof List<?> list ? list : List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("online", ServerState.isOnline(state));
        body.put("announcements", announcements);

        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }
}
