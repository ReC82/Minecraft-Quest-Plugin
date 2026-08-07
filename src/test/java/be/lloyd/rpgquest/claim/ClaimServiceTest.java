package be.lloyd.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.claim.model.Claim;
import be.lloyd.rpgquest.config.ConfigService;
import be.lloyd.rpgquest.database.ClaimActionOutcome;
import be.lloyd.rpgquest.database.ClaimRepository;
import be.lloyd.rpgquest.database.DatabaseManager;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.travel.YamlPortalRegistry;
import be.lloyd.rpgquest.travel.model.PortalDefinition;
import be.lloyd.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ClaimServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private ClaimRepository claimRepository;
    private ZoneRegistry zoneRegistry;
    private YamlPortalRegistry portalRegistry;
    private ConfigService configService;
    private ClaimService claimService;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        claimRepository = new ClaimRepository(database);

        zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        zoneRegistry.start(); // génère l'exemple central_village (-48..48 sur x/z, monde "world")
        portalRegistry = new YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portalRegistry.start();
        configService = new ConfigService(plugin);
        configService.start();

        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService);
        claimService.start();
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

    private Location loc(int x, int y, int z) {
        World world = server.getWorld("world");
        if (world == null) {
            world = server.addSimpleWorld("world");
        }
        return new Location(world, x, y, z);
    }

    /**
     * Attend le résultat d'une opération, puis laisse le temps au {@code
     * runTask(...)} qui applique le rechargement en mémoire de s'exécuter —
     * MockBukkit ne fait avancer les ticks planifiés que sur demande
     * explicite, contrairement à {@code CompletableFuture#get} qui ne
     * garantit que la fin de la partie asynchrone (base de données).
     */
    private <T> T await(CompletableFuture<T> future) throws Exception {
        T result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        return result;
    }

    @Test
    void createASimpleClaimSucceeds() throws Exception {
        PlayerMock owner = addPlayer();

        ClaimService.CreateOutcome outcome = await(claimService.create(owner, "home", loc(100, 60, 100), loc(105, 63, 105)));

        assertEquals(ClaimService.CreateOutcome.CREATED, outcome);
        assertTrue(claimService.find("home").isPresent());
        assertEquals(owner.getUniqueId(), claimService.find("home").get().owner());
    }

    @Test
    void createRejectsDuplicateId() throws Exception {
        PlayerMock owner = addPlayer();
        await(claimService.create(owner, "home", loc(100, 60, 100), loc(105, 63, 105)));

        ClaimService.CreateOutcome outcome = await(claimService.create(owner, "home", loc(200, 60, 200), loc(205, 63, 205)));

        assertEquals(ClaimService.CreateOutcome.DUPLICATE_ID, outcome);
    }

    @Test
    void createRejectsOverlapWithAnExistingClaim() throws Exception {
        PlayerMock owner = addPlayer();
        await(claimService.create(owner, "home", loc(100, 60, 100), loc(110, 63, 110)));

        ClaimService.CreateOutcome outcome = await(claimService.create(owner, "neighbour", loc(105, 60, 105), loc(115, 63, 115)));

        assertEquals(ClaimService.CreateOutcome.OVERLAPS_CLAIM, outcome);
    }

    @Test
    void createRejectsOverlapWithAProtectedZone() throws Exception {
        PlayerMock owner = addPlayer();

        // central_village couvre -48..48 sur x/z dans "world".
        ClaimService.CreateOutcome outcome = await(claimService.create(owner, "home", loc(0, 60, 0), loc(5, 63, 5)));

        assertEquals(ClaimService.CreateOutcome.OVERLAPS_PROTECTED_ZONE, outcome);
    }

    @Test
    void createRejectsWhenTooCloseToAPortal() throws Exception {
        PlayerMock owner = addPlayer();
        portalRegistry.create(new PortalDefinition("gate", "world", 200, 60, 200, 202, 63, 202,
                null, 3, 5, null, null, null, null, null));

        // Buffer par défaut : 16 blocs. À 5 blocs du portail, largement dans la zone tampon.
        ClaimService.CreateOutcome outcome = await(claimService.create(owner, "home", loc(203, 60, 203), loc(208, 63, 208)));

        assertEquals(ClaimService.CreateOutcome.TOO_CLOSE_TO_PORTAL, outcome);
    }

    @Test
    void createRejectsWhenLargerThanConfiguredMaximum() throws Exception {
        PlayerMock owner = addPlayer();
        int tooWide = configService.current().claims().maxWidth() + 10;

        ClaimService.CreateOutcome outcome = await(claimService.create(owner, "home", loc(100, 60, 100), loc(100 + tooWide, 63, 100)));

        assertEquals(ClaimService.CreateOutcome.TOO_LARGE, outcome);
    }

    @Test
    void createRejectsBeyondTheMaxClaimsPerPlayer() throws Exception {
        PlayerMock owner = addPlayer();
        int max = configService.current().claims().maxClaimsPerPlayer();
        for (int i = 0; i < max; i++) {
            int base = 100 + i * 20;
            ClaimService.CreateOutcome outcome = await(claimService.create(owner, "claim" + i, loc(base, 60, base), loc(base + 5, 63, base + 5)));
            assertEquals(ClaimService.CreateOutcome.CREATED, outcome, "claim #" + i + " devrait réussir");
        }

        int base = 100 + max * 20;
        ClaimService.CreateOutcome outcome = await(claimService.create(owner, "one_too_many", loc(base, 60, base), loc(base + 5, 63, base + 5)));

        assertEquals(ClaimService.CreateOutcome.TOO_MANY_CLAIMS, outcome);
    }

    @Test
    void deleteByOwnerSucceedsAndClaimDisappears() throws Exception {
        PlayerMock owner = addPlayer();
        await(claimService.create(owner, "home", loc(100, 60, 100), loc(105, 63, 105)));

        ClaimActionOutcome outcome = await(claimService.delete(owner.getUniqueId(), "home"));

        assertEquals(ClaimActionOutcome.SUCCESS, outcome);
        assertTrue(claimService.find("home").isEmpty());
        assertTrue(claimService.claimAt("world", 102, 61, 102).isEmpty(), "la protection doit disparaître avec le claim");
    }

    @Test
    void deleteByNonOwnerFails() throws Exception {
        PlayerMock owner = addPlayer();
        PlayerMock stranger = addPlayer();
        await(claimService.create(owner, "home", loc(100, 60, 100), loc(105, 63, 105)));

        ClaimActionOutcome outcome = await(claimService.delete(stranger.getUniqueId(), "home"));

        assertEquals(ClaimActionOutcome.NOT_OWNER, outcome);
        assertTrue(claimService.find("home").isPresent());
    }

    @Test
    void trustAddsAMemberByUuid() throws Exception {
        PlayerMock owner = addPlayer();
        PlayerMock friend = addPlayer();
        await(claimService.create(owner, "home", loc(100, 60, 100), loc(105, 63, 105)));

        ClaimActionOutcome outcome = await(claimService.trust(owner.getUniqueId(), "home", friend.getUniqueId()));

        assertEquals(ClaimActionOutcome.SUCCESS, outcome);
        assertTrue(claimService.find("home").get().isTrusted(friend.getUniqueId()));
    }

    @Test
    void untrustRemovesAMember() throws Exception {
        PlayerMock owner = addPlayer();
        PlayerMock friend = addPlayer();
        await(claimService.create(owner, "home", loc(100, 60, 100), loc(105, 63, 105)));
        await(claimService.trust(owner.getUniqueId(), "home", friend.getUniqueId()));

        await(claimService.untrust(owner.getUniqueId(), "home", friend.getUniqueId()));

        assertTrue(!claimService.find("home").get().isTrusted(friend.getUniqueId()));
    }

    @Test
    void claimAtOnAMissingWorldReturnsEmptyWithoutThrowing() {
        var result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> claimService.claimAt("does_not_exist", 0, 64, 0));
        assertTrue(result.isEmpty());
    }

    @Test
    void protectionAppliesRegardlessOfWhetherTheOwnerIsOnline() throws Exception {
        PlayerMock owner = addPlayer();
        await(claimService.create(owner, "home", loc(100, 60, 100), loc(105, 63, 105)));
        owner.disconnect();

        // Le claim reste consultable (données par UUID, pas par session en ligne).
        java.util.Optional<Claim> claim = claimService.claimAt("world", 102, 61, 102);
        assertTrue(claim.isPresent());
        assertTrue(claim.get().isTrusted(owner.getUniqueId()));
    }
}
