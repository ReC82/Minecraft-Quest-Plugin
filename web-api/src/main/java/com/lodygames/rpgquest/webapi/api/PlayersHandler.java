package com.lodygames.rpgquest.webapi.api;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.privacy.PublicPlayerData;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/players} — nombre de joueurs, et liste nominative
 * seulement si {@code web-export.include-connected-players} l'autorise côté
 * plugin (mission point 3, "joueurs connectés si autorisé") et que le portail publie des données
 * joueurs ({@code player-data-public}, #55).
 */
public final class PlayersHandler implements ApiHandler {

    private final SnapshotStore store;
    private final boolean playerDataPublic;

    public PlayersHandler(SnapshotStore store, boolean playerDataPublic) {
        this.store = store;
        this.playerDataPublic = playerDataPublic;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        boolean online = ServerState.isOnline(state);

        Object serverRaw = state.get("server");
        Map<?, ?> server = serverRaw instanceof Map<?, ?> map ? map : Map.of();
        List<String> players = playerDataPublic ? PublicPlayerData.names(state.get("players")) : List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("online", online);
        body.put("count", online ? server.get("playerCount") : 0);
        body.put("players", players);

        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }
}
