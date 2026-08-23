package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.ClaimConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Mission « bloquer le Nether depuis claims » : couvre le refus, la bascule config, et la non-interférence. */
class ClaimNetherTravelListenerTest {

    private static final ClaimConfig BLOCKING_CONFIG = new ClaimConfig(64, 384, 3, 16, "claims", true);
    private static final ClaimConfig NON_BLOCKING_CONFIG = new ClaimConfig(64, 384, 3, 16, "claims", false);

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World claimsWorld;
    private World other;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        claimsWorld = server.addSimpleWorld("claims");
        other = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void netherPortalFromTheClaimsWorldIsCancelled() {
        ClaimNetherTravelListener listener = new ClaimNetherTravelListener(() -> BLOCKING_CONFIG);
        PlayerMock player = server.addPlayer();
        Location from = new Location(claimsWorld, 0.5, 64, 0.5);
        Location to = new Location(other, 0.5, 64, 0.5); // destination Nether réelle non simulable par MockBukkit : monde quelconque suffit.
        PlayerPortalEvent event = new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(event);

        assertTrue(event.isCancelled(), "un portail Nether activé depuis claims doit être refusé");
        assertTrue(player.nextMessage() != null, "un message doit expliquer le refus au joueur");
    }

    @Test
    void netherPortalFromAnotherWorldIsNeverCancelled() {
        ClaimNetherTravelListener listener = new ClaimNetherTravelListener(() -> BLOCKING_CONFIG);
        PlayerMock player = server.addPlayer();
        Location from = new Location(other, 0.5, 64, 0.5);
        Location to = new Location(claimsWorld, 0.5, 64, 0.5);
        PlayerPortalEvent event = new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(event);

        assertFalse(event.isCancelled(), "un portail Nether ailleurs (y compris un retour vers claims) n'est jamais concerné");
    }

    @Test
    void nonNetherPortalCauseFromClaimsIsNeverCancelled() {
        ClaimNetherTravelListener listener = new ClaimNetherTravelListener(() -> BLOCKING_CONFIG);
        PlayerMock player = server.addPlayer();
        Location from = new Location(claimsWorld, 0.5, 64, 0.5);
        Location to = new Location(claimsWorld, 10.5, 64, 10.5);
        PlayerPortalEvent event = new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPortal(event);

        assertFalse(event.isCancelled(), "seul NETHER_PORTAL est concerné, jamais END_PORTAL");
    }

    @Test
    void togglingBlockNetherTravelToFalseReenablesItWithoutCodeChange() {
        ClaimNetherTravelListener listener = new ClaimNetherTravelListener(() -> NON_BLOCKING_CONFIG);
        PlayerMock player = server.addPlayer();
        Location from = new Location(claimsWorld, 0.5, 64, 0.5);
        Location to = new Location(other, 0.5, 64, 0.5);
        PlayerPortalEvent event = new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(event);

        assertFalse(event.isCancelled(), "claims.block-nether-travel=false doit réautoriser instantanément");
    }
}
