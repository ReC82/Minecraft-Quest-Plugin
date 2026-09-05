package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Couvre {@link ClaimTeleportService} (mission « Jo : retourner à son claim » / commande admin de
 * diagnostic) : résolution du claim principal, recherche d'une position sûre (centre du claim
 * d'abord, sinon balayage borné au cuboïde actif — jamais hors du claim), et les 4 issues possibles.
 */
class ClaimTeleportServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private ClaimService claimService;
    private ClaimTeleportService teleportService;
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

        teleportService = new ClaimTeleportService(plugin, claimService);
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
    void teleportReturnsNoMainClaimWhenThePlayerOwnsNone() throws Exception {
        PlayerMock player = addPlayer();

        ClaimTeleportService.Outcome outcome = teleportService.teleport(player, player.getUniqueId());

        assertEquals(ClaimTeleportService.Outcome.NO_MAIN_CLAIM, outcome);
    }

    @Test
    void teleportMovesThePlayerToASafeSpotAtTheCenterOfTheClaim() throws Exception {
        PlayerMock owner = addPlayer();
        // Claim 5x5 centré sur (0, 0) — même convention que DeedClaimListener.
        createClaim(owner, -2, -2, 2, 2);
        setGround(0, 0, Material.STONE);

        ClaimTeleportService.Outcome outcome = teleportService.teleport(owner, owner.getUniqueId());

        assertEquals(ClaimTeleportService.Outcome.TELEPORTED, outcome);
        Location destination = owner.getLocation();
        assertEquals(claimsWorld, destination.getWorld());
        assertEquals(0, destination.getBlockX());
        assertEquals(0, destination.getBlockZ());
        assertEquals(61, destination.getBlockY());
    }

    @Test
    void teleportFallsBackToAnotherColumnWithinTheClaimWhenTheCenterIsUnsafe() throws Exception {
        PlayerMock owner = addPlayer();
        createClaim(owner, -2, -2, 2, 2);
        // Centre (0,0) laissé vide (colonne d'air, jamais sûre) ; seule (1,1) est un sol valide.
        setGround(1, 1, Material.STONE);

        ClaimTeleportService.Outcome outcome = teleportService.teleport(owner, owner.getUniqueId());

        assertEquals(ClaimTeleportService.Outcome.TELEPORTED, outcome);
        Location destination = owner.getLocation();
        assertEquals(1, destination.getBlockX());
        assertEquals(1, destination.getBlockZ());
        assertTrue(destination.getBlockX() >= -2 && destination.getBlockX() <= 2
                && destination.getBlockZ() >= -2 && destination.getBlockZ() <= 2, "la destination doit toujours rester dans le claim");
    }

    @Test
    void teleportReturnsNoSafeLocationWhenNothingInTheClaimIsSafe() throws Exception {
        PlayerMock owner = addPlayer();
        createClaim(owner, -1, -1, 1, 1);
        // Aucun bloc posé nulle part dans le claim : uniquement de l'air.

        ClaimTeleportService.Outcome outcome = teleportService.teleport(owner, owner.getUniqueId());

        assertEquals(ClaimTeleportService.Outcome.NO_SAFE_LOCATION, outcome);
    }

    @Test
    void teleportReturnsWorldUnavailableWhenTheClaimsWorldIsNotLoaded() throws Exception {
        PlayerMock player = addPlayer();
        Claim fabricated = new Claim("main_" + player.getUniqueId(), player.getUniqueId(), "not_a_loaded_world",
                -2, 60, -2, 2, 63, 2, Set.of(), ClaimFlags.defaults());

        ClaimTeleportService.Outcome outcome = teleportService.teleport(player, fabricated);

        assertEquals(ClaimTeleportService.Outcome.WORLD_UNAVAILABLE, outcome);
    }
}
