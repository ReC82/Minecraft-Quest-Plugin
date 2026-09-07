package com.lodygames.rpgquest.panel.bridge;

import com.lodygames.rpgquest.panel.json.Json;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vue typée et tolérante de la réponse {@code GET /admin/v1/health} du bridge. Un champ absent ou
 * d'un type inattendu devient {@code null}/{@code -1}/liste vide — ne jamais faire échouer le
 * dashboard sur une évolution mineure du contrat (le contrat est versionné, mais la lecture reste
 * défensive).
 */
public record BridgeHealth(
        String status,
        String pluginName,
        String pluginVersion,
        String bridgeApiVersion,
        String targetEnv,
        long playersOnline,
        long maxPlayers,
        long uptimeSeconds,
        List<WorldStatus> worlds,
        String generatedAt) {

    public record WorldStatus(String role, String name, boolean loaded) {
    }

    public static BridgeHealth fromJson(String body) {
        Map<String, Object> root = Json.parseObject(body);
        Map<String, Object> plugin = obj(root.get("plugin"));
        Map<String, Object> server = obj(root.get("server"));
        Map<String, Object> target = obj(root.get("target"));
        Map<String, Object> worlds = obj(root.get("worlds"));

        List<WorldStatus> worldList = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : worlds.entrySet()) {
            Map<String, Object> w = obj(e.getValue());
            worldList.add(new WorldStatus(e.getKey(), str(w.get("name")), bool(w.get("loaded"))));
        }

        return new BridgeHealth(
                str(root.get("status")),
                str(plugin.get("name")),
                str(plugin.get("version")),
                str(root.get("bridge_api_version")),
                str(target.get("env")),
                lng(server.get("players_online")),
                lng(server.get("max_players")),
                lng(server.get("uptime_seconds")),
                List.copyOf(worldList),
                str(root.get("generated_at")));
    }

    public boolean allEssentialWorldsLoaded() {
        return !worlds.isEmpty() && worlds.stream().allMatch(WorldStatus::loaded);
    }

    public String uptimeHuman() {
        if (uptimeSeconds < 0) {
            return "—";
        }
        long s = uptimeSeconds;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        if (d > 0) {
            return d + "j " + h + "h " + m + "min";
        }
        if (h > 0) {
            return h + "h " + m + "min";
        }
        return m + "min " + (s % 60) + "s";
    }

    public String generatedAtHuman() {
        try {
            return Instant.parse(generatedAt).toString();
        } catch (RuntimeException e) {
            return generatedAt == null ? "—" : generatedAt;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }

    private static long lng(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return -1;
    }
}
