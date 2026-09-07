package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Fraîcheur ONLINE/STALE/OFFLINE (issue #51, phase 5) — indépendante de toute session navigateur. */
class AgentLivenessTest {

    private static final AgentLiveness.Thresholds T = new AgentLiveness.Thresholds(45, 150);
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private static Optional<HeartbeatRecord> hbAt(Instant receivedAt) {
        return Optional.of(new HeartbeatRecord("rpgquest-dev", "dev", receivedAt, null, "agent/v1",
                "RPGQuest", "0.1", "ONLINE", 1, 20, 10, "{}", "{}"));
    }

    @Test
    void unknownWhenNoHeartbeat() {
        assertEquals(AgentLiveness.UNKNOWN, AgentLiveness.of(Optional.empty(), T, NOW));
    }

    @Test
    void onlineWhenRecent() {
        assertEquals(AgentLiveness.ONLINE, AgentLiveness.of(hbAt(NOW.minusSeconds(8)), T, NOW));
        assertEquals(AgentLiveness.ONLINE, AgentLiveness.of(hbAt(NOW.minusSeconds(45)), T, NOW));
    }

    @Test
    void staleBetweenThresholds() {
        assertEquals(AgentLiveness.STALE, AgentLiveness.of(hbAt(NOW.minusSeconds(46)), T, NOW));
        assertEquals(AgentLiveness.STALE, AgentLiveness.of(hbAt(NOW.minusSeconds(150)), T, NOW));
    }

    @Test
    void offlineBeyondOfflineThreshold() {
        assertEquals(AgentLiveness.OFFLINE, AgentLiveness.of(hbAt(NOW.minusSeconds(151)), T, NOW));
        assertEquals(AgentLiveness.OFFLINE, AgentLiveness.of(hbAt(NOW.minusSeconds(9999)), T, NOW));
    }
}
