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
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issues #21/#22/#23 : {@link ClaimWorldAccessGuard} n'ouvre le monde des claims qu'aux joueurs
 * qui ont réellement débloqué leur premier terrain (variable {@code CLAIM_TIER_1} ou claim
 * existant), refuse les autres sans les téléporter, et se re-verrouille après un reset.
 */
class ClaimWorldAccessGuardTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final ClaimConfig CLAIMS_CONFIG = new ClaimConfig(64, 384, 3, 16, "claims", true);
    private static final WorldPortalDefinition TO_CLAIMS = new WorldPortalDefinition(
            "hub_to_claims", "world_hub", 0, 0, 0, 4, 4, 4, "claims", true);
    private static final WorldPortalDefinition TO_WILD = new WorldPortalDefinition(
            "hub_to_wild", "world_hub", 0, 0, 0, 4, 4, 4, "wild", true);

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private ClaimService claimService;
    private ClaimWorldAccessGuard guard;
    private final AtomicInteger teleportNowCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        server.addSimpleWorld("world_hub");
        server.addSimpleWorld("claims");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        variableRepository = new PlayerVariableRepository(database);
        ClaimRepository claimRepository = new ClaimRepository(database);
        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        zoneRegistry.start();
        com.lodygames.rpgquest.travel.YamlPortalRegistry portalRegistry =
                new com.lodygames.rpgquest.travel.YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portalRegistry.start();
        ConfigService configService = new ConfigService(plugin);
        configService.start();
        ProgressionService progressionService = new ProgressionService(
                plugin, new ProgressionRepository(database), () -> configService.current().progression(), plugin.getSLF4JLogger());
        progressionService.start();
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();
        // Réchauffe l'exécuteur SQLite (première requête notablement plus lente) pour que le
        // contrôle asynchrone du garde s'achève dans la fenêtre de pumpUntil.
        variableRepository.get(new java.util.UUID(0, 0), "warmup").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        guard = new ClaimWorldAccessGuard(plugin, claimService, () -> CLAIMS_CONFIG,
                (player, portal) -> teleportNowCalls.incrementAndGet());
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
        player.teleport(new Location(server.getWorld("world_hub"), 2, 2, 2));
        player.nextMessage(); // vide le message de join éventuel.
        return player;
    }

    private void grantTierOne(PlayerMock player) throws Exception {
        variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Pompe des ticks jusqu'à ce que la condition soit vraie, ou échoue après ~2 s. */
    private void pumpUntil(BooleanSupplier condition, String what) throws Exception {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(15);
        }
        assertTrue(condition.getAsBoolean(), () -> "condition jamais atteinte : " + what);
    }

    private List<String> drainMessages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        String next;
        while ((next = player.nextMessage()) != null) {
            out.add(next);
        }
        return out;
    }

    /**
     * Pompe des ticks en accumulant les messages reçus (lecture destructive de {@code nextMessage},
     * donc jamais réévaluée dans une assertion) et échoue si le refus n'arrive pas.
     */
    private void awaitRefusal(PlayerMock player) throws Exception {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(15);
            seen.addAll(drainMessages(player));
            if (seen.stream().anyMatch(m -> m.contains("réservé aux joueurs"))) {
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("message de refus jamais reçu ; messages : " + seen);
    }

    @Test
    void aFreshPlayerWithoutUnlockIsRefusedAndNeverTeleported() throws Exception {
        PlayerMock player = addPlayer();

        assertFalse(guard.allowEntry(player, TO_CLAIMS), "l'entrée immédiate doit être bloquée");
        awaitRefusal(player);

        server.getScheduler().performTicks(5);
        assertEquals(0, teleportNowCalls.get(), "aucun joueur non éligible ne doit être téléporté dans le monde des claims");
    }

    @Test
    void aPlayerWithClaimTierOneIsLetThrough() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);

        assertFalse(guard.allowEntry(player, TO_CLAIMS), "le passage réel se fait via teleportNow, après le contrôle async");
        pumpUntil(() -> teleportNowCalls.get() >= 1, "téléportation relancée pour un joueur éligible");
        assertTrue(drainMessages(player).stream().noneMatch(m -> m.contains("réservé aux joueurs")), "aucun refus attendu");
    }

    @Test
    void aPlayerWhoAlreadyOwnsAClaimIsLetThroughImmediately() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        World claimWorld = server.addSimpleWorld("world_for_claim");
        var outcome = claimService.create(player, "main_" + player.getUniqueId(),
                        new Location(claimWorld, 0, 60, 0), new Location(claimWorld, 4, 63, 4))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome);
        server.getScheduler().performTicks(2);

        assertTrue(guard.allowEntry(player, TO_CLAIMS), "un propriétaire de claim doit toujours pouvoir revenir");
        assertEquals(0, teleportNowCalls.get(), "autorisé de façon synchrone : pas de relance via teleportNow");
    }

    @Test
    void theExplicitBypassPermissionPasses() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);

        assertTrue(guard.allowEntry(player, TO_CLAIMS));
        assertTrue(drainMessages(player).isEmpty());
    }

    @Test
    void resettingAPreviouslyEligiblePlayerRefusesAccessAgain() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        assertFalse(guard.allowEntry(player, TO_CLAIMS));
        pumpUntil(() -> teleportNowCalls.get() >= 1, "1er passage éligible");
        drainMessages(player);
        teleportNowCalls.set(0);
        // Laisse expirer le laissez-passer court (CLEARED_TTL_TICKS) accordé par le passage réussi,
        // sinon le contrôle suivant serait court-circuité par cette fenêtre — sans rapport avec le reset.
        server.getScheduler().performTicks(220);

        // Simule /rpgadmin player resetnew : toutes les variables du joueur sont effacées.
        variableRepository.deleteAllForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(guard.allowEntry(player, TO_CLAIMS), "après reset, l'accès doit être de nouveau refusé");
        awaitRefusal(player);
        server.getScheduler().performTicks(5);
        assertEquals(0, teleportNowCalls.get(), "aucune téléportation après reset");
    }

    @Test
    void portalsThatDoNotLeadToTheClaimsWorldAreNeverAffected() throws Exception {
        PlayerMock player = addPlayer();

        assertTrue(guard.allowEntry(player, TO_WILD), "un portail vers le Wild n'est pas concerné par ce garde");
        assertTrue(drainMessages(player).isEmpty());
        assertEquals(0, teleportNowCalls.get());
    }
}
