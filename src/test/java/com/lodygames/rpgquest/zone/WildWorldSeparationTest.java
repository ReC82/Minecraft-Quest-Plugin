package com.lodygames.rpgquest.zone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * Vérifie explicitement que les protections du Hub (zone protégée) ne débordent jamais sur le
 * monde {@code wild} : {@code ZoneProtectionListener} ne s'appuie que sur {@code
 * ZoneRegistry#zoneAt(world, x, y, z)}, qui n'existe que là où une zone a été enregistrée
 * explicitement — rien n'enregistre de zone dans {@code wild}, donc chaque protection reste
 * inactive là-bas par construction, sans code de plus. Chaque test ci-dessous reproduit le même
 * événement dans la zone protégée du Hub (annulé) puis dans {@code wild} (jamais annulé), pour
 * documenter cette garantie de façon vivante — voir docs-site/worlds.html, section « Règles du
 * world wild ».
 */
@SuppressWarnings("removal") // EntityDamageByEntityEvent(Entity, Entity, DamageCause, double) — voir ZoneProtectionListenerTest.
class WildWorldSeparationTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World hub;
    private World wild;
    private DatabaseManager database;
    private ZoneProtectionListener listener;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hub = server.addSimpleWorld("world");
        wild = server.addSimpleWorld("wild");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));

        ZoneRegistry registry = new ZoneRegistry(tempDir.resolve("zones"), NOPLogger.NOP_LOGGER);
        // Zone protégée dans "world" (le Hub) uniquement — jamais de zone enregistrée dans "wild".
        registry.create(new ZoneDefinition("hub", "world", -10, 0, -10, 10, 255, 10, ZoneFlags.defaults()));

        listener = new ZoneProtectionListener(registry, npcIdentityService, com.lodygames.rpgquest.permission.TestBuildPermissions.standard());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void blockBreakIsCancelledInTheHubButAllowedInWild() {
        PlayerMock player = server.addPlayer();

        BlockBreakEvent inHub = new BlockBreakEvent(hub.getBlockAt(0, 64, 0), player);
        listener.onBreak(inHub);
        assertTrue(inHub.isCancelled(), "le Hub doit rester protégé exactement comme avant");

        BlockBreakEvent inWild = new BlockBreakEvent(wild.getBlockAt(0, 64, 0), player);
        listener.onBreak(inWild);
        assertFalse(inWild.isCancelled(), "wild doit se comporter comme un monde vanilla (casse autorisée)");
    }

    @Test
    void pvpDamageIsCancelledInTheHubButAllowedInWild() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();

        victim.teleport(new Location(hub, 0.5, 64, 0.5));
        EntityDamageByEntityEvent inHub = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onEntityDamage(inHub);
        assertTrue(inHub.isCancelled());

        victim.teleport(new Location(wild, 0.5, 64, 0.5));
        EntityDamageByEntityEvent inWild = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onEntityDamage(inWild);
        assertFalse(inWild.isCancelled(), "PvP doit rester autorisé dans wild");
    }

    @Test
    void hostileMobDamageIsCancelledInTheHubButAllowedInWild() {
        var zombieInHub = hub.spawn(new Location(hub, 0.5, 64, 0.5), Zombie.class);
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(hub, 0.5, 64, 0.5));
        EntityDamageByEntityEvent inHub = new EntityDamageByEntityEvent(
                zombieInHub, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onEntityDamage(inHub);
        assertTrue(inHub.isCancelled());

        var zombieInWild = wild.spawn(new Location(wild, 0.5, 64, 0.5), Zombie.class);
        victim.teleport(new Location(wild, 0.5, 64, 0.5));
        EntityDamageByEntityEvent inWild = new EntityDamageByEntityEvent(
                zombieInWild, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onEntityDamage(inWild);
        assertFalse(inWild.isCancelled(), "les dégâts de mob hostile doivent rester autorisés dans wild");
    }

    @Test
    void environmentalDamageIsCancelledInTheHubButAllowedInWild() {
        PlayerMock victim = server.addPlayer();

        victim.teleport(new Location(hub, 0.5, 64, 0.5));
        EntityDamageEvent inHub = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.FALL, 5.0);
        listener.onEntityDamage(inHub);
        assertTrue(inHub.isCancelled());

        victim.teleport(new Location(wild, 0.5, 64, 0.5));
        EntityDamageEvent inWild = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.FALL, 5.0);
        listener.onEntityDamage(inWild);
        assertFalse(inWild.isCancelled(), "la mort (chute, noyade...) doit rester possible dans wild");
    }

    @Test
    void explosionClearsBlockListInTheHubButNotInWild() {
        var creeperInHub = hub.spawn(new Location(hub, 0.5, 64, 0.5), Creeper.class);
        Block blockInHub = hub.getBlockAt(0, 64, 0);
        blockInHub.setType(Material.STONE);
        EntityExplodeEvent inHub = new EntityExplodeEvent(creeperInHub, blockInHub.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(blockInHub)), 0f, org.bukkit.ExplosionResult.DESTROY);
        listener.onEntityExplode(inHub);
        assertTrue(inHub.blockList().isEmpty(), "explosions bloquées côté destruction dans le Hub");

        var creeperInWild = wild.spawn(new Location(wild, 0.5, 64, 0.5), Creeper.class);
        Block blockInWild = wild.getBlockAt(0, 64, 0);
        blockInWild.setType(Material.STONE);
        EntityExplodeEvent inWild = new EntityExplodeEvent(creeperInWild, blockInWild.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(blockInWild)), 0f, org.bukkit.ExplosionResult.DESTROY);
        listener.onEntityExplode(inWild);
        assertFalse(inWild.blockList().isEmpty(), "les explosions doivent se comporter en vanilla dans wild");
    }

    @Test
    void naturalHostileSpawnIsCancelledInTheHubButAllowedInWild() {
        var zombieInHub = hub.spawn(new Location(hub, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent inHub = new CreatureSpawnEvent(zombieInHub, CreatureSpawnEvent.SpawnReason.NATURAL);
        listener.onCreatureSpawn(inHub);
        assertTrue(inHub.isCancelled());

        var zombieInWild = wild.spawn(new Location(wild, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent inWild = new CreatureSpawnEvent(zombieInWild, CreatureSpawnEvent.SpawnReason.NATURAL);
        listener.onCreatureSpawn(inWild);
        assertFalse(inWild.isCancelled(), "les mobs hostiles doivent pouvoir apparaître normalement dans wild");
    }
}
