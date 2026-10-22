package com.lodygames.rpgquest.webapi.api;

import com.lodygames.rpgquest.webapi.ServerState;
import com.lodygames.rpgquest.webapi.ServerStatus;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.news.NewsRepository;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GET /health} — sonde d'exploitation non authentifiée (#52), comme celle de PlugAdmin.
 *
 * <p>Répond 200 dès que le processus sert des requêtes : un instantané absent ou périmé n'est pas
 * une panne du portail (il affiche alors son mode dégradé), c'est une information. Ne contient ni
 * donnée joueur, ni chemin de fichier, ni configuration sensible.</p>
 *
 * <p>#421 : {@code server} est l'état du <em>jeu</em> (ONLINE / OFFLINE / STALE / UNKNOWN), distinct
 * de {@code status} qui n'atteste que le portail. {@code heartbeat} dit si le relevé d'état agrégé
 * envoyé par le serveur Minecraft arrive (MISSING / FRESH / STALE) et {@code lastUpdate} date le
 * dernier relevé pris en compte : de quoi diagnostiquer le canal VeryGames → AWS depuis n'importe
 * quelle machine.</p>
 */
public final class HealthHandler implements ApiHandler {

    private final SnapshotStore store;
    private final boolean playerDataPublic;
    private final boolean storeEnabled;
    private final NewsRepository news;

    public HealthHandler(SnapshotStore store, boolean playerDataPublic, boolean storeEnabled, NewsRepository news) {
        this.store = store;
        this.playerDataPublic = playerDataPublic;
        this.storeEnabled = storeEnabled;
        this.news = news;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("snapshot", snapshotState());
        ServerStatus server = ServerStatus.of(ServerState.resolve(store));
        body.put("server", server.kind().name());
        body.put("heartbeat", heartbeatState());
        body.put("lastUpdate", server.lastUpdate() == null ? null : server.lastUpdate().toString());
        body.put("playerDataPublic", playerDataPublic);
        body.put("store", storeEnabled);
        body.put("news", news.published().size());
        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }

    private String heartbeatState() {
        Optional<Map<String, Object>> heartbeat = store.currentHeartbeat();
        if (heartbeat.isEmpty()) {
            return "MISSING";
        }
        return store.isHeartbeatStale(heartbeat.get()) ? "STALE" : "FRESH";
    }

    private String snapshotState() {
        Optional<Map<String, Object>> snapshot = store.current();
        if (snapshot.isEmpty()) {
            return "MISSING";
        }
        return store.isStale(snapshot.get()) ? "STALE" : "FRESH";
    }
}
