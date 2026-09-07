package com.lodygames.rpgquest.panel.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.config.Target;
import com.lodygames.rpgquest.panel.support.StubBridge;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BridgeClientTest {

    private final BridgeClient client = new BridgeClient(Duration.ofMillis(400), Duration.ofMillis(600));

    private static Target target(String url, String token) {
        return new Target("dev", "RPGQuest DEV", Target.Mode.BRIDGE, url, token);
    }

    @Test
    void readsHealthFromAReachableBridge() throws Exception {
        try (StubBridge stub = new StubBridge("tok")) {
            BridgeHealth health = client.health(target(stub.baseUrl(), "tok"));
            assertEquals("ONLINE", health.status());
            assertEquals("1.2.3-test", health.pluginVersion());
            assertEquals("v1", health.bridgeApiVersion());
            assertEquals(2, health.playersOnline());
            assertEquals(20, health.maxPlayers());
            assertEquals(3, health.worlds().size());
            assertTrue(health.worlds().stream().anyMatch(w -> w.role().equals("hub") && w.loaded()));
            assertTrue(health.worlds().stream().anyMatch(w -> w.role().equals("wild") && !w.loaded()));
            assertFalse(health.allEssentialWorldsLoaded());
        }
    }

    @Test
    void rejectedTokenBecomesAReadableBridgeException() throws Exception {
        try (StubBridge stub = new StubBridge("the-real-token")) {
            BridgeException ex = assertThrows(BridgeException.class,
                    () -> client.health(target(stub.baseUrl(), "wrong-token")));
            assertTrue(ex.getMessage().toLowerCase().contains("refus"));
        }
    }

    @Test
    void unreachableBridgeBecomesAReadableBridgeException() {
        BridgeException ex = assertThrows(BridgeException.class,
                () -> client.health(target("http://127.0.0.1:2/admin/v1", "tok")));
        assertTrue(ex.getMessage().toLowerCase().contains("injoignable"));
    }

    @Test
    void unconfiguredTargetIsRejectedBeforeAnyNetworkCall() {
        BridgeException ex = assertThrows(BridgeException.class,
                () -> client.health(target("http://127.0.0.1:1/admin/v1", "")));
        assertTrue(ex.getMessage().contains("non configurée"));
    }
}
