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
 * {@code GET /api/profiles} — profils publics limités et statistiques par portée (#54 / #56).
 * Uniquement les champs de la liste blanche {@link PublicPlayerData} (#55) ; vide si le serveur ne
 * répond pas ou tant que {@code player-data-public=false}.
 */
public final class ProfilesHandler implements ApiHandler {

    private final SnapshotStore store;
    private final boolean playerDataPublic;

    public ProfilesHandler(SnapshotStore store, boolean playerDataPublic) {
        this.store = store;
        this.playerDataPublic = playerDataPublic;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("online", ServerState.isOnline(state));
        body.put("profiles", playerDataPublic ? PublicPlayerData.profiles(state.get("profiles")) : List.of());
        body.put("statistics", playerDataPublic ? PublicPlayerData.statistics(state.get("statistics")) : Map.of());
        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }
}
