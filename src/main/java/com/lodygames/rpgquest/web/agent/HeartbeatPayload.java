package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.web.admin.HealthSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Construit le corps du heartbeat {@code POST /agent/v1/heartbeat} (issue #51, phase 4).
 *
 * <p><strong>Aucune logique de health dupliquée</strong> : l'état serveur vient intégralement de
 * {@link HealthSource} (la même source que le bridge local {@code /admin/v1/health} de #37). Ce
 * builder ne fait qu'emballer ces valeurs avec l'identité de l'agent et un horodatage serveur.</p>
 */
public final class HeartbeatPayload {

    private final HealthSource health;

    public HeartbeatPayload(HealthSource health) {
        this.health = health;
    }

    public Map<String, Object> build(AgentConfig config) {
        Map<String, Object> worlds = new LinkedHashMap<>();
        for (HealthSource.WorldInfo world : health.essentialWorlds()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", world.name());
            entry.put("loaded", world.loaded());
            worlds.put(world.role(), entry);
        }

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("state", "ONLINE");
        server.put("players_online", health.playersOnline());
        server.put("max_players", health.maxPlayers());
        server.put("uptime_seconds", health.uptimeSeconds());

        Map<String, Object> plugin = new LinkedHashMap<>();
        plugin.put("name", health.pluginName());
        plugin.put("version", health.pluginVersion());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("protocol", AgentConfig.PROTOCOL);
        root.put("agent_id", config.agentId());
        root.put("environment", config.environment());
        root.put("target_env", health.targetEnv());
        root.put("plugin", plugin);
        root.put("server", server);
        root.put("worlds", worlds);
        root.put("generated_at", Instant.now().toString());
        return root;
    }
}
