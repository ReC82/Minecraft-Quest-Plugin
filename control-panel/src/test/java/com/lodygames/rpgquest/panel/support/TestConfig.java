package com.lodygames.rpgquest.panel.support;

import com.lodygames.rpgquest.panel.agent.AgentIdentity;
import com.lodygames.rpgquest.panel.agent.AgentLiveness;
import com.lodygames.rpgquest.panel.agent.AgentSettings;
import com.lodygames.rpgquest.panel.config.PanelConfig;
import com.lodygames.rpgquest.panel.config.Target;
import com.lodygames.rpgquest.panel.security.PasswordHasher;
import java.time.Duration;
import java.util.List;

/** Fabrique de {@link PanelConfig} pour les tests (secrets factices, cookies non-Secure, port 0). */
public final class TestConfig {

    public static final String SESSION_SECRET = "test-session-secret-0123456789-abcdefgh";
    public static final String OWNER_USERNAME = "owner";
    public static final String OWNER_PASSWORD = "correct horse battery staple";
    public static final String OWNER_HASH = new PasswordHasher().hash(OWNER_PASSWORD);
    public static final String BRIDGE_TOKEN = "bridge-token-abc-123";
    public static final String AGENT_ID = "rpgquest-dev";
    public static final String AGENT_TOKEN = "agent-token-xyz-789";

    private TestConfig() {
    }

    public static PanelConfig withBridgeUrl(String dbPath, String bridgeUrl) {
        return build(dbPath, bridgeUrl, false, AgentSettings.none());
    }

    public static PanelConfig disabled(String dbPath) {
        return build(dbPath, "http://127.0.0.1:1/admin/v1", true, AgentSettings.none());
    }

    /** Config avec un agent {@code rpgquest-dev} déclaré (jeton {@link #AGENT_TOKEN}). */
    public static PanelConfig withAgent(String dbPath, String bridgeUrl) {
        AgentSettings agents = new AgentSettings(
                List.of(new AgentIdentity(AGENT_ID, "dev", AGENT_TOKEN)),
                new AgentLiveness.Thresholds(45, 150), Duration.ofMinutes(5), AGENT_ID);
        return build(dbPath, bridgeUrl, false, agents);
    }

    private static PanelConfig build(String dbPath, String bridgeUrl, boolean disabled, AgentSettings agents) {
        Target dev = new Target("dev", "RPGQuest DEV", Target.Mode.BRIDGE, bridgeUrl, BRIDGE_TOKEN);
        return new PanelConfig(
                0, "127.0.0.1", "", disabled, false, 120, 30, dbPath,
                OWNER_USERNAME, OWNER_HASH, SESSION_SECRET, List.of(dev), "dev", agents);
    }
}
