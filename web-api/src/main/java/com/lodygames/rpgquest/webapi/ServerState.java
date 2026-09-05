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
 */
public final class ServerState {

    private ServerState() {
    }

    public static Map<String, Object> resolve(SnapshotStore store) {
        Optional<Map<String, Object>> snapshot = store.current();
        if (snapshot.isEmpty()) {
            return degraded("no_data");
        }
        Map<String, Object> data = snapshot.get();
        if (store.isStale(data)) {
            return degraded("stale");
        }
        return data;
    }

    public static boolean isOnline(Map<String, Object> state) {
        Object server = state.get("server");
        return server instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("online"));
    }

    private static Map<String, Object> degraded(String reason) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("online", false);
        server.put("reason", reason);
        root.put("server", server);

        root.put("players", List.of());
        root.put("leaderboards", Map.of());
        root.put("catalog", List.of());
        root.put("announcements", List.of());
        return root;
    }
}
