package com.lodygames.rpgquest.webapi.api;

import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
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
 */
public final class HealthHandler implements ApiHandler {

    private final SnapshotStore store;
    private final boolean playerDataPublic;
    private final boolean storeEnabled;

    public HealthHandler(SnapshotStore store, boolean playerDataPublic, boolean storeEnabled) {
        this.store = store;
        this.playerDataPublic = playerDataPublic;
        this.storeEnabled = storeEnabled;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("snapshot", snapshotState());
        body.put("playerDataPublic", playerDataPublic);
        body.put("store", storeEnabled);
        HttpResponses.sendJson(exchange, 200, body);
        return 200;
    }

    private String snapshotState() {
        Optional<Map<String, Object>> snapshot = store.current();
        if (snapshot.isEmpty()) {
            return "MISSING";
        }
        return store.isStale(snapshot.get()) ? "STALE" : "FRESH";
    }
}
