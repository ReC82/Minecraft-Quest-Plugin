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
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issues #21/#22/#23 : {@link ClaimWorldSafetyListener} garantit qu'un joueur légitimement présent
 * dans le monde des claims a toujours une Pierre de retour (retour Hub sans commande) et qu'un
 * joueur non éligible ne peut jamais y rester coincé (renvoyé au village).
 */
class ClaimWorldSafetyListenerTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final ClaimConfig CLAIMS_CONFIG = new ClaimConfig(64, 384, 3, 16, "claims", true);

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private YamlCustomItemRegistry customItemRegistry;
    private ClaimService claimService;
    private ClaimWorldSafetyListener listener;
    private World hubWorld;
    private World claimsWorld;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hubWorld = server.addSimpleWorld("world_hub");
        claimsWorld = server.addSimpleWorld("claims");

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
        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();
        // Réchauffe l'exécuteur SQLite (première requête notablement plus lente) pour que les
        // contrôles asynchrones du listener s'achèvent dans la fenêtre de pumpUntil.
        variableRepository.get(new java.util.UUID(0, 0), "warmup").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        listener = new ClaimWorldSafetyListener(plugin, claimService, customItemRegistry, () -> CLAIMS_CONFIG,
                () -> Optional.of(new Location(hubWorld, 0, 64, 0)));
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

    private void grantTierOne(PlayerMock player) throws Exception {
        variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Place le joueur dans le monde des claims via {@code setLocation} (aucun événement) puis
     * invoque directement le handler — {@code player.teleport(...)} déclencherait un vrai {@code
     * PlayerChangedWorldEvent} capté par l'instance de {@link ClaimWorldSafetyListener} enregistrée
     * par le vrai bootstrap du plugin (chargé par MockBukkit), qui agirait en parallèle avec sa
     * propre base vide. Même précaution que {@code ClaimsWorldRulesListenerTest} (events construits,
     * jamais {@code callEvent}).
     */
    private void arriveInClaims(PlayerMock player) {
        player.setLocation(new Location(claimsWorld, 0.5, 64, 0.5));
        listener.onWorldChange(new PlayerChangedWorldEvent(player, hubWorld));
    }

    private void pumpUntil(BooleanSupplier condition, String what) throws Exception {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(15);
        }
        assertTrue(condition.getAsBoolean(), () -> "condition jamais atteinte : " + what);
    }

    private boolean hasReturnStone(PlayerMock player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(s -> customItemRegistry.identify(s).map(RpgItemKeys.PIERRE_RETOUR::equals).orElse(false));
    }

    private int returnStoneCount(PlayerMock player) {
        int n = 0;
        for (ItemStack s : player.getInventory().getContents()) {
            if (s != null && customItemRegistry.identify(s).map(RpgItemKeys.PIERRE_RETOUR::equals).orElse(false)) {
                n += s.getAmount();
            }
        }
        return n;
    }

    @Test
    void anEligiblePlayerEnteringTheClaimsWorldWithoutAReturnStoneReceivesOne() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);

        arriveInClaims(player);

        pumpUntil(() -> hasReturnStone(player), "Pierre de retour donnée à l'arrivée");
        assertEquals(1, returnStoneCount(player));
    }

    @Test
    void aSecondArrivalNeverGrantsADuplicateReturnStone() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);

        arriveInClaims(player);
        pumpUntil(() -> hasReturnStone(player), "1re Pierre de retour");
        arriveInClaims(player);
        server.getScheduler().performTicks(10);

        assertEquals(1, returnStoneCount(player), "jamais de second exemplaire");
    }

    @Test
    void aNonEligiblePlayerIsSentBackToTheHub() throws Exception {
        PlayerMock player = addPlayer();

        arriveInClaims(player);

        pumpUntil(() -> player.getWorld().getName().equals("world_hub"), "renvoi au Hub");
        assertFalse(hasReturnStone(player), "un joueur non éligible n'a pas besoin d'une Pierre de retour, il est renvoyé");
        boolean explained = false;
        String msg;
        while ((msg = player.nextMessage()) != null) {
            if (msg.contains("ramené au village") || msg.contains("réservé aux joueurs")) {
                explained = true;
            }
        }
        assertTrue(explained, "le renvoi doit être expliqué au joueur");
    }

    @Test
    void aPlayerWhoOwnsAClaimIsNeverBouncedAndGetsAStone() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        World claimWorld = server.addSimpleWorld("world_for_claim");
        var outcome = claimService.create(player, "main_" + player.getUniqueId(),
                        new Location(claimWorld, 0, 60, 0), new Location(claimWorld, 4, 63, 4))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome);
        server.getScheduler().performTicks(2);

        arriveInClaims(player);

        pumpUntil(() -> hasReturnStone(player), "Pierre de retour pour un propriétaire de claim");
        assertEquals("claims", player.getWorld().getName(), "un propriétaire n'est jamais renvoyé");
    }

    @Test
    void theExplicitBypassPermissionIsNeitherBouncedNorHandedAStone() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);

        arriveInClaims(player);
        server.getScheduler().performTicks(10);

        assertEquals("claims", player.getWorld().getName(), "un joueur avec le bypass reste sur place");
        assertFalse(hasReturnStone(player), "aucun objet imposé à un joueur avec le bypass");
    }

    @Test
    void joiningInsideTheClaimsWorldIsHandledLikeAnArrival() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        player.setLocation(new Location(claimsWorld, 0.5, 64, 0.5));

        listener.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));

        pumpUntil(() -> hasReturnStone(player), "Pierre de retour donnée à la connexion dans le monde des claims");
    }
}
