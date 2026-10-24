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
 * quelle machine. {@code sync} décrit le dernier instantané complet (données de jeu) et
 * {@code load} la charge annoncée par le serveur (TPS, MSPT).</p>
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
        body.put("sync", syncState());
        body.put("load", load());
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

    /**
     * #421 — instantané complet (toutes les 10 à 15 min) : état des données de jeu, date et numéro
     * du dernier reçu, coût de sa collecte mesuré par le plugin. Aucune donnée de joueur.
     */
    private Map<String, Object> syncState() {
        Map<String, Object> sync = new LinkedHashMap<>();
        Optional<Map<String, Object>> snapshot = store.current();
        sync.put("data", snapshot.isEmpty() ? "MISSING" : store.isDataStale(snapshot.get()) ? "STALE" : "FRESH");
        sync.put("lastSync", snapshot.map(data -> data.get("generatedAt")).orElse(null));
        sync.put("sequence", snapshot.map(data -> data.get("sequence")).orElse(null));
        snapshot.map(data -> data.get("export")).filter(Map.class::isInstance).map(Map.class::cast)
                .ifPresent(export -> {
                    Map<String, Object> cost = new LinkedHashMap<>();
                    for (String key : EXPORT_KEYS) {
                        if (export.get(key) instanceof Number number) {
                            cost.put(key, number);
                        }
                    }
                    sync.put("export", cost);
                });
        return sync;
    }

    private static final java.util.List<String> EXPORT_KEYS = java.util.List.of(
            "intervalSeconds", "collectMs", "mainThreadMs", "profiles");

    /** Charge du serveur de jeu au dernier relevé d'état (TPS, MSPT), si le plugin l'envoie. */
    private Map<String, Object> load() {
        Map<String, Object> load = new LinkedHashMap<>();
        store.currentHeartbeat().map(beat -> beat.get("server")).filter(Map.class::isInstance).map(Map.class::cast)
                .ifPresent(server -> {
                    if (server.get("tps") instanceof Number tps) {
                        load.put("tps", tps);
                    }
                    if (server.get("mspt") instanceof Number mspt) {
                        load.put("mspt", mspt);
                    }
                });
        return load;
    }

    private String snapshotState() {
        Optional<Map<String, Object>> snapshot = store.current();
        if (snapshot.isEmpty()) {
            return "MISSING";
        }
        return store.isStale(snapshot.get()) ? "STALE" : "FRESH";
    }
}
