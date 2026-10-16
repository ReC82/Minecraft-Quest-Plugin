package com.lodygames.rpgquest.webapi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Résout le snapshot courant en un état exploitable par l'API et le site,
 * en repliant systématiquement l'absence ou la péremption des données sur le
 * mode dégradé (mission étape 21, point 9) plutôt que de laisser chaque
 * route réinventer cette logique.
 *
 * <p>#421 : {@code server.status} distingue {@link ServerStatus.Kind#UNKNOWN} (aucun instantané),
 * {@link ServerStatus.Kind#STALE} (instantané trop ancien), {@link ServerStatus.Kind#OFFLINE} (arrêt
 * annoncé par le serveur) et {@link ServerStatus.Kind#ONLINE} ; {@code server.lastUpdate} date le
 * dernier relevé. Hors {@code ONLINE}, les données de jeu restent repliées sur le mode dégradé.</p>
 */
public final class ServerState {

    private ServerState() {
    }

    public static Map<String, Object> resolve(SnapshotStore store) {
        Optional<Map<String, Object>> snapshot = store.current();
        if (snapshot.isEmpty()) {
            return degraded("no_data", ServerStatus.Kind.UNKNOWN, null);
        }
        Map<String, Object> data = snapshot.get();
        Object generatedAt = data.get("generatedAt");
        if (data.get("server") instanceof Map<?, ?> server && Boolean.FALSE.equals(server.get("online"))) {
            return degraded("stopped", ServerStatus.Kind.OFFLINE, generatedAt);
        }
        if (store.isStale(data)) {
            return degraded("stale", ServerStatus.Kind.STALE, generatedAt);
        }
        Map<String, Object> fresh = new LinkedHashMap<>(data);
        Map<String, Object> server = new LinkedHashMap<>();
        if (data.get("server") instanceof Map<?, ?> source) {
            source.forEach((key, value) -> server.put(String.valueOf(key), value));
        }
        server.put("status", ServerStatus.Kind.ONLINE.name());
        server.put("lastUpdate", generatedAt);
        fresh.put("server", server);
        return fresh;
    }

    public static boolean isOnline(Map<String, Object> state) {
        Object server = state.get("server");
        return server instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("online"));
    }

    private static Map<String, Object> degraded(String reason, ServerStatus.Kind status, Object lastUpdate) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("online", false);
        server.put("reason", reason);
        server.put("status", status.name());
        server.put("lastUpdate", lastUpdate instanceof String text ? text : null);
        root.put("server", server);

        root.put("players", List.of());
        root.put("leaderboards", Map.of());
        root.put("catalog", List.of());
        root.put("announcements", List.of());
        root.put("profiles", List.of());
        root.put("statistics", Map.of());
        return root;
    }
}
