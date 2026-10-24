package com.lodygames.rpgquest.webapi;

import java.time.Instant;
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
 * dernier relevé. Hors {@code ONLINE}, les données de jeu restent repliées sur le mode dégradé. En
 * ligne, elles viennent du dernier instantané complet tant qu'il a moins de
 * {@code snapshot-data-max-age-seconds} ({@link #SYNCED_AT} le date).</p>
 */
public final class ServerState {

    /**
     * Date de génération de l'instantané complet dont viennent les données de jeu affichées, absente
     * si aucune n'est affichée (#421 : « dernière synchronisation »).
     */
    public static final String SYNCED_AT = "syncedAt";

    private ServerState() {
    }

    public static Map<String, Object> resolve(SnapshotStore store) {
        Optional<Map<String, Object>> snapshot = store.current();
        Optional<Map<String, Object>> heartbeat = store.currentHeartbeat();
        if (heartbeat.isPresent() && isNewerThan(heartbeat.get(), snapshot)) {
            return fromHeartbeat(store, heartbeat.get(), snapshot);
        }
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
        fresh.put("server", onlineServer(data.get("server"), generatedAt));
        fresh.put(SYNCED_AT, generatedAt);
        return fresh;
    }

    /**
     * #421 — le relevé d'état agrégé fait foi pour l'état du serveur dès qu'il est au moins aussi
     * récent que l'instantané complet. Les données de jeu (classements, catalogue…) ne viennent que
     * d'un instantané complet assez récent ({@link SnapshotStore#isDataStale}) : sans lui, elles
     * restent vides, jamais inventées.
     */
    private static Map<String, Object> fromHeartbeat(SnapshotStore store, Map<String, Object> heartbeat,
                                                     Optional<Map<String, Object>> snapshot) {
        Object receivedAt = heartbeat.get("receivedAt");
        if (heartbeat.get("server") instanceof Map<?, ?> server && Boolean.FALSE.equals(server.get("online"))) {
            return degraded("stopped", ServerStatus.Kind.OFFLINE, receivedAt);
        }
        if (store.isHeartbeatStale(heartbeat)) {
            return degraded("stale", ServerStatus.Kind.STALE, receivedAt);
        }
        Map<String, Object> fresh = degraded("online", ServerStatus.Kind.ONLINE, receivedAt);
        Map<String, Object> server = new LinkedHashMap<>();
        snapshot.filter(data -> !store.isDataStale(data) && isOnline(data)).ifPresent(data -> {
            fresh.putAll(data);
            copyInto(server, data.get("server"));
            fresh.put(SYNCED_AT, data.get("generatedAt"));
        });
        // Les compteurs du relevé, plus récent, remplacent ceux de l'instantané : jamais additionnés.
        copyInto(server, heartbeat.get("server"));
        fresh.put("server", onlineServer(server, receivedAt));
        return fresh;
    }

    private static boolean isNewerThan(Map<String, Object> heartbeat, Optional<Map<String, Object>> snapshot) {
        if (snapshot.isEmpty()) {
            return true;
        }
        Instant beat = SnapshotStore.instant(heartbeat.get("receivedAt"));
        Instant full = SnapshotStore.instant(snapshot.get().get("generatedAt"));
        return full == null || (beat != null && !beat.isBefore(full));
    }

    private static void copyInto(Map<String, Object> target, Object source) {
        if (source instanceof Map<?, ?> map) {
            map.forEach((key, value) -> target.put(String.valueOf(key), value));
        }
    }

    private static Map<String, Object> onlineServer(Object source, Object lastUpdate) {
        Map<String, Object> server = new LinkedHashMap<>();
        copyInto(server, source);
        server.put("online", true);
        server.put("status", ServerStatus.Kind.ONLINE.name());
        server.put("lastUpdate", lastUpdate);
        return server;
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
