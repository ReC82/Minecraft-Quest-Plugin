package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.lodygames.rpgquest.web.admin.HealthSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #42 — le heartbeat porte le backend de base de données actif, sans aucun détail d'accès. */
class HeartbeatPayloadTest {

    private static HealthSource health(String engine, int version) {
        return new HealthSource() {
            @Override public String pluginName() {
                return "RPGQuest";
            }

            @Override public String pluginVersion() {
                return "test";
            }

            @Override public int playersOnline() {
                return 0;
            }

            @Override public int maxPlayers() {
                return 20;
            }

            @Override public long uptimeSeconds() {
                return 1;
            }

            @Override public String targetEnv() {
                return "dev";
            }

            @Override public List<WorldInfo> essentialWorlds() {
                return List.of();
            }

            @Override public String databaseEngine() {
                return engine == null ? HealthSource.super.databaseEngine() : engine;
            }

            @Override public int databaseSchemaVersion() {
                return version;
            }
        };
    }

    private static AgentConfig config() {
        return new AgentConfig(true, "https://plugadmin.example", "rpgquest-dev", "dev", "tok",
                20, 15, 5000, 10000, 300, true, 256 * 1024L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void heartbeatReportsActiveDatabaseEngine() {
        Map<String, Object> body = new HeartbeatPayload(health("sqlite", 34)).build(config());

        Map<String, Object> database = (Map<String, Object>) body.get("database");
        assertEquals("sqlite", database.get("engine"));
        assertEquals(34, database.get("schema_version"));
        assertEquals(2, database.size(), "ni hôte, ni compte, ni secret");
        assertFalse(body.toString().contains("password"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void unknownEngineByDefault() {
        Map<String, Object> body = new HeartbeatPayload(health(null, 0)).build(config());

        assertEquals("unknown", ((Map<String, Object>) body.get("database")).get("engine"));
    }
}
