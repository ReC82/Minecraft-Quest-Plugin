package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.model.Claim;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Couvre uniquement la <strong>détection</strong> d'entrée (mission « visualisation des limites du
 * claim », architecture explicitement séparée du rendu) — {@link ClaimBorderRenderer#show} n'est
 * jamais réellement invoqué en jeu ici : {@link RecordingBorderRenderer} enregistre seulement les
 * appels (MockBukkit ne simule pas {@code Player#spawnParticle}, rien à observer côté particules).
 */
class ClaimBorderEntryListenerTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private ClaimService claimService;
    private RecordingBorderRenderer renderer;
    private ClaimBorderEntryListener listener;
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

        renderer = new RecordingBorderRenderer(plugin);
        listener = new ClaimBorderEntryListener(claimService, renderer);
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

    private Claim createClaim(PlayerMock owner) throws Exception {
        var outcome = claimService.create(owner, "main_" + owner.getUniqueId(),
                        new Location(claimsWorld, -2, 60, -2), new Location(claimsWorld, 2, 63, 2))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome, "pré-requis du test : le claim doit vraiment être créé");
        server.getScheduler().performTicks(2);
        return claimService.mainClaimOf(owner.getUniqueId()).orElseThrow();
    }

    private Location outside() {
        return new Location(claimsWorld, 500.5, 64, 500.5);
    }

    private Location insideClaim() {
        return new Location(claimsWorld, 0.5, 61, 0.5);
    }

    @Test
    void ownerEnteringTheirOwnClaimFromOutsideTriggersTheRender() throws Exception {
        PlayerMock owner = addPlayer();
        Claim claim = createClaim(owner);

        listener.onMove(new PlayerMoveEvent(owner, outside(), insideClaim()));

        assertEquals(List.of(claim.id()), renderer.shownClaimIds);
    }

    @Test
    void walkingInsideTheSameClaimNeverRetriggers() throws Exception {
        PlayerMock owner = addPlayer();
        createClaim(owner);

        listener.onMove(new PlayerMoveEvent(owner, outside(), insideClaim()));
        listener.onMove(new PlayerMoveEvent(owner, insideClaim(), new Location(claimsWorld, 1.5, 61, 1.5)));
        listener.onMove(new PlayerMoveEvent(owner, new Location(claimsWorld, 1.5, 61, 1.5), new Location(claimsWorld, -1.5, 61, -1.5)));

        assertEquals(1, renderer.shownClaimIds.size(), "aucun spam tant que le joueur reste dans le même claim");
    }

    @Test
    void leavingThenReenteringTriggersTheRenderAgain() throws Exception {
        PlayerMock owner = addPlayer();
        createClaim(owner);

        listener.onMove(new PlayerMoveEvent(owner, outside(), insideClaim()));
        listener.onMove(new PlayerMoveEvent(owner, insideClaim(), outside()));
        listener.onMove(new PlayerMoveEvent(owner, outside(), insideClaim()));

        assertEquals(2, renderer.shownClaimIds.size(), "sortir puis revenir doit réarmer la détection");
    }

    @Test
    void visitorEnteringAnotherPlayersClaimNeverTriggersTheRender() throws Exception {
        PlayerMock owner = addPlayer();
        createClaim(owner);
        PlayerMock visitor = addPlayer();

        listener.onMove(new PlayerMoveEvent(visitor, outside(), insideClaim()));

        assertTrue(renderer.shownClaimIds.isEmpty(), "un visiteur ne doit jamais déclencher le rendu (particules privées au propriétaire)");
    }

    @Test
    void lookOnlyMovementWithinTheSameBlockNeverTriggersAnything() throws Exception {
        PlayerMock owner = addPlayer();
        createClaim(owner);
        Location sameBlockA = new Location(claimsWorld, 0.2, 61, 0.2, 0f, 0f);
        Location sameBlockB = new Location(claimsWorld, 0.3, 61, 0.3, 45f, 10f);

        listener.onMove(new PlayerMoveEvent(owner, sameBlockA, sameBlockB));

        assertTrue(renderer.shownClaimIds.isEmpty(), "un simple changement de vue dans le même bloc n'est jamais une transition de claim");
    }

    /** Enregistre les appels à {@link #show} plutôt que de vraiment envoyer des particules (non observables sous MockBukkit). */
    private static final class RecordingBorderRenderer extends ClaimBorderRenderer {
        private final List<String> shownClaimIds = new ArrayList<>();

        RecordingBorderRenderer(RPGQuestPlugin plugin) {
            super(plugin);
        }

        @Override
        public void show(Player player, Claim claim) {
            shownClaimIds.add(claim.id());
        }
    }
}
