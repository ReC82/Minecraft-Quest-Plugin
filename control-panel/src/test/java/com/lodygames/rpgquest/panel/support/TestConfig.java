package com.lodygames.rpgquest.panel.support;

import com.lodygames.rpgquest.panel.config.PanelConfig;
import com.lodygames.rpgquest.panel.config.Target;
import com.lodygames.rpgquest.panel.security.PasswordHasher;
import java.util.List;

/** Fabrique de {@link PanelConfig} pour les tests (secrets factices, cookies non-Secure, port 0). */
public final class TestConfig {

    public static final String SESSION_SECRET = "test-session-secret-0123456789-abcdefgh";
    public static final String OWNER_USERNAME = "owner";
    public static final String OWNER_PASSWORD = "correct horse battery staple";
    public static final String OWNER_HASH = new PasswordHasher().hash(OWNER_PASSWORD);
    public static final String BRIDGE_TOKEN = "bridge-token-abc-123";

    private TestConfig() {
    }

    public static PanelConfig withBridgeUrl(String dbPath, String bridgeUrl) {
        Target dev = new Target("dev", "RPGQuest DEV", Target.Mode.BRIDGE, bridgeUrl, BRIDGE_TOKEN);
        return new PanelConfig(
                0, "127.0.0.1", "", false, false, 120, 30, dbPath,
                OWNER_USERNAME, OWNER_HASH, SESSION_SECRET, List.of(dev), "dev");
    }

    public static PanelConfig disabled(String dbPath) {
        Target dev = new Target("dev", "RPGQuest DEV", Target.Mode.BRIDGE, "http://127.0.0.1:1/admin/v1", BRIDGE_TOKEN);
        return new PanelConfig(
                0, "127.0.0.1", "", true, false, 120, 30, dbPath,
                OWNER_USERNAME, OWNER_HASH, SESSION_SECRET, List.of(dev), "dev");
    }
}
