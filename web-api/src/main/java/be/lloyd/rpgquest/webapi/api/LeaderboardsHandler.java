package be.lloyd.rpgquest.webapi.api;

import be.lloyd.rpgquest.webapi.ServerState;
import be.lloyd.rpgquest.webapi.SnapshotStore;
import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import be.lloyd.rpgquest.webapi.http.MalformedRequestException;
import be.lloyd.rpgquest.webapi.http.QueryParams;
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

    public LeaderboardsHandler(SnapshotStore store) {
        this.store = store;
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

    private List<?> truncate(Object value, int limit) {
        List<?> list = value instanceof List<?> l ? l : List.of();
        return list.size() <= limit ? list : list.subList(0, limit);
    }
}
