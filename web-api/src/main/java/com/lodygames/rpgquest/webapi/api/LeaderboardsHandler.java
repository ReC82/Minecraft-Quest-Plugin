package com.lodygames.rpgquest.webapi.api;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.MalformedRequestException;
import com.lodygames.rpgquest.webapi.http.QueryParams;
import com.lodygames.rpgquest.webapi.privacy.PublicPlayerData;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/leaderboards[?skill=COMBAT&limit=5]} — classements
 * (mission point 3). Sans {@code skill}, retourne toutes les pistes du
 * snapshot ; {@code limit} tronque chaque liste renvoyée (ne relit jamais la
 * base — {@code leaderboard-size} du plugin fixe déjà la borne haute
 * réellement disponible dans le snapshot).
 */
public final class LeaderboardsHandler implements ApiHandler {

    private final SnapshotStore store;
    private final boolean playerDataPublic;

    public LeaderboardsHandler(SnapshotStore store, boolean playerDataPublic) {
        this.store = store;
        this.playerDataPublic = playerDataPublic;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, String> query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
        String requestedSkill = query.get("skill");
        int limit = QueryParams.parseIntParam(query, "limit", Integer.MAX_VALUE, 1, 1000);

        Map<String, Object> state = ServerState.resolve(store);
        Object leaderboardsRaw = state.get("leaderboards");
        Map<?, ?> leaderboards = leaderboardsRaw instanceof Map<?, ?> map ? map : Map.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("online", ServerState.isOnline(state));

        if (requestedSkill != null) {
            String skillKey = requestedSkill.toUpperCase(java.util.Locale.ROOT);
            if (!leaderboards.containsKey(skillKey)) {
                throw new MalformedRequestException("Piste de progression inconnue : \"" + requestedSkill + "\".");
            }
            body.put("skill", skillKey);
            body.put("entries", truncate(leaderboards.get(skillKey), limit));
        } else {
            Map<String, Object> allEntries = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : leaderboards.entrySet()) {
                allEntries.put(String.valueOf(entry.getKey()), truncate(entry.getValue(), limit));
            }
            body.put("leaderboards", allEntries);
        }

        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }

    /** Lignes filtrées par la liste blanche (#55) ; aucune tant que {@code player-data-public=false}. */
    private List<?> truncate(Object value, int limit) {
        List<?> list = playerDataPublic ? PublicPlayerData.rankingRows(value, "totalXp") : List.of();
        return list.size() <= limit ? list : list.subList(0, limit);
    }
}
