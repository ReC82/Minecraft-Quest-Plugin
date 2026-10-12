package com.lodygames.rpgquest.spawn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import com.lodygames.rpgquest.RPGQuestPlugin;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #220 (consigne du 12/10/2026) — aucun lit, dans les claims ou ailleurs, ne fixe le point de
 * réapparition ; un point déjà enregistré est effacé ; le sommeil reste possible.
 */
class BedSpawnPolicyTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private World claims;
    private SpawnPlayerListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        claims = server.addSimpleWorld("claims");
        SpawnService service = new SpawnService(plugin, tempDir.resolve("spawn.yml"), NOPLogger.NOP_LOGGER, () -> "world_hub");
        listener = new SpawnPlayerListener(service);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void aBedNeverSetsTheRespawnPointAndThePlayerIsToldTheRealRule() {
        PlayerMock player = server.addPlayer();
        PlayerSetSpawnEvent event = new PlayerSetSpawnEvent(player, PlayerSetSpawnEvent.Cause.BED,
                new Location(claims, 10, 64, 10), false, true, Component.text("Point de réapparition défini"));

        listener.onSetSpawn(event);

        assertTrue(event.isCancelled(), "le lit n'enregistre pas de point de réapparition");
        String message = player.nextMessage();
        assertTrue(message != null && message.contains("un lit ne fixe pas ton point de réapparition"), message);
    }

    @Test
    void anotherCauseIsLeftAlone() {
        PlayerMock player = server.addPlayer();
        PlayerSetSpawnEvent event = new PlayerSetSpawnEvent(player, PlayerSetSpawnEvent.Cause.PLUGIN,
                new Location(claims, 10, 64, 10), false, false, null);

        listener.onSetSpawn(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void anAlreadyRecordedBedPointIsForgottenAtJoin() {
        PlayerMock player = server.addPlayer();
        player.setRespawnLocation(new Location(claims, 10, 64, 10), true);

        listener.onJoin(new PlayerJoinEvent(player, Component.empty()));

        assertNull(player.getRespawnLocation(), "le point hérité est effacé");
    }
}
