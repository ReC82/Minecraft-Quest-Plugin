package com.lodygames.rpgquest.zone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

@SuppressWarnings("removal") // EntityDamageByEntityEvent(Entity, Entity, DamageCause, double) : même
// dépréciation « for removal » déjà documentée pour WeaponBehaviorListenerTest (docs/ARCHITECTURE.md,
// Décisions techniques) — le remplacement complet exige une Map<DamageModifier, ...> interne au
// moteur vanilla, disproportionné pour un fixture de test.
class ZoneProtectionListenerTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private World world;
    private ZoneRegistry registry;
    private ZoneProtectionListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");

        registry = new ZoneRegistry(tempDir.resolve("zones"), NOPLogger.NOP_LOGGER);
        // Zone protégée (PvP/casse/explosions bloqués) couvrant -10..10 sur x/z.
        registry.create(new ZoneDefinition("safe", "world", -10, 0, -10, 10, 255, 10, ZoneFlags.defaults()));

        listener = new ZoneProtectionListener(registry);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void blockBreakInsideZoneIsCancelled() {
        PlayerMock player = server.addPlayer();
        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBreak(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void blockBreakOutsideZoneIsAllowed() {
        PlayerMock player = server.addPlayer();
        Block block = world.getBlockAt(1000, 64, 1000);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBreak(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void blockBreakOnTheBorderIsCancelled() {
        PlayerMock player = server.addPlayer();
        Block block = world.getBlockAt(10, 64, 10); // borne max, incluse
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBreak(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void blockBreakByABypassingAdminIsAllowed() {
        PlayerMock admin = server.addPlayer();
        admin.setOp(true); // rpgquest.admin.world est "default: op"
        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, admin);

        listener.onBreak(event);

        assertFalse(event.isCancelled(), "un administrateur doit pouvoir agir dans la zone");
    }

    @Test
    void alreadyCancelledBlockBreakEventIsLeftAlone() {
        PlayerMock player = server.addPlayer();
        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setCancelled(true);

        // ignoreCancelled = true sur le vrai listener empêche même l'appel ; on vérifie ici que
        // rappeler explicitement la méthode ne "décancelle" jamais un événement déjà annulé ailleurs.
        listener.onBreak(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void blockPlaceInsideZoneIsCancelled() {
        PlayerMock player = server.addPlayer();
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), block.getRelative(0, -1, 0),
                new ItemStack(Material.STONE), player, true, EquipmentSlot.HAND);

        listener.onPlace(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void pvpDamageInsideZoneIsCancelled() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        victim.teleport(new org.bukkit.Location(world, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void pvpDamageOutsideZoneIsAllowed() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        victim.teleport(new org.bukkit.Location(world, 1000.5, 64, 1000.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void pvpDamageByABypassingAdminIsAllowed() {
        PlayerMock attacker = server.addPlayer();
        attacker.setOp(true);
        PlayerMock victim = server.addPlayer();
        victim.teleport(new org.bukkit.Location(world, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void nonPlayerVictimDamageIsIgnored() {
        // Pas de joueur impliqué comme victime : la zone ne doit rien faire (et ne pas planter).
        var zombie = world.spawn(new org.bukkit.Location(world, 0.5, 64, 0.5), org.bukkit.entity.Zombie.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                server.addPlayer(), zombie, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void explosionInsideZoneClearsTheBlockList() {
        var creeper = world.spawn(new org.bukkit.Location(world, 0.5, 64, 0.5), org.bukkit.entity.Creeper.class);
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertTrue(event.blockList().isEmpty(), "aucune destruction de bloc dans une zone sans explosions");
    }

    @Test
    void explosionOutsideZoneKeepsTheBlockList() {
        var creeper = world.spawn(new org.bukkit.Location(world, 1000.5, 64, 1000.5), org.bukkit.entity.Creeper.class);
        Block block = world.getBlockAt(1000, 64, 1000);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertFalse(event.blockList().isEmpty());
    }

    @Test
    void zoneCheckOnAWorldWithNoZonesNeverThrows() {
        World otherWorld = server.addSimpleWorld("other_world");
        PlayerMock player = server.addPlayer();
        Block block = otherWorld.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> listener.onBreak(event));
        assertFalse(event.isCancelled());
    }
}
