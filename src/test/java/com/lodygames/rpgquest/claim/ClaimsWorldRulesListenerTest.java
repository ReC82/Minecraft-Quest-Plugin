package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Couvre les 3 règles de monde du monde des claims (mission « premier claim 5×5 ») : PvP interdit,
 * mobs hostiles naturels empêchés, construction refusée hors de tout claim — jamais jour/nuit ni
 * météo, volontairement absents (voir {@link ClaimsWorldRulesListener}, contrairement à {@code
 * hub.HubWorldRulesService}).
 */
@SuppressWarnings("removal") // EntityDamageByEntityEvent(Entity, Entity, DamageCause, double) — voir ZoneProtectionListenerTest.
class ClaimsWorldRulesListenerTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final ClaimConfig CLAIMS_CONFIG = new ClaimConfig(64, 384, 3, 16, "claims", true);

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private ClaimService claimService;
    private World claimsWorld;
    private World other;
    private ClaimsWorldRulesListener listener;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        claimsWorld = server.addSimpleWorld("claims");
        other = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        ClaimRepository claimRepository = new ClaimRepository(database);
        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        zoneRegistry.start();
        YamlPortalRegistry portalRegistry = new YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portalRegistry.start();
        ConfigService configService = new ConfigService(plugin);
        configService.start();
        ProgressionService progressionService = new ProgressionService(
                plugin, new ProgressionRepository(database), () -> configService.current().progression(), plugin.getSLF4JLogger());
        progressionService.start();
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(database);
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();

        listener = new ClaimsWorldRulesListener(plugin, claimService, () -> CLAIMS_CONFIG);
    }

    @AfterEach
    void tearDown() {
        claimService.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    // ---- Zone résidentielle totalement safe (tout dégât joueur, toute cause) ---------------------

    @Test
    void pvpDamageInTheClaimsWorldIsCancelled() throws Exception {
        PlayerMock attacker = addPlayer();
        PlayerMock victim = addPlayer();
        victim.teleport(new Location(claimsWorld, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled(), "le PvP doit être interdit dans le monde des claims");
    }

    @Test
    void pvpDamageInAnotherWorldIsNeverCancelled() throws Exception {
        PlayerMock attacker = addPlayer();
        PlayerMock victim = addPlayer();
        victim.teleport(new Location(other, 0.5, 64, 0.5));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void fallDamageInTheClaimsWorldIsCancelled() throws Exception {
        assertDamageCancelledInClaims(EntityDamageEvent.DamageCause.FALL);
    }

    @Test
    void fireDamageInTheClaimsWorldIsCancelled() throws Exception {
        assertDamageCancelledInClaims(EntityDamageEvent.DamageCause.FIRE);
    }

    @Test
    void fireTickDamageInTheClaimsWorldIsCancelled() throws Exception {
        assertDamageCancelledInClaims(EntityDamageEvent.DamageCause.FIRE_TICK);
    }

    @Test
    void lavaDamageInTheClaimsWorldIsCancelled() throws Exception {
        assertDamageCancelledInClaims(EntityDamageEvent.DamageCause.LAVA);
    }

    @Test
    void drowningDamageInTheClaimsWorldIsCancelled() throws Exception {
        assertDamageCancelledInClaims(EntityDamageEvent.DamageCause.DROWNING);
    }

    @Test
    void suffocationDamageInTheClaimsWorldIsCancelled() throws Exception {
        assertDamageCancelledInClaims(EntityDamageEvent.DamageCause.SUFFOCATION);
    }

    @Test
    void starvationDamageInTheClaimsWorldIsCancelled() throws Exception {
        assertDamageCancelledInClaims(EntityDamageEvent.DamageCause.STARVATION);
    }

    @Test
    void voidDamageInTheClaimsWorldIsCancelled() throws Exception {
        assertDamageCancelledInClaims(EntityDamageEvent.DamageCause.VOID);
    }

    @Test
    void explosionDamageInTheClaimsWorldIsCancelled() throws Exception {
        PlayerMock victim = addPlayer();
        victim.teleport(new Location(claimsWorld, 0.5, 64, 0.5));
        var creeper = claimsWorld.spawn(new Location(claimsWorld, 1.5, 64, 1.5), org.bukkit.entity.Creeper.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                creeper, victim, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, 8.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled(), "les dégâts d'explosion doivent être annulés dans le monde des claims");
    }

    @Test
    void projectileDamageInTheClaimsWorldIsCancelled() throws Exception {
        PlayerMock shooter = addPlayer();
        PlayerMock victim = addPlayer();
        victim.teleport(new Location(claimsWorld, 0.5, 64, 0.5));
        var arrow = claimsWorld.spawn(new Location(claimsWorld, 1.5, 64, 1.5), org.bukkit.entity.Arrow.class);
        arrow.setShooter(shooter);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                arrow, victim, EntityDamageEvent.DamageCause.PROJECTILE, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled(), "les dégâts de projectile doivent être annulés dans le monde des claims");
    }

    @Test
    void fallDamageOutsideTheClaimsWorldIsNeverCancelled() throws Exception {
        PlayerMock victim = addPlayer();
        victim.teleport(new Location(other, 0.5, 64, 0.5));
        EntityDamageEvent event = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.FALL, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled(), "hors du monde des claims, le comportement normal doit revenir immédiatement");
    }

    @Test
    void nonPlayerDamageInTheClaimsWorldIsNeverCancelled() throws Exception {
        // Seuls les joueurs sont concernés : un animal/mob doit continuer à pouvoir être blessé normalement.
        var cow = claimsWorld.spawn(new Location(claimsWorld, 0.5, 64, 0.5), org.bukkit.entity.Cow.class);
        EntityDamageEvent event = new EntityDamageEvent(cow, EntityDamageEvent.DamageCause.FALL, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    private void assertDamageCancelledInClaims(EntityDamageEvent.DamageCause cause) throws Exception {
        PlayerMock victim = addPlayer();
        victim.teleport(new Location(claimsWorld, 0.5, 64, 0.5));
        EntityDamageEvent event = new EntityDamageEvent(victim, cause, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled(), () -> cause + " doit être annulé dans le monde des claims (zone résidentielle safe)");
    }

    // ---- Mobs hostiles -----------------------------------------------------------------------------

    @Test
    void naturalHostileSpawnInTheClaimsWorldIsCancelled() {
        var zombie = claimsWorld.spawn(new Location(claimsWorld, 0.5, 64, 0.5), Zombie.class);
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

    /**
     * Mission « monstres hostiles dans claims » : le monde doit être <strong>réellement</strong>
     * pacifique, pas seulement pour le spawn naturel — un spawner, des renforts de zombie ou une
     * invocation par commande sont tout autant refusés.
     */
    @Test
    void hostileSpawnFromASpawnerInTheClaimsWorldIsAlsoCancelled() {
        var zombie = claimsWorld.spawn(new Location(claimsWorld, 0.5, 64, 0.5), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.SPAWNER);

        listener.onCreatureSpawn(event);

        assertTrue(event.isCancelled(), "un spawner reste un mob hostile : doit être refusé comme le spawn naturel");
    }

    @Test
    void passiveMobSpawnInTheClaimsWorldIsNeverCancelled() {
        var cow = claimsWorld.spawn(new Location(claimsWorld, 0.5, 64, 0.5), Cow.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(cow, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onCreatureSpawn(event);

        assertFalse(event.isCancelled(), "un animal passif ne doit jamais être refusé, seuls les monstres hostiles le sont");
    }

    // ---- Nettoyage des mobs hostiles déjà présents ---------------------------------------------

    @Test
    void purgeAlreadyLoadedWorldRemovesExistingHostileMobsButKeepsPassiveOnes() {
        var zombie = claimsWorld.spawn(new Location(claimsWorld, 10.5, 64, 10.5), Zombie.class);
        var cow = claimsWorld.spawn(new Location(claimsWorld, 11.5, 64, 11.5), Cow.class);

        listener.purgeAlreadyLoadedWorld();

        assertTrue(zombie.isDead(), "un mob hostile déjà présent doit être nettoyé");
        assertFalse(cow.isDead(), "les animaux passifs ne doivent jamais être supprimés par ce nettoyage");
    }

    @Test
    void purgeAlreadyLoadedWorldNeverTouchesAnotherWorld() {
        var zombie = other.spawn(new Location(other, 0.5, 64, 0.5), Zombie.class);

        listener.purgeAlreadyLoadedWorld();

        assertFalse(zombie.isDead(), "seul le monde des claims est concerné par ce nettoyage");
    }

    @Test
    void onWorldLoadPurgesHostileMobsAlreadyPresentInTheClaimsWorld() {
        var zombie = claimsWorld.spawn(new Location(claimsWorld, 20.5, 64, 20.5), Zombie.class);

        listener.onWorldLoad(new WorldLoadEvent(claimsWorld));

        assertTrue(zombie.isDead(), "un rechargement du monde des claims doit aussi nettoyer les mobs hostiles déjà présents");
    }

    @Test
    void onChunkLoadPurgesHostileMobsAlreadyPresentInThatChunk() {
        var zombie = claimsWorld.spawn(new Location(claimsWorld, 30.5, 64, 30.5), Zombie.class);

        listener.onChunkLoad(new ChunkLoadEvent(claimsWorld.getChunkAt(30 >> 4, 30 >> 4), false));

        assertTrue(zombie.isDead(), "le chargement d'un chunk du monde des claims doit nettoyer les mobs hostiles qu'il contient");
    }

    // ---- Construction hors de tout claim -------------------------------------------------------

    @Test
    void blockBreakOutsideAnyClaimInTheClaimsWorldIsCancelled() throws Exception {
        PlayerMock player = addPlayer();
        Block block = claimsWorld.getBlockAt(500, 64, 500); // loin de tout claim.
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBreak(event);

        assertTrue(event.isCancelled(), "hors de tout claim, la construction doit être refusée dans le monde des claims");
    }

    @Test
    void blockBreakInsideAnOwnedClaimInTheClaimsWorldIsNeverCancelledByThisListener() throws Exception {
        PlayerMock owner = addPlayer();
        createClaim(owner.getUniqueId(), "home", -2, -2, 2, 2);
        Block block = claimsWorld.getBlockAt(0, 61, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, owner);

        listener.onBreak(event);

        assertFalse(event.isCancelled(), "à l'intérieur d'un claim, la protection revient à ClaimProtectionListener, pas à celui-ci");
    }

    @Test
    void blockBreakOutsideAnyClaimByABypassingAdminIsAllowed() throws Exception {
        PlayerMock admin = addPlayer();
        admin.setOp(true);
        Block block = claimsWorld.getBlockAt(500, 64, 500);
        BlockBreakEvent event = new BlockBreakEvent(block, admin);

        listener.onBreak(event);

        assertFalse(event.isCancelled(), "l'admin bypass existant (rpgquest.admin.world) doit continuer à fonctionner");
    }

    @Test
    void blockBreakOutsideAnyClaimInAnotherWorldIsNeverCancelled() throws Exception {
        PlayerMock player = addPlayer();
        Block block = other.getBlockAt(500, 64, 500);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBreak(event);

        assertFalse(event.isCancelled());
    }

    // ---- Jour/nuit et météo jamais désactivés (contrairement au Hub) --------------------------

    @Test
    void dayNightCycleAndWeatherAreNeverForciblyDisabledInTheClaimsWorld() {
        Boolean daylight = claimsWorld.getGameRuleValue(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE);
        Boolean weather = claimsWorld.getGameRuleValue(org.bukkit.GameRule.DO_WEATHER_CYCLE);
        assertTrue(daylight == null || daylight, "le cycle jour/nuit ne doit jamais être verrouillé par cette fonctionnalité");
        assertTrue(weather == null || weather, "la météo ne doit jamais être verrouillée par cette fonctionnalité");
    }

    private void createClaim(UUID owner, String id, int minX, int minZ, int maxX, int maxZ) throws Exception {
        new PlayerVariableRepository(database)
                .set(owner, ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        PlayerMock ownerPlayer = (PlayerMock) plugin.getServer().getOfflinePlayer(owner).getPlayer();
        var outcome = claimService.create(ownerPlayer, id,
                        new Location(claimsWorld, minX, 60, minZ), new Location(claimsWorld, maxX, 63, maxZ))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome, "pré-requis du test : le claim doit vraiment être créé");
        server.getScheduler().performTicks(2);
    }
}
