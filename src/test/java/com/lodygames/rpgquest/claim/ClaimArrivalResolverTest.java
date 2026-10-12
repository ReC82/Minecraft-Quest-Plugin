package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issues #73 et #208 — l'arrivée « CLAIM_HOME » dans le monde des claims : sur son claim s'il en a
 * un, sinon répartie et toujours à distance des claims existants, y compris après un reset.
 */
class ClaimArrivalResolverTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private ClaimService claimService;
    private ClaimArrivalResolver resolver;
    private World claimsWorld;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        claimsWorld = server.addSimpleWorld("claims");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        variableRepository = new PlayerVariableRepository(database);
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
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();

        resolver = new ClaimArrivalResolver(claimService, () -> new com.lodygames.rpgquest.config.ClaimConfig(
                64, 384, 3, 16, "claims", true, 64, 600, 100));
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
        variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private void setGround(int x, int z, Material groundType) {
        claimsWorld.getBlockAt(x, 60, z).setType(groundType);
        claimsWorld.getBlockAt(x, 61, z).setType(Material.AIR);
        claimsWorld.getBlockAt(x, 62, z).setType(Material.AIR);
    }

    /** Claim "main_<uuid>" (même convention que {@code DeedClaimListener}) créé pour {@code owner}, puis renvoyé depuis le service. */
    private void createClaim(PlayerMock owner, int minX, int minZ, int maxX, int maxZ) throws Exception {
        var outcome = claimService.create(owner, "main_" + owner.getUniqueId(),
                        new Location(claimsWorld, minX, 60, minZ), new Location(claimsWorld, maxX, 63, maxZ))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome, "pré-requis du test : le claim doit vraiment être créé");
        server.getScheduler().performTicks(2);
    }

    @Test
    void anOwnerArrivesOnHisOwnClaim() throws Exception {
        PlayerMock owner = addPlayer();
        createClaim(owner, 300, 300, 304, 304);
        setGround(302, 302, Material.STONE);

        Location arrival = resolver.resolve(owner, claimsWorld).orElseThrow();
        assertEquals(302, arrival.getBlockX());
        assertEquals(302, arrival.getBlockZ());
    }

    @Test
    void firstEntriesAreSpreadAndKeepAwayFromEveryExistingClaim() throws Exception {
        PlayerMock neighbour = addPlayer();
        createClaim(neighbour, 90, -10, 110, 10);
        var claims = claimService.claimsInWorld("claims");
        PlayerMock newcomer = addPlayer();

        java.util.Set<String> points = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            Location arrival = resolver.resolve(newcomer, claimsWorld).orElseThrow();
            points.add(arrival.getBlockX() + "," + arrival.getBlockZ());
            assertTrue(ClaimArrivalResolver.farFromClaims(claims, arrival.getBlockX(), arrival.getBlockZ(), 100),
                    "arrivée trop proche d'un claim : " + arrival);
            double fromSpawn = Math.hypot(arrival.getX() - claimsWorld.getSpawnLocation().getX(),
                    arrival.getZ() - claimsWorld.getSpawnLocation().getZ());
            assertTrue(fromSpawn >= 63 && fromSpawn <= 601, "hors de la couronne configurée : " + fromSpawn);
        }
        assertTrue(points.size() > 6, "les entrées ne retombent pas au même point : " + points);
    }

    @Test
    void afterAResetThePlayerNeverLandsBackOnHisFormerClaim() throws Exception {
        PlayerMock player = addPlayer();
        createClaim(player, 200, 200, 220, 220);
        setGround(210, 210, Material.STONE);
        assertEquals(210, resolver.resolve(player, claimsWorld).orElseThrow().getBlockX());

        claimService.resetTierOneClaimForTesting(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        assertTrue(claimService.claimsOwnedBy(player.getUniqueId()).isEmpty(), "le reset supprime bien le claim");

        for (int i = 0; i < 10; i++) {
            Location arrival = resolver.resolve(player, claimsWorld).orElseThrow();
            boolean onFormer = arrival.getBlockX() >= 200 && arrival.getBlockX() <= 220
                    && arrival.getBlockZ() >= 200 && arrival.getBlockZ() <= 220;
            assertFalse(onFormer, "plus de retour implicite sur l'ancien claim : " + arrival);
        }
    }

    @Test
    void distanceToAClaimIsMeasuredFromItsEdges() {
        var claim = new com.lodygames.rpgquest.claim.model.Claim("c", java.util.UUID.randomUUID(), "claims",
                0, 0, 0, 10, 100, 10, Set.of(), com.lodygames.rpgquest.claim.model.ClaimFlags.defaults());
        assertFalse(ClaimArrivalResolver.farFromClaims(List.of(claim), 5, 5, 100), "dedans");
        assertFalse(ClaimArrivalResolver.farFromClaims(List.of(claim), 109, 5, 100), "à 99 blocs du bord");
        assertTrue(ClaimArrivalResolver.farFromClaims(List.of(claim), 110, 5, 100), "à 100 blocs du bord");
        assertTrue(ClaimArrivalResolver.farFromClaims(List.of(), 0, 0, 100));
    }
}
