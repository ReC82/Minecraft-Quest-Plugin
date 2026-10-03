package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.TraderLlama;
import org.bukkit.entity.WanderingTrader;
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
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Couvre les protections d'événement du monde Hub (dégâts/PvP, protection des entités, casse/pose
 * de bloc, spawn de mob indésirable, explosions), scoped par nom de monde — voir
 * {@code hub.HubWorldRulesService} pour les règles réelles de monde (jour/météo), testées
 * séparément.
 */
@SuppressWarnings("removal") // EntityDamageByEntityEvent(Entity, Entity, DamageCause, double) — voir ZoneProtectionListenerTest.
class HubWorldProtectionListenerTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final HubConfig HUB_CONFIG = new HubConfig("world_hub");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World hub;
    private World other;
    private DatabaseManager database;
    private HubWorldProtectionListener listener;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hub = server.addSimpleWorld("world_hub");
        other = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));

        listener = new HubWorldProtectionListener(() -> HUB_CONFIG, npcIdentityService);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
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

    // ---- Protection des entités (issues #30/#31) ----------------------------------------------

    @Test
    void meleeDamageToACowInTheHubByANormalPlayerIsCancelled() {
        PlayerMock attacker = server.addPlayer();
        Cow cow = hub.spawn(new Location(hub, 0.5, 64, 0.5), Cow.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled(), "un mouton/vache du Hub ne doit jamais pouvoir être tué par un joueur normal");
    }

    @Test
    void projectileDamageToACowInTheHubByANormalPlayerIsCancelled() {
        PlayerMock attacker = server.addPlayer();
        Cow cow = hub.spawn(new Location(hub, 0.5, 64, 0.5), Cow.class);
        var arrow = hub.spawn(new Location(hub, 0.5, 64, 0.5), org.bukkit.entity.Arrow.class);
        arrow.setShooter(attacker);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                arrow, cow, EntityDamageEvent.DamageCause.PROJECTILE, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled(), "un projectile tiré par un joueur doit être couvert, pas seulement la mêlée");
    }

    @Test
    void damageToACowInTheHubByABypassingAdminIsAllowed() {
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "rpgquest.admin.hub.combat", true);
        Cow cow = hub.spawn(new Location(hub, 0.5, 64, 0.5), Cow.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                admin, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled(), "le bypass combat explicite doit rester possible");
    }

    @Test
    void buildBypassAloneDoesNotGrantCombatBypass() {
        PlayerMock builder = server.addPlayer();
        builder.addAttachment(plugin, "rpgquest.admin.world", true); // droit de construire uniquement.
        Cow cow = hub.spawn(new Location(hub, 0.5, 64, 0.5), Cow.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                builder, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled(), "le droit de construire ne doit jamais donner implicitement le droit de tuer");
    }

    @Test
    void cowDamageInAnotherWorldIsNeverCancelled() {
        PlayerMock attacker = server.addPlayer();
        Cow cow = other.spawn(new Location(other, 0.5, 64, 0.5), Cow.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled(), "le Wild garde son combat normal");
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
        var creeper = hub.spawn(new Location(hub, 0.5, 64, 0.5), Creeper.class);
        Block block = hub.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertTrue(event.blockList().isEmpty(), "aucune destruction de bloc dans le Hub");
    }

    @Test
    void explosionInAnotherWorldKeepsTheBlockList() {
        var creeper = other.spawn(new Location(other, 0.5, 64, 0.5), Creeper.class);
        Block block = other.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertFalse(event.blockList().isEmpty());
    }

    // ---- Spawns de mobs indésirables (issues #121/#155) -------------------------------------------

    @Test
    void naturalHostileSpawnInTheHubIsCancelled() {
        var zombie = hub.spawn(new Location(hub, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void nonNaturalHostileSpawnInTheHubIsAlsoCancelled() {
        // Issue #155 : un creeper/enderman venu d'une raison non-NATURAL (ex. CHUNK_GEN lors d'un
        // import Multiverse, ou un spawner) ne doit pas non plus apparaître dans le Hub.
        var creeper = hub.spawn(new Location(hub, 0.5, 64, 0.5), Creeper.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(creeper, CreatureSpawnEvent.SpawnReason.CHUNK_GEN);

        listener.onCreatureSpawn(event);

        assertTrue(event.isCancelled(), "la raison du spawn ne doit pas protéger un mob hostile dans le Hub");
    }

    @Test
    void endermanSpawnInTheHubIsCancelledEvenIfNeutral() {
        var enderman = hub.spawn(new Location(hub, 0.5, 64, 0.5), Enderman.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(enderman, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(event);

        assertTrue(event.isCancelled(), "un enderman reste indésirable même neutre, tant qu'il n'est pas agressé");
    }

    @Test
    void wanderingTraderAndLlamaSpawnInTheHubAreCancelled() {
        var trader = hub.spawn(new Location(hub, 0.5, 64, 0.5), WanderingTrader.class);
        CreatureSpawnEvent traderEvent = new CreatureSpawnEvent(trader, CreatureSpawnEvent.SpawnReason.NATURAL);
        var llama = hub.spawn(new Location(hub, 0.5, 64, 0.5), TraderLlama.class);
        CreatureSpawnEvent llamaEvent = new CreatureSpawnEvent(llama, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(traderEvent);
        listener.onCreatureSpawn(llamaEvent);

        assertTrue(traderEvent.isCancelled(), "issue #121 : aucun marchand ambulant dans le Hub");
        assertTrue(llamaEvent.isCancelled(), "issue #121 : aucun lama de commerce associé dans le Hub");
    }

    @Test
    void naturalHostileSpawnInAnotherWorldIsAllowed() {
        var zombie = other.spawn(new Location(other, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(event);

        assertFalse(event.isCancelled(), "le Wild garde ses spawns normaux");
    }

    @Test
    void passiveAnimalSpawnInTheHubIsNeverCancelled() {
        var cow = hub.spawn(new Location(hub, 0.5, 64, 0.5), Cow.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(cow, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(event);

        assertFalse(event.isCancelled(), "un animal passif autorisé n'est jamais concerné par cette règle");
    }

    // ---- Nettoyage ciblé des entités déjà présentes (issues #121/#155) ------------------------

    @Test
    void sweepAlreadyLoadedRemovesUnwantedEntitiesButKeepsPassiveAnimals() {
        var creeper = hub.spawn(new Location(hub, 0.5, 64, 0.5), Creeper.class);
        var enderman = hub.spawn(new Location(hub, 1.5, 64, 0.5), Enderman.class);
        var trader = hub.spawn(new Location(hub, 2.5, 64, 0.5), WanderingTrader.class);
        var cow = hub.spawn(new Location(hub, 3.5, 64, 0.5), Cow.class);

        listener.sweepAlreadyLoaded(hub);

        assertTrue(creeper.isDead(), "creeper déjà présent nettoyé");
        assertTrue(enderman.isDead(), "enderman déjà présent nettoyé");
        assertTrue(trader.isDead(), "marchand ambulant déjà présent nettoyé");
        assertFalse(cow.isDead(), "un animal passif ne doit jamais être supprimé par ce nettoyage");
    }

    @Test
    void sweepAlreadyLoadedNeverTouchesAnotherWorld() {
        var creeper = other.spawn(new Location(other, 0.5, 64, 0.5), Creeper.class);

        listener.sweepAlreadyLoaded(other);

        assertFalse(creeper.isDead(), "le nettoyage est scoped au Hub uniquement");
    }
}
