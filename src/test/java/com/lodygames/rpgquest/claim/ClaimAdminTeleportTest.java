package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.settings.GameSettings;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Issue #637 — téléportation de diagnostic d'un administrateur vers un claim depuis PlugAdmin.
 *
 * <p>Ce qui compte : seul le personnage demandé bouge, vers une position sûre DU claim ; un refus
 * est explicite (claim inconnu ou changé de main, hors ligne, monde absent, accès au monde des
 * claims non débloqué, aucune position sûre, double demande) ; et rien d'autre ne change — ni le
 * claim, ni les variables du joueur.</p>
 */
class ClaimAdminTeleportTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private ClaimService claimService;
    private ClaimAdminTeleport teleport;
    private World claimsWorld;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        claimsWorld = server.addSimpleWorld("claims");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        variableRepository = new PlayerVariableRepository(database);
        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        zoneRegistry.start();
        YamlPortalRegistry portalRegistry = new YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portalRegistry.start();
        ConfigService configService = new ConfigService(plugin);
        configService.start();
        ProgressionService progressionService = new ProgressionService(
                plugin, new ProgressionRepository(database), () -> configService.current().progression(), plugin.getSLF4JLogger());
        progressionService.start();
        claimService = new ClaimService(plugin, new ClaimRepository(database), zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();
        ClaimConfig claims = new ClaimConfig(100, 256, 1, 0, "claims", true);
        // MockBukkit n'implémente pas teleportAsync : téléportation synchrone, même contrat (booléen).
        teleport = new ClaimAdminTeleport(plugin, claimService, () -> claims,
                new AdminBypassMode(() -> GameSettings.DEFAULTS),
                (player, location) -> CompletableFuture.completedFuture(player.teleport(location)));
    }

    @AfterEach
    void tearDown() {
        claimService.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PlayerMock addPlayer(boolean tierOne) throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (tierOne) {
            variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        return player;
    }

    private Claim createClaim(PlayerMock owner, int minX, int minZ, int maxX, int maxZ) throws Exception {
        String id = "main_" + owner.getUniqueId();
        assertEquals(ClaimService.CreateOutcome.CREATED, claimService.create(owner, id,
                        new Location(claimsWorld, minX, 60, minZ), new Location(claimsWorld, maxX, 63, maxZ))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        server.getScheduler().performTicks(2);
        return claimService.find(id).orElseThrow();
    }

    private void setGround(int x, int z) {
        claimsWorld.getBlockAt(x, 60, z).setType(Material.STONE);
        claimsWorld.getBlockAt(x, 61, z).setType(Material.AIR);
        claimsWorld.getBlockAt(x, 62, z).setType(Material.AIR);
    }

    /** Fait avancer le serveur jusqu'au résultat (contrôle asynchrone + relecture différée). */
    private ClaimAdminTeleport.Result await(CompletableFuture<ClaimAdminTeleport.Result> future) {
        for (int i = 0; i < 100 && !future.isDone(); i++) {
            server.getScheduler().performTicks(1);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(future.isDone(), "la téléportation doit aboutir à un résultat");
        return future.join();
    }

    @Test
    void movesOnlyTheAdministratorToASafeSpotOfTheClaimAndChangesNothingElse() throws Exception {
        PlayerMock owner = addPlayer(true);
        PlayerMock admin = addPlayer(true);
        Claim before = createClaim(owner, -2, -2, 2, 2);
        setGround(0, 0);
        Location ownerBefore = owner.getLocation().clone();

        ClaimAdminTeleport.Result result = await(teleport.teleport(admin.getUniqueId(), before.id(), owner.getUniqueId()));

        assertEquals(ClaimAdminTeleport.Code.TELEPORTED, result.code(), result.message());
        assertNotNull(result.destination());
        assertEquals(claimsWorld, admin.getLocation().getWorld());
        assertEquals(0, admin.getLocation().getBlockX());
        assertEquals(0, admin.getLocation().getBlockZ());
        assertEquals(ownerBefore, owner.getLocation(), "le propriétaire n'est jamais déplacé");
        assertEquals(before, claimService.find(before.id()).orElseThrow(), "le claim est strictement inchangé");
        assertFalse(claimService.find(before.id()).orElseThrow().isTrusted(admin.getUniqueId()),
                "la téléportation ne donne aucune appartenance au claim");
    }

    @Test
    void anAdministratorWithoutClaimsAccessIsRefusedBeforeMovingInsteadOfBeingBouncedBack() throws Exception {
        PlayerMock owner = addPlayer(true);
        PlayerMock admin = addPlayer(false);
        Claim claim = createClaim(owner, -2, -2, 2, 2);
        setGround(0, 0);
        Location adminBefore = admin.getLocation().clone();

        ClaimAdminTeleport.Result result = await(teleport.teleport(admin.getUniqueId(), claim.id(), owner.getUniqueId()));

        assertEquals(ClaimAdminTeleport.Code.NOT_ELIGIBLE, result.code());
        assertEquals(adminBefore, admin.getLocation());
        assertEquals(Optional.empty(), variableRepository.get(admin.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "aucun déblocage CLAIM_TIER_1 n'est écrit (#321)");
    }

    @Test
    void refusesExplicitlyWhenNoSafeSpotExistsInTheClaim() throws Exception {
        PlayerMock owner = addPlayer(true);
        PlayerMock admin = addPlayer(true);
        Claim claim = createClaim(owner, -1, -1, 1, 1);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                claimsWorld.getBlockAt(x, 60, z).setType(Material.LAVA); // aucune colonne sûre.
            }
        }
        Location adminBefore = admin.getLocation().clone();

        ClaimAdminTeleport.Result result = await(teleport.teleport(admin.getUniqueId(), claim.id(), owner.getUniqueId()));

        assertEquals(ClaimAdminTeleport.Code.NO_SAFE_LOCATION, result.code());
        assertEquals(adminBefore, admin.getLocation());
    }

    @Test
    void refusesAnUnknownClaimAClaimThatChangedHandsAndAnOfflineAdministrator() throws Exception {
        PlayerMock owner = addPlayer(true);
        PlayerMock admin = addPlayer(true);
        Claim claim = createClaim(owner, -2, -2, 2, 2);
        setGround(0, 0);

        assertEquals(ClaimAdminTeleport.Code.UNKNOWN_CLAIM,
                await(teleport.teleport(admin.getUniqueId(), "absent", null)).code());
        assertEquals(ClaimAdminTeleport.Code.CLAIM_CHANGED,
                await(teleport.teleport(admin.getUniqueId(), claim.id(), admin.getUniqueId())).code());
        assertEquals(ClaimAdminTeleport.Code.OFFLINE,
                await(teleport.teleport(UUID.randomUUID(), claim.id(), owner.getUniqueId())).code());
    }

    @Test
    void aSecondRequestWhileTheFirstIsRunningIsRefusedAndNeverSentTwice() throws Exception {
        PlayerMock owner = addPlayer(true);
        PlayerMock admin = addPlayer(true);
        Claim claim = createClaim(owner, -2, -2, 2, 2);
        setGround(0, 0);

        CompletableFuture<ClaimAdminTeleport.Result> first = teleport.teleport(admin.getUniqueId(), claim.id(),
                owner.getUniqueId());
        ClaimAdminTeleport.Result second = teleport.teleport(admin.getUniqueId(), claim.id(), owner.getUniqueId()).join();

        assertEquals(ClaimAdminTeleport.Code.BUSY, second.code());
        assertEquals(ClaimAdminTeleport.Code.TELEPORTED, await(first).code());
        // Une fois la première terminée, une nouvelle demande est de nouveau acceptée.
        assertEquals(ClaimAdminTeleport.Code.TELEPORTED,
                await(teleport.teleport(admin.getUniqueId(), claim.id(), owner.getUniqueId())).code());
    }

    @Test
    void refusesWhenTheClaimWorldIsNotLoaded() throws Exception {
        PlayerMock owner = addPlayer(true);
        PlayerMock admin = addPlayer(true);
        WorldMock ghost = new WorldMock(Material.STONE, 3);
        ghost.setName("ghost_world"); // jamais ajouté au serveur : monde non chargé.
        assertEquals(ClaimService.CreateOutcome.CREATED, claimService.create(owner, "ghost_claim",
                new Location(ghost, 0, 60, 0), new Location(ghost, 2, 63, 2)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        server.getScheduler().performTicks(2);
        Claim claim = claimService.find("ghost_claim").orElseThrow();

        assertEquals(ClaimAdminTeleport.Code.WORLD_UNAVAILABLE,
                await(teleport.teleport(admin.getUniqueId(), claim.id(), owner.getUniqueId())).code());
    }
}
