package com.lodygames.rpgquest.web.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source des données du {@code /admin/v1/health} — isole {@link WebAdminServer} de l'API Bukkit
 * (l'implémentation réelle est {@link BukkitHealthSource} ; les tests fournissent une fausse
 * source). Toutes les valeurs sont l'état <strong>réel</strong> du serveur, jamais une lecture de
 * {@code data.db}.
 */
public interface HealthSource {

    /** Un monde RPGQuest essentiel : {@code role} = {@code hub}/{@code claims}/{@code wild}. */
    record WorldInfo(String role, String name, boolean loaded) {
    }

    String pluginName();

    String pluginVersion();

    int playersOnline();

    int maxPlayers();

    long uptimeSeconds();

    /** Étiquette d'environnement (ex. {@code DEV}), ou {@code unknown}. */
    String targetEnv();

    List<WorldInfo> essentialWorlds();

    /** Assemble la structure JSON complète renvoyée par la route health. */
    default Map<String, Object> healthPayload() {
        Map<String, Object> plugin = new LinkedHashMap<>();
        plugin.put("name", pluginName());
        plugin.put("version", pluginVersion());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("env", targetEnv());
        target.put("mode", "bridge");

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("players_online", playersOnline());
        server.put("max_players", maxPlayers());
        server.put("uptime_seconds", uptimeSeconds());

        Map<String, Object> worlds = new LinkedHashMap<>();
        for (WorldInfo world : essentialWorlds()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", world.name());
            entry.put("loaded", world.loaded());
            worlds.put(world.role(), entry);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("status", "ONLINE");
        root.put("plugin", plugin);
        root.put("bridge_api_version", WebAdminServer.API_VERSION);
        root.put("target", target);
        root.put("server", server);
        root.put("worlds", worlds);
        root.put("generated_at", java.time.Instant.now().toString());
        return root;
    }
}
