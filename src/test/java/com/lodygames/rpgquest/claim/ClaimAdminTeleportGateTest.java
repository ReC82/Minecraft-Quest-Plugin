package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.settings.GameSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #829, décision D1 : {@code /rpgadmin world tp <monde des claims>} et {@code /claim admin tp}
 * exigent une session bypass explicite — l'OP seul ne suffit jamais.
 */
class ClaimAdminTeleportGateTest {

    private ServerMock server;
    private final AdminBypassMode bypass = new AdminBypassMode(() -> GameSettings.DEFAULTS);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void anOpWithoutAnOpenBypassSessionIsRefused() {
        PlayerMock op = server.addPlayer();
        op.setOp(true);

        assertFalse(ClaimAdminTeleportGate.permits(bypass, op));
    }

    @Test
    void anOpenBypassSessionPermitsAndClosingItRefusesAgain() {
        PlayerMock op = server.addPlayer();
        op.setOp(true);
        assertEquals(AdminBypassMode.Outcome.ENABLED, bypass.enable(op));

        assertTrue(ClaimAdminTeleportGate.permits(bypass, op));

        bypass.disable(op.getUniqueId());
        assertFalse(ClaimAdminTeleportGate.permits(bypass, op));
    }

    @Test
    void aMissingBypassModeNeverPermits() {
        PlayerMock op = server.addPlayer();
        op.setOp(true);

        assertFalse(ClaimAdminTeleportGate.permits(null, op));
    }
}
