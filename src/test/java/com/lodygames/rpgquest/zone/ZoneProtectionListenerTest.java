package com.lodygames.rpgquest.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private DatabaseManager database;
    private ZoneRegistry registry;
    private ZoneProtectionListener listener;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));

        registry = new ZoneRegistry(tempDir.resolve("zones"), NOPLogger.NOP_LOGGER);
        // Zone protégée (PvP/casse/explosions/mobs/environnement bloqués) couvrant -10..10 sur x/z.
        registry.create(new ZoneDefinition("safe", "world", -10, 0, -10, 10, 255, 10, ZoneFlags.defaults()));

        listener = new ZoneProtectionListener(registry, npcIdentityService, com.lodygames.rpgquest.permission.TestBuildPermissions.standard());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    // ---- Blocs --------------------------------------------------------------------------------

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
    void blockPlaceByABypassingAdminIsAllowed() {
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), block.getRelative(0, -1, 0),
                new ItemStack(Material.STONE), admin, true, EquipmentSlot.HAND);

        listener.onPlace(event);

        assertFalse(event.isCancelled(), "un administrateur doit pouvoir construire dans la zone");
    }

    // ---- PvP ------------------------------------------------------------------------------------

    @Test
    void pvpDamageInsideZoneIsCancelled() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(world, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void pvpDamageOutsideZoneIsAllowed() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(world, 1000.5, 64, 1000.5));
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
        victim.teleport(new Location(world, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void nonPlayerNonNpcVictimDamageIsIgnored() {
        // Ni joueur, ni PNJ Citizens (Citizens absent de cet environnement de test) : la zone ne
        // doit rien faire (et ne pas planter).
        var zombie = world.spawn(new Location(world, 0.5, 64, 0.5), Zombie.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                server.addPlayer(), zombie, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    // ---- Dégâts causés par des mobs hostiles ------------------------------------------------------

    @Test
    void hostileMobDamageToPlayerInsideZoneIsCancelled() {
        var zombie = world.spawn(new Location(world, 0.5, 64, 0.5), Zombie.class);
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(world, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                zombie, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void hostileMobDamageToPlayerOutsideZoneIsAllowed() {
        var zombie = world.spawn(new Location(world, 1000.5, 64, 1000.5), Zombie.class);
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(world, 1000.5, 64, 1000.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                zombie, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void arrowShotByHostileMobInsideZoneIsCancelled() {
        var skeleton = world.spawn(new Location(world, 0.5, 64, 0.5), Skeleton.class);
        Arrow arrow = world.spawn(new Location(world, 0.5, 64, 0.5), Arrow.class);
        arrow.setShooter(skeleton);
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(world, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                arrow, victim, EntityDamageEvent.DamageCause.PROJECTILE, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    // ---- Dégâts environnementaux (chute, noyade, faim, lave...) -----------------------------------

    @Test
    void fallDamageInsideZoneIsCancelled() {
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(world, 0.5, 64, 0.5));
        EntityDamageEvent event = new EntityDamageEvent(
                victim, EntityDamageEvent.DamageCause.FALL, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void fallDamageOutsideZoneIsAllowed() {
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(world, 1000.5, 64, 1000.5));
        EntityDamageEvent event = new EntityDamageEvent(
                victim, EntityDamageEvent.DamageCause.FALL, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void drowningAndLavaDamageInsideZoneAreCancelled() {
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(world, 0.5, 64, 0.5));

        EntityDamageEvent drowning = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.DROWNING, 2.0);
        listener.onEntityDamage(drowning);
        assertTrue(drowning.isCancelled());

        EntityDamageEvent lava = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.LAVA, 4.0);
        listener.onEntityDamage(lava);
        assertTrue(lava.isCancelled());

        EntityDamageEvent starvation = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.STARVATION, 1.0);
        listener.onEntityDamage(starvation);
        assertTrue(starvation.isCancelled());
    }

    @Test
    void environmentalDamageByABypassingAdminIsStillCancelled() {
        // Pas d'« acteur » distinct de la victime pour un dégât environnemental : même un admin
        // reste protégé de la chute/noyade/faim dans le hub, cohérent avec la doc.
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        admin.teleport(new Location(world, 0.5, 64, 0.5));
        EntityDamageEvent event = new EntityDamageEvent(admin, EntityDamageEvent.DamageCause.FALL, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    // ---- Explosions ---------------------------------------------------------------------------

    @Test
    void explosionInsideZoneClearsTheBlockList() {
        var creeper = world.spawn(new Location(world, 0.5, 64, 0.5), org.bukkit.entity.Creeper.class);
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertTrue(event.blockList().isEmpty(), "aucune destruction de bloc dans une zone sans explosions");
    }

    @Test
    void explosionOutsideZoneKeepsTheBlockList() {
        var creeper = world.spawn(new Location(world, 1000.5, 64, 1000.5), org.bukkit.entity.Creeper.class);
        Block block = world.getBlockAt(1000, 64, 1000);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertFalse(event.blockList().isEmpty());
    }

    @Test
    void explosionDamageToAPlayerInsideZoneIsCancelled() {
        var creeper = world.spawn(new Location(world, 0.5, 64, 0.5), org.bukkit.entity.Creeper.class);
        PlayerMock victim = server.addPlayer();
        victim.teleport(new Location(world, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                creeper, victim, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, 8.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    // ---- Spawns naturels de mobs hostiles ----------------------------------------------------------

    @Test
    void naturalHostileSpawnInsideZoneIsCancelled() {
        var zombie = world.spawn(new Location(world, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void naturalHostileSpawnOutsideZoneIsAllowed() {
        var zombie = world.spawn(new Location(world, 1000.5, 64, 1000.5), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void nonNaturalHostileSpawnInsideZoneIsIgnored() {
        // /rpgadmin mob spawn ou /rpgadmin npc tag ne doivent pas être bloqués par la protection
        // "spawn naturel" — seul SpawnReason.NATURAL est concerné.
        var zombie = world.spawn(new Location(world, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.CUSTOM);

        listener.onCreatureSpawn(event);

        assertFalse(event.isCancelled());
    }

    // ---- Jour figé (forceDay), cosmétique par joueur -----------------------------------------------

    @Test
    void movingIntoAForceDayZoneFixesThePlayerClientTimeAtNoon() throws Exception {
        registry.create(new ZoneDefinition("hub", "world", 100, 0, 100, 120, 255, 120, forceDayFlags()));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 1000.5, 64, 1000.5));

        PlayerMoveEvent event = new PlayerMoveEvent(player,
                new Location(world, 1000.5, 64, 1000.5), new Location(world, 110.5, 64, 110.5));
        listener.onMove(event);

        assertFalse(player.isPlayerTimeRelative(), "le temps doit être figé (absolu), pas suivre l'horloge du monde");
        assertEquals(6000L, player.getPlayerTime());
    }

    @Test
    void leavingAForceDayZoneRestoresNormalPlayerTime() throws Exception {
        registry.create(new ZoneDefinition("hub", "world", 100, 0, 100, 120, 255, 120, forceDayFlags()));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 110.5, 64, 110.5));
        listener.onMove(new PlayerMoveEvent(player,
                new Location(world, 1000.5, 64, 1000.5), new Location(world, 110.5, 64, 110.5)));

        PlayerMoveEvent leaving = new PlayerMoveEvent(player,
                new Location(world, 110.5, 64, 110.5), new Location(world, 1000.5, 64, 1000.5));
        listener.onMove(leaving);

        assertTrue(player.isPlayerTimeRelative(), "de retour à l'heure normale du monde après avoir quitté la zone");
    }

    @Test
    void movingIntoAZoneWithoutForceDayNeverTouchesPlayerTime() throws Exception {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 1000.5, 64, 1000.5));

        PlayerMoveEvent event = new PlayerMoveEvent(player,
                new Location(world, 1000.5, 64, 1000.5), new Location(world, 0.5, 64, 0.5)); // "safe", forceDay=false
        listener.onMove(event);

        assertTrue(player.isPlayerTimeRelative(), "aucune zone forceDay ici : le temps normal du monde doit rester actif");
    }

    @Test
    void joiningAlreadyInsideAForceDayZoneFixesTimeImmediately() {
        registry.create(new ZoneDefinition("hub", "world", 100, 0, 100, 120, 255, 120, forceDayFlags()));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 110.5, 64, 110.5));

        listener.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));

        assertFalse(player.isPlayerTimeRelative());
        assertEquals(6000L, player.getPlayerTime());
    }

    private ZoneFlags forceDayFlags() {
        ZoneFlags defaults = ZoneFlags.defaults();
        return new ZoneFlags(
                defaults.allowPvp(), defaults.allowBlockBreak(), defaults.allowBlockPlace(),
                defaults.allowExplosions(), defaults.allowFire(), defaults.allowLava(),
                defaults.allowPistonsAcrossBorder(), defaults.allowHostileSpawn(),
                defaults.allowHostileDamage(), defaults.allowEnvironmentalDamage(), defaults.allowNpcDamage(),
                defaults.allowDoors(), defaults.allowButtons(), defaults.allowLevers(),
                defaults.allowNpcInteract(), defaults.allowPublicContainers(),
                true);
    }

    // ---- Divers -----------------------------------------------------------------------------------

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
