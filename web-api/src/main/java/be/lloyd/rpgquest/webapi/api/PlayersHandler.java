package be.lloyd.rpgquest.webapi.api;

import be.lloyd.rpgquest.webapi.ServerState;
import be.lloyd.rpgquest.webapi.SnapshotStore;
import be.lloyd.rpgquest.webapi.http.ApiHandler;
import be.lloyd.rpgquest.webapi.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/players} — nombre de joueurs, et liste nominative
 * seulement si {@code web-export.include-connected-players} l'autorise côté
 * plugin (mission point 3, "joueurs connectés si autorisé").
 */
public final class PlayersHandler implements ApiHandler {

    private final SnapshotStore store;

    public PlayersHandler(SnapshotStore store) {
        this.store = store;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> state = ServerState.resolve(store);
        boolean online = ServerState.isOnline(state);

        Object serverRaw = state.get("server");
        Map<?, ?> server = serverRaw instanceof Map<?, ?> map ? map : Map.of();
        Object playersRaw = state.get("players");
        List<?> players = playersRaw instanceof List<?> list ? list : List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("online", online);
        body.put("count", online ? server.get("playerCount") : 0);
        body.put("players", players);

        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }
}
