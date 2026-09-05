package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.HubConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Couvre les protections d'événement du monde Hub (dégâts/PvP, casse/pose de bloc, spawn de mob
 * hostile, explosions), scoped par nom de monde — voir {@code hub.HubWorldRulesService} pour les
 * règles réelles de monde (jour/météo), testées séparément.
 */
@SuppressWarnings("removal") // EntityDamageByEntityEvent(Entity, Entity, DamageCause, double) — voir ZoneProtectionListenerTest.
class HubWorldProtectionListenerTest {

    private static final HubConfig HUB_CONFIG = new HubConfig("world_hub");

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World hub;
    private World other;
    private HubWorldProtectionListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hub = server.addSimpleWorld("world_hub");
        other = server.addSimpleWorld("world");

        listener = new HubWorldProtectionListener(() -> HUB_CONFIG);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- Dégâts / PvP ---------------------------------------------------------------------------

    @Test
    void anyDamageToAPlayerInTheHubIsCancelled() {
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(hub, 0.5, 64, 0.5));
        EntityDamageEvent event = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.FALL, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void pvpDamageInTheHubIsCancelled() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(hub, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled(), "aucun dégât aux joueurs couvre déjà le PvP");
    }

    @Test
    void damageInAnotherWorldIsNeverCancelled() {
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(other, 0.5, 64, 0.5));
        EntityDamageEvent event = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.FALL, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    // ---- Blocs ------------------------------------------------------------------------------------

    @Test
    void blockBreakInTheHubIsCancelledForANormalPlayer() {
        PlayerMock player = server.addPlayer();
        Block block = hub.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBreak(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void blockPlaceInTheHubIsCancelledForANormalPlayer() {
        PlayerMock player = server.addPlayer();
        Block block = hub.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), block.getRelative(0, -1, 0),
                new ItemStack(Material.STONE), player, true, EquipmentSlot.HAND);

        listener.onPlace(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void blockBreakByABypassingAdminInTheHubIsAllowed() {
        PlayerMock admin = server.addPlayer();
        admin.setOp(true); // rpgquest.admin.world est "default: op"
        Block block = hub.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, admin);

        listener.onBreak(event);

        assertFalse(event.isCancelled(), "un administrateur doit pouvoir construire dans le Hub");
    }

    @Test
    void blockBreakInAnotherWorldIsNeverCancelled() {
        PlayerMock player = server.addPlayer();
        Block block = other.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBreak(event);

        assertFalse(event.isCancelled());
    }

    // ---- Explosions ---------------------------------------------------------------------------

    @Test
    void explosionInTheHubClearsTheBlockList() {
        var creeper = hub.spawn(new Location(hub, 0.5, 64, 0.5), org.bukkit.entity.Creeper.class);
        Block block = hub.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertTrue(event.blockList().isEmpty(), "aucune destruction de bloc dans le Hub");
    }

    @Test
    void explosionInAnotherWorldKeepsTheBlockList() {
        var creeper = other.spawn(new Location(other, 0.5, 64, 0.5), org.bukkit.entity.Creeper.class);
        Block block = other.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertFalse(event.blockList().isEmpty());
    }

    // ---- Spawns naturels de mobs hostiles ----------------------------------------------------------

    @Test
    void naturalHostileSpawnInTheHubIsCancelled() {
        var zombie = hub.spawn(new Location(hub, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void naturalHostileSpawnInAnotherWorldIsAllowed() {
        var zombie = other.spawn(new Location(other, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void nonNaturalSpawnInTheHubIsIgnored() {
        var zombie = hub.spawn(new Location(hub, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.CUSTOM);

        listener.onCreatureSpawn(event);

        assertFalse(event.isCancelled());
    }
}
