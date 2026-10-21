package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.dialogue.PlayerConditionEvaluator;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.PortalAccess;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.world.WorldService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #567 — conditions d'accès vérifiées par le serveur au franchissement d'un portail entre
 * mondes, simulation et « Téléporter pour test ». Joueurs fictifs MockBukkit seulement.
 */
class WorldPortalAccessListenerTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World hub;
    private World wild;
    private WorldPortalRegistry registry;
    private WorldPortalTeleportListener listener;
    private final Map<String, Instant> cooldowns = new HashMap<>();
    private PortalAccessChecker checker;
    private int playerLevel = 1;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hub = server.addSimpleWorld("world");
        wild = server.addSimpleWorld("wild");
        WorldService worldService = new WorldService(plugin, tempDir.resolve("worlds.yml"), plugin.getSLF4JLogger());
        worldService.start();
        registry = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        registry.start();
        listener = new WorldPortalTeleportListener(plugin, registry, worldService,
                () -> new RandomSafeArrivalConfig(500, 5000, 20), plugin.getSLF4JLogger());
        checker = new PortalAccessChecker(new PlayerConditionEvaluator(null, null, null, null), memoryCooldowns(),
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
        checker.setRequiredLevelRule(p -> playerLevel);
        listener.setAccessChecker(checker);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PortalAccessChecker.CooldownStore memoryCooldowns() {
        return new PortalAccessChecker.CooldownStore() {
            @Override
            public CompletableFuture<Optional<Instant>> expiry(UUID playerId, String key) {
                return CompletableFuture.completedFuture(Optional.ofNullable(cooldowns.get(playerId + key)));
            }

            @Override
            public void record(UUID playerId, String key, Instant expiry) {
                cooldowns.put(playerId + key, expiry);
            }
        };
    }

    private WorldPortalDefinition portal(boolean enabled, PortalAccess access) {
        return new WorldPortalDefinition("arche", "world", -5, 60, -5, 5, 70, 5, "wild", enabled,
                DestinationStrategy.WORLD_SPAWN, WorldPortalDefinition.Details.NONE, null, "", access);
    }

    private void enter(PlayerMock player) {
        listener.onMove(new PlayerMoveEvent(player, new Location(hub, 100.5, 64, 100.5), new Location(hub, 0.5, 64, 0.5)));
        server.getScheduler().performTicks(2);
    }

    private static String lastMessage(PlayerMock player) {
        String last = null;
        net.kyori.adventure.text.Component c;
        while ((c = player.nextComponentMessage()) != null) {
            last = PlainTextComponentSerializer.plainText().serialize(c);
        }
        return last;
    }

    @Test
    void aPortalWithoutConditionsTeleportsInTheSameTickEvenWithoutChecker() {
        listener.setAccessChecker(null);
        registry.create(portal(true, PortalAccess.NONE));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(hub, 100.5, 64, 100.5));

        listener.onMove(new PlayerMoveEvent(player, new Location(hub, 100.5, 64, 100.5), new Location(hub, 0.5, 64, 0.5)));

        assertEquals(wild, player.getWorld(), "chemin d'avant #567 : synchrone, aucune évaluation");
    }

    @Test
    void aRefusedPlayerStaysAndReceivesTheConditionMessage() {
        registry.create(portal(true, new PortalAccess(null, "", List.of(new PortalAccess.Rule(
                new HasPermissionCondition("rpgquest.portal.arche"), "<red>Réservé aux éclaireurs.</red>")), 0, "")));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(hub, 100.5, 64, 100.5));

        enter(player);

        assertEquals(hub, player.getWorld());
        assertEquals("Réservé aux éclaireurs.", lastMessage(player));
    }

    @Test
    void aPlayerMeetingEveryConditionIsTeleportedAndTheCooldownStarts() {
        registry.create(portal(true, new PortalAccess(3, "", List.of(new PortalAccess.Rule(
                new HasItemCondition(Material.DIAMOND, 2), "")), 60, "")));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(hub, 100.5, 64, 100.5));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 2));
        playerLevel = 3;

        enter(player);

        assertEquals(wild, player.getWorld());
        assertEquals(Instant.parse("2026-10-21T12:01:00Z"),
                cooldowns.get(player.getUniqueId() + PortalAccessChecker.cooldownKey("arche")));
        assertEquals(2, player.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount)
                .sum(), "une condition « avoir » ne retire rien");
    }

    @Test
    void theLevelRefusalUsesTheRequiredLevelAndNothingStarts() {
        registry.create(portal(true, new PortalAccess(5, "", List.of(), 60, "")));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(hub, 100.5, 64, 100.5));
        playerLevel = 4;

        enter(player);

        assertEquals(hub, player.getWorld());
        assertEquals("Il te faut le niveau 5 pour emprunter ce portail.", lastMessage(player));
        assertTrue(cooldowns.isEmpty(), "un passage refusé ne démarre aucun délai");
    }

    @Test
    void aRunningCooldownRefusesTheSecondPassage() {
        registry.create(portal(true, new PortalAccess(null, "", List.of(), 60, "")));
        PlayerMock player = server.addPlayer();
        cooldowns.put(player.getUniqueId() + PortalAccessChecker.cooldownKey("arche"),
                Instant.parse("2026-10-21T12:00:30Z"));
        player.teleport(new Location(hub, 100.5, 64, 100.5));

        enter(player);

        assertEquals(hub, player.getWorld());
        assertEquals("Ce portail se recharge : réessaie dans 31 s.", lastMessage(player));
    }

    @Test
    void conditionsWithoutCheckerRefuseThePassage() {
        listener.setAccessChecker(null);
        registry.create(portal(true, new PortalAccess(2, "", List.of(), 0, "")));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(hub, 100.5, 64, 100.5));
        playerLevel = 99;

        enter(player);

        assertEquals(hub, player.getWorld(), "jamais de passage sans vérification");
    }

    @Test
    void theEntryGuardStillRunsFirst() {
        registry.create(portal(true, new PortalAccess(2, "", List.of(), 0, "")));
        listener.setEntryGuard((p, portal) -> {
            p.sendMessage("garde");
            return false;
        });
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(hub, 100.5, 64, 100.5));
        playerLevel = 1;

        enter(player);

        assertEquals(hub, player.getWorld());
        assertEquals("garde", lastMessage(player), "le garde a la main, les conditions ne sont pas évaluées");
    }

    @Test
    void testTeleportOfADraftUsesTheRealPathWithoutCooldown() throws Exception {
        WorldPortalDefinition draft = portal(false, new PortalAccess(2, "", List.of(), 60, ""));
        registry.create(draft);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(hub, 100.5, 64, 100.5));
        playerLevel = 2;
        cooldowns.put(player.getUniqueId() + PortalAccessChecker.cooldownKey("arche"),
                Instant.parse("2026-10-21T13:00:00Z"));

        CompletableFuture<WorldPortalTeleportListener.TestResult> result = listener.testTeleport(player, draft);
        server.getScheduler().performTicks(2);

        assertEquals(WorldPortalTeleportListener.TestOutcome.TELEPORTING,
                result.get(5, TimeUnit.SECONDS).outcome());
        assertEquals(wild, player.getWorld());
        assertEquals(Instant.parse("2026-10-21T13:00:00Z"),
                cooldowns.get(player.getUniqueId() + PortalAccessChecker.cooldownKey("arche")),
                "le test ne lit ni ne démarre le délai");
    }

    @Test
    void testTeleportRefusedMovesNobody() throws Exception {
        WorldPortalDefinition draft = portal(false, new PortalAccess(9, "", List.of(), 0, ""));
        registry.create(draft);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(hub, 100.5, 64, 100.5));

        CompletableFuture<WorldPortalTeleportListener.TestResult> result = listener.testTeleport(player, draft);
        server.getScheduler().performTicks(2);

        WorldPortalTeleportListener.TestResult r = result.get(5, TimeUnit.SECONDS);
        assertEquals(WorldPortalTeleportListener.TestOutcome.REFUSED, r.outcome());
        assertFalse(r.verdict().allowed());
        assertEquals(hub, player.getWorld());
    }
}
