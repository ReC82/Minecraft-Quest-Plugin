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

        ClaimReturnService returnService = new ClaimReturnService(plugin, customItemRegistry,
                () -> Optional.of(new Location(hubWorld, 0, 64, 0)));
        listener = new ClaimWorldSafetyListener(plugin, claimService, returnService, () -> CLAIMS_CONFIG,
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

    /**
     * Issue #22 — ce test affirmait l'inverse jusqu'au 05/10 (« aucun objet imposé à un joueur avec
     * le bypass »), et figeait donc le piège : un administrateur éligible arrivé dans le monde des
     * claims n'était ni renvoyé, ni doté d'une Pierre de retour, donc sans aucune sortie. C'est
     * exactement le blocage signalé. Le bypass garde son seul sens défendable — ne jamais
     * téléporter de force quelqu'un venu volontairement — mais il ne prive plus du moyen de
     * repartir.
     */
    @Test
    void theExplicitBypassPermissionIsNeverBouncedButStillGetsAStone() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);

        arriveInClaims(player);

        pumpUntil(() -> hasReturnStone(player), "Pierre de retour garantie malgré le bypass");
        assertEquals("claims", player.getWorld().getName(),
                "un joueur avec le bypass n'est JAMAIS téléporté de force");
        assertEquals(1, returnStoneCount(player), "jamais de second exemplaire");
    }

    @Test
    void aBypassHolderWhoAlreadyHasAStoneGetsNoDuplicate() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);

        arriveInClaims(player);
        pumpUntil(() -> hasReturnStone(player), "1re Pierre de retour");
        arriveInClaims(player);
        server.getScheduler().performTicks(10);

        assertEquals(1, returnStoneCount(player));
    }

    @Test
    void aBypassHolderWithAFullInventoryGetsTheStoneAtTheirFeetAndIsToldSo() throws Exception {
        PlayerMock player = addPlayer();
        player.addAttachment(plugin, ClaimWorldAccessGuard.BYPASS_PERMISSION, true);
        fillInventory(player);
        assertEquals(-1, player.getInventory().firstEmpty(),
                "précondition : l'inventaire doit être réellement plein");

        arriveInClaims(player);
        server.getScheduler().performTicks(10);

        // L'objet ne peut pas entrer dans l'inventaire : il tombe au sol. Le point corrigé est le
        // MESSAGE — avant, le joueur lisait « tu reçois une Pierre de retour » et la cherchait
        // dans des poches pleines.
        assertFalse(hasReturnStone(player), "inventaire plein : l'objet ne peut pas y entrer");
        boolean toldAboutTheGround = false;
        String msg;
        while ((msg = player.nextMessage()) != null) {
            if (msg.contains("à tes pieds") || msg.contains("inventaire")) {
                toldAboutTheGround = true;
            }
        }
        assertTrue(toldAboutTheGround,
                "le joueur doit être prévenu que la Pierre est au sol, pas dans son inventaire");
    }

    @Test
    void anEligiblePlayerWithAFullInventoryIsAlsoToldWhereTheStoneWent() throws Exception {
        PlayerMock player = addPlayer();
        grantTierOne(player);
        fillInventory(player);
        assertEquals(-1, player.getInventory().firstEmpty(),
                "précondition : l'inventaire doit être réellement plein");

        arriveInClaims(player);
        server.getScheduler().performTicks(10);

        boolean toldAboutTheGround = false;
        String msg;
        while ((msg = player.nextMessage()) != null) {
            if (msg.contains("à tes pieds")) {
                toldAboutTheGround = true;
            }
        }
        assertTrue(toldAboutTheGround, "même message explicite pour un joueur éligible non admin");
    }

    /**
     * Remplit l'inventaire jusqu'à saturation réelle.
     *
     * <p>On ne présume pas le nombre d'emplacements : {@code firstEmpty()} de MockBukkit renvoie
     * encore 36 après avoir rempli 0–35, alors que le rangement d'un joueur Bukkit s'arrête à 35.
     * Boucler sur {@code firstEmpty()} rend le test juste quelle que soit cette différence, au lieu
     * de tester un inventaire qu'on croit plein.</p>
     */
    private void fillInventory(PlayerMock player) {
        for (int guard = 0; guard < 64; guard++) {
            int slot = player.getInventory().firstEmpty();
            if (slot < 0) {
                return;
            }
            player.getInventory().setItem(slot,
                    new org.bukkit.inventory.ItemStack(org.bukkit.Material.COBBLESTONE, 64));
        }
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
