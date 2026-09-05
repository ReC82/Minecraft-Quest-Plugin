package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Couvre l'UX sans commande de l'Acte de propriété (mission « premier claim 5×5 ») : clic droit
 * dans le monde des claims -> aperçu -> second clic droit -> claim réellement créé. Utilise le vrai
 * {@link YamlCustomItemRegistry} (objet {@code rpgquest:acte_propriete} généré par {@code
 * ensureExamplesExist}) pour exercer exactement l'identification par PDC utilisée en jeu, pas une
 * substitution simplifiée.
 */
class DeedClaimListenerTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final ClaimConfig CLAIMS_CONFIG = new ClaimConfig(64, 384, 3, 16, "claims", true);

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private ClaimService claimService;
    private YamlCustomItemRegistry customItemRegistry;
    private RecordingBorderRenderer borderRenderer;
    private DeedClaimListener listener;
    private World claimsWorld;
    private World other;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        claimsWorld = server.addSimpleWorld("claims");
        other = server.addSimpleWorld("world");

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

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start(); // génère notamment acte_propriete.yml.

        borderRenderer = new RecordingBorderRenderer(plugin);
        listener = new DeedClaimListener(plugin, claimService, customItemRegistry, borderRenderer, () -> CLAIMS_CONFIG);
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

    private ItemStack deed() {
        return customItemRegistry.create(DeedClaimListener.DEED_ITEM_ID, 1).orElseThrow();
    }

    private void rightClick(PlayerMock player, ItemStack item, Block block) throws Exception {
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, item, block, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
        listener.onInteract(event);
        // Les continuations (hasClaimTierOne/create) sont async (thread DB) puis republiées via
        // runTask : laisse quelques ticks s'exécuter avant que le test n'observe le résultat.
        for (int i = 0; i < 20; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }

    // ---- CLAIM_TIER_1 absent -----------------------------------------------------------------

    @Test
    void deedIsRefusedWithoutClaimTierOne() throws Exception {
        PlayerMock player = addPlayer();
        Block block = claimsWorld.getBlockAt(100, 64, 100);

        rightClick(player, deed(), block);

        assertTrue(claimService.claimsOwnedBy(player.getUniqueId()).isEmpty(),
                "sans CLAIM_TIER_1, aucun aperçu ne doit mener à un claim");
    }

    // ---- Hors du monde des claims -----------------------------------------------------------

    @Test
    void deedUsedOutsideTheClaimsWorldIsRefusedCleanly() throws Exception {
        PlayerMock player = addPlayer();
        grantClaimTierOne(player);
        Block block = other.getBlockAt(100, 64, 100);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> rightClick(player, deed(), block));

        assertTrue(claimService.claimsOwnedBy(player.getUniqueId()).isEmpty(),
                "hors du monde des claims, aucun claim ne doit jamais être créé");
    }

    // ---- Aperçu 5×5 puis confirmation --------------------------------------------------------

    @Test
    void firstRightClickOnlyPreviewsAndCreatesNoClaimYet() throws Exception {
        PlayerMock player = addPlayer();
        grantClaimTierOne(player);
        Block block = claimsWorld.getBlockAt(100, 64, 100);

        rightClick(player, deed(), block);

        assertTrue(claimService.claimsOwnedBy(player.getUniqueId()).isEmpty(),
                "le premier clic droit doit uniquement afficher un aperçu, jamais créer le claim immédiatement");
    }

    @Test
    void secondRightClickOnTheSameTargetConfirmsAndCreatesA5x5Claim() throws Exception {
        PlayerMock player = addPlayer();
        grantClaimTierOne(player);
        Block block = claimsWorld.getBlockAt(100, 64, 100);
        ItemStack item = deed();
        player.getInventory().setItemInMainHand(item);

        rightClick(player, item, block); // aperçu
        rightClick(player, item, block); // confirmation

        var claims = claimService.claimsOwnedBy(player.getUniqueId());
        assertEquals(1, claims.size(), "la confirmation doit créer exactement un claim");
        Claim claim = claims.get(0);
        assertEquals(5, claim.width(), "le cuboïde actif doit être 5×5 (TIER_1)");
        assertEquals(5, claim.depth());
        assertEquals(100, claim.reservedMaxX() - claim.reservedMinX() + 1, "la réservation doit être 100×100");
        assertEquals(100, claim.reservedMaxZ() - claim.reservedMinZ() + 1);
        assertTrue(claim.minX() <= 100 && claim.maxX() >= 100 && claim.minZ() <= 100 && claim.maxZ() >= 100,
                "le claim actif doit être centré sur le bloc visé (100,100) : " + claim);
    }

    @Test
    void confirmingConsumesExactlyOneDeed() throws Exception {
        PlayerMock player = addPlayer();
        grantClaimTierOne(player);
        Block block = claimsWorld.getBlockAt(100, 64, 100);
        ItemStack item = deed();
        item.setAmount(1);
        player.getInventory().setItemInMainHand(item);

        rightClick(player, item, block);
        rightClick(player, item, block);

        boolean stillHasDeed = java.util.Arrays.stream(player.getInventory().getContents())
                .filter(java.util.Objects::nonNull)
                .anyMatch(stack -> customItemRegistry.identify(stack).map(DeedClaimListener.DEED_ITEM_ID::equals).orElse(false));
        assertFalse(stillHasDeed, "l'Acte doit être consommé après la création réussie du claim");
    }

    @Test
    void claimingASecondTimeAfterAlreadyOwningOneFailsCleanly() throws Exception {
        PlayerMock player = addPlayer();
        grantClaimTierOne(player);
        Block first = claimsWorld.getBlockAt(100, 64, 100);
        ItemStack item = deed();
        player.getInventory().setItemInMainHand(item);
        rightClick(player, item, first);
        rightClick(player, item, first);
        assertEquals(1, claimService.claimsOwnedBy(player.getUniqueId()).size());

        // Un 2e Acte (redonné par Jo si perdu) utilisé ailleurs ne doit jamais créer de 2e claim
        // principal via ce chemin (le dialogue Jo ne le propose plus, mais on vérifie ici la
        // défense en profondeur côté ClaimService/DeedClaimListener eux-mêmes).
        ItemStack secondDeed = deed();
        player.getInventory().setItemInMainHand(secondDeed);
        Block second = claimsWorld.getBlockAt(300, 64, 300);
        rightClick(player, secondDeed, second);
        rightClick(player, secondDeed, second);

        assertEquals(1, claimService.claimsOwnedBy(player.getUniqueId()).size(),
                "un joueur ayant déjà un claim ne doit jamais en obtenir un second via ce chemin");
    }

    private void grantClaimTierOne(PlayerMock player) throws Exception {
        variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // ---- Double usage de l'Acte : outil de visualisation une fois le claim posé ----------------

    @Test
    void rightClickingTheDeedOnceAMainClaimExistsShowsTheBorderInsteadOfRefusing() throws Exception {
        PlayerMock player = addPlayer();
        grantClaimTierOne(player);
        Block first = claimsWorld.getBlockAt(100, 64, 100);
        ItemStack item = deed();
        player.getInventory().setItemInMainHand(item);
        rightClick(player, item, first);
        rightClick(player, item, first); // claim créé, Acte consommé.
        String claimId = claimService.claimsOwnedBy(player.getUniqueId()).get(0).id();
        borderRenderer.shownClaimIds.clear();

        // Jo redonne un Acte pour visualiser (voir jo.yml, choix « Revoir les limites »). Le joueur
        // se trouve dans son propre claim (mission « retrouver visuellement son claim à distance » :
        // le périmètre n'est affiché que depuis l'intérieur, voir le test dédié pour le cas contraire).
        player.teleport(new Location(claimsWorld, 100.5, 64, 100.5));
        ItemStack viewerDeed = deed();
        player.getInventory().setItemInMainHand(viewerDeed);
        rightClick(player, viewerDeed, claimsWorld.getBlockAt(100, 64, 100));

        assertEquals(List.of(claimId), borderRenderer.shownClaimIds, "doit afficher les limites du claim déjà possédé, pas tenter une seconde création");
        assertTrue(borderRenderer.shownBeaconClaimIds.isEmpty(), "depuis l'intérieur du claim, jamais le faisceau à distance");
    }

    @Test
    void rightClickingTheDeedOutsideOwnMainClaimShowsTheBeaconInstead() throws Exception {
        PlayerMock player = addPlayer();
        grantClaimTierOne(player);
        Block first = claimsWorld.getBlockAt(100, 64, 100);
        ItemStack item = deed();
        player.getInventory().setItemInMainHand(item);
        rightClick(player, item, first);
        rightClick(player, item, first); // claim créé, Acte consommé.
        String claimId = claimService.claimsOwnedBy(player.getUniqueId()).get(0).id();
        borderRenderer.shownClaimIds.clear();

        // Le joueur est ailleurs dans le monde des claims, hors de son propre claim.
        player.teleport(new Location(claimsWorld, 300.5, 64, 300.5));
        ItemStack viewerDeed = deed();
        player.getInventory().setItemInMainHand(viewerDeed);
        rightClick(player, viewerDeed, claimsWorld.getBlockAt(300, 64, 300));

        assertEquals(List.of(claimId), borderRenderer.shownBeaconClaimIds,
                "hors de son claim, doit afficher le faisceau à distance plutôt que le périmètre");
        assertTrue(borderRenderer.shownClaimIds.isEmpty(), "hors de son claim, jamais le périmètre au sol");
    }

    @Test
    void rightClickingTheDeedOnceAMainClaimExistsNeverConsumesIt() throws Exception {
        PlayerMock player = addPlayer();
        grantClaimTierOne(player);
        ItemStack firstItem = deed();
        player.getInventory().setItemInMainHand(firstItem);
        Block first = claimsWorld.getBlockAt(100, 64, 100);
        rightClick(player, firstItem, first);
        rightClick(player, firstItem, first);

        ItemStack viewerDeed = deed();
        viewerDeed.setAmount(1);
        player.getInventory().setItemInMainHand(viewerDeed);
        rightClick(player, viewerDeed, claimsWorld.getBlockAt(300, 64, 300));

        boolean stillHasDeed = java.util.Arrays.stream(player.getInventory().getContents())
                .filter(java.util.Objects::nonNull)
                .anyMatch(stack -> customItemRegistry.identify(stack).map(DeedClaimListener.DEED_ITEM_ID::equals).orElse(false));
        assertTrue(stillHasDeed, "l'Acte réutilisé comme visualiseur ne doit jamais être consommé");
    }

    /** Enregistre les appels à {@link #show}/{@link #showBeacon} plutôt que de vraiment envoyer des particules (non observables sous MockBukkit). */
    private static final class RecordingBorderRenderer extends ClaimBorderRenderer {
        private final List<String> shownClaimIds = new java.util.ArrayList<>();
        private final List<String> shownBeaconClaimIds = new java.util.ArrayList<>();

        RecordingBorderRenderer(RPGQuestPlugin plugin) {
            super(plugin);
        }

        @Override
        public void show(org.bukkit.entity.Player player, Claim claim) {
            shownClaimIds.add(claim.id());
        }

        @Override
        public void showBeacon(org.bukkit.entity.Player player, Claim claim) {
            shownBeaconClaimIds.add(claim.id());
        }
    }
}
