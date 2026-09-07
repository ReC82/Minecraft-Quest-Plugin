package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Abstraction de configuration de l'agent (issue #51, phase 2) : fichier local + surcharge env, fail-closed. */
class AgentConfigLoaderTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("test");

    private AgentConfig load(Path file, Map<String, String> env) {
        return new AgentConfigLoader(file, env::get, LOG).load();
    }

    @Test
    void disabledByDefaultWhenNothingIsProvided(@TempDir Path dir) {
        AgentConfig config = load(dir.resolve("absent.properties"), Map.of());
        assertFalse(config.enabled(), "aucun fichier, aucune variable -> inerte");
    }

    @Test
    void enabledFromLocalFileAlone(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(AgentConfigLoader.FILE_NAME);
        Files.writeString(file, """
                enabled=true
                base-url=https://plugadmin.lodylands.com
                agent-id=rpgquest-dev
                environment=dev
                token=super-secret-token
                heartbeat-seconds=25
                poll-seconds=12
                """);
        AgentConfig config = load(file, Map.of());
        assertTrue(config.enabled());
        assertEquals("https://plugadmin.lodylands.com", config.baseUrl());
        assertEquals("rpgquest-dev", config.agentId());
        assertEquals("dev", config.environment());
        assertEquals(25, config.heartbeatSeconds());
        assertEquals(12, config.pollSeconds());
        assertEquals("https://plugadmin.lodylands.com/agent/v1", config.agentBaseUrl());
    }

    @Test
    void failsClosedWhenTokenMissing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(AgentConfigLoader.FILE_NAME);
        Files.writeString(file, """
                enabled=true
                base-url=https://plugadmin.lodylands.com
                agent-id=rpgquest-dev
                """);
        assertFalse(load(file, Map.of()).enabled(), "pas de token -> fail-closed");
    }

    @Test
    void environmentOverridesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(AgentConfigLoader.FILE_NAME);
        Files.writeString(file, """
                enabled=true
                base-url=https://old.example.com
                agent-id=rpgquest-dev
                token=file-token
                """);
        Map<String, String> env = new HashMap<>();
        env.put("RPGQUEST_PLUGADMIN_BASE_URL", "https://plugadmin.lodylands.com");
        env.put("RPGQUEST_PLUGADMIN_TOKEN", "env-token");
        AgentConfig config = load(file, env);
        assertTrue(config.enabled());
        assertEquals("https://plugadmin.lodylands.com", config.baseUrl());
        assertEquals("env-token", config.token());
    }

    @Test
    void rejectsNonHttpsBaseUrl(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(AgentConfigLoader.FILE_NAME);
        Files.writeString(file, """
                enabled=true
                base-url=http://plugadmin.lodylands.com
                agent-id=rpgquest-dev
                token=super-secret-token
                """);
        assertFalse(load(file, Map.of()).enabled(), "http non-local refusé");
    }

    @Test
    void toStringNeverLeaksTheToken(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(AgentConfigLoader.FILE_NAME);
        Files.writeString(file, """
                enabled=true
                base-url=https://plugadmin.lodylands.com
                agent-id=rpgquest-dev
                token=TOP-SECRET-VALUE-42
                """);
        String rendered = load(file, Map.of()).toString();
        assertFalse(rendered.contains("TOP-SECRET-VALUE-42"), "toString ne doit jamais contenir le jeton");
        assertTrue(rendered.contains("token=<défini>"));
    }
}
