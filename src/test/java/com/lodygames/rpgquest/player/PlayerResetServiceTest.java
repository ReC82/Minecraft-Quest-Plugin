package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.config.JournalConfig;
import com.lodygames.rpgquest.config.TravelConfig;
import com.lodygames.rpgquest.config.TravelConfig.RuneConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaystoneConfig;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.ItemTravelCooldownRepository;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.PortalCooldownRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.database.WaystoneRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.spawn.SpawnService;
import com.lodygames.rpgquest.story.StoryRegistry;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.story.model.StoryState;
import com.lodygames.rpgquest.travel.ItemTravelService;
import com.lodygames.rpgquest.travel.PortalService;
import com.lodygames.rpgquest.travel.YamlDestinationRegistry;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.ui.QuestJournalService;
import com.lodygames.rpgquest.waystone.SimpleWaystoneStructurePlacer;
import com.lodygames.rpgquest.waystone.WaystoneCellPlanner;
import com.lodygames.rpgquest.waystone.WaystoneService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Mission « reset admin complet pour simuler un vrai nouveau joueur RPGQuest ». Couvre
 * {@link PlayerResetService} : suppression par joueur de tout l'état d'onboarding, isolation des
 * autres joueurs, possibilité de recommencer le parcours, comportement en ligne / hors ligne.
 */
class PlayerResetServiceTest {

    private static final long TIMEOUT = 5;
    private static final NamespacedKey PREMIERS_PAS = new NamespacedKey("rpgquest", "premiers_pas");
    private static final String CUSTOM_UNLOCK = "SOME_OTHER_UNLOCK";

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;

    private PlayerProfileRepository profileRepository;
    private QuestProgressRepository questProgressRepository;
    private StoryProgressRepository storyProgressRepository;
    private PlayerVariableRepository variableRepository;
    private ProgressionRepository progressionRepository;
    private PortalCooldownRepository portalCooldownRepository;
    private ItemTravelCooldownRepository itemTravelCooldownRepository;
    private WaystoneRepository waystoneRepository;
    private ClaimRepository claimRepository;

    private QuestProgressEngine questProgressEngine;
    private StoryService storyService;
    private WaystoneService waystoneService;
    private ClaimService claimService;
    private ProgressionService progressionService;
    private QuestJournalService questJournalService;
    private PortalService portalService;
    private ItemTravelService itemTravelService;
    private YamlCustomItemRegistry customItemRegistry;

    private PlayerResetService resetService;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);

        profileRepository = new PlayerProfileRepository(database);
        questProgressRepository = new QuestProgressRepository(database);
        storyProgressRepository = new StoryProgressRepository(database);
        variableRepository = new PlayerVariableRepository(database);
        progressionRepository = new ProgressionRepository(database);
        portalCooldownRepository = new PortalCooldownRepository(database);
        itemTravelCooldownRepository = new ItemTravelCooldownRepository(database);
        waystoneRepository = new WaystoneRepository(database);
        claimRepository = new ClaimRepository(database);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("premiers_pas.yml"), """
                id: rpgquest:premiers_pas
                title: "Premiers pas"
                description: "d"
                category: test
                steps:
                  - id: s1
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                """);
        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, questProgressRepository, variableRepository, messagesService, npcIdentityService);
        questProgressEngine.start();

        StoryRegistry storyRegistry = new StoryRegistry(tempDir.resolve("stories"), plugin.getSLF4JLogger());
        storyRegistry.start();
        storyService = new StoryService(plugin, storyRegistry, storyProgressRepository, profileRepository,
                questProgressEngine, questEngine, messagesService, plugin.getSLF4JLogger());
        storyService.start();

        ConfigService configService = new ConfigService(plugin);
        configService.start();
        progressionService = new ProgressionService(
                plugin, progressionRepository, () -> configService.current().progression(), plugin.getSLF4JLogger());
        progressionService.start();

        YamlPortalRegistry portalRegistry = new YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portalRegistry.start();
        new com.lodygames.rpgquest.zone.ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger()).start();
        com.lodygames.rpgquest.zone.ZoneRegistry zoneRegistry =
                new com.lodygames.rpgquest.zone.ZoneRegistry(tempDir.resolve("zones2"), plugin.getSLF4JLogger());
        zoneRegistry.start();
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();

        SpawnService spawnService = new SpawnService(plugin, tempDir.resolve("spawn.yml"), plugin.getSLF4JLogger());
        spawnService.start();
        TravelConfig travelConfig = new TravelConfig("wild", new RuneConfig(10, 1800),
                new WaystoneConfig(1000L, 0.6, 300, 16, 3));
        waystoneService = new WaystoneService(plugin, waystoneRepository, new WaystoneCellPlanner(),
                new SimpleWaystoneStructurePlacer(), spawnService, () -> travelConfig);
        waystoneService.start();

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        questJournalService = new QuestJournalService(
                plugin, questEngine, questProgressEngine, variableRepository, customItemRegistry, new JournalConfig(true));
        questJournalService.start();

        YamlDestinationRegistry destinationRegistry =
                new YamlDestinationRegistry(tempDir.resolve("destinations"), plugin.getSLF4JLogger());
        destinationRegistry.start();
        EconomyService economyService = new EconomyService(new WalletRepository(database));
        portalService = new PortalService(plugin, portalRegistry, destinationRegistry, economyService,
                questProgressEngine, portalCooldownRepository);
        portalService.start();

        itemTravelService = new ItemTravelService(
                plugin, customItemRegistry, itemTravelCooldownRepository, plugin.getSLF4JLogger());
        itemTravelService.start();

        resetService = new PlayerResetService(plugin, questProgressEngine, storyService, waystoneService, claimService,
                progressionService, questJournalService, portalService, itemTravelService, variableRepository,
                progressionRepository, portalCooldownRepository, itemTravelCooldownRepository, customItemRegistry);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(condition.getAsBoolean(), "condition non atteinte avant le délai");
    }

    /** La complétion de {@code resetToNewPlayer} passe par le thread principal : il faut ticker le scheduler. */
    private PlayerResetService.ResetSummary runReset(UUID uuid, String name) throws Exception {
        CompletableFuture<PlayerResetService.ResetSummary> future = resetService.resetToNewPlayer(uuid, name);
        await(future::isDone);
        return future.get();
    }

    /** Comme {@link #runReset}, {@code previewReset} se termine sur le thread principal. */
    private PlayerResetService.ResetPreview runPreview(UUID uuid) throws Exception {
        CompletableFuture<PlayerResetService.ResetPreview> future = resetService.previewReset(uuid);
        await(future::isDone);
        return future.get();
    }

    private static PlayerResetService.ResetCategory category(PlayerResetService.ResetPreview preview, String label) {
        return preview.categories().stream()
                .filter(c -> c.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("catégorie absente du preview : " + label));
    }

    /** Sème l'état d'onboarding complet d'un joueur directement en base (fonctionne en ligne comme hors ligne). */
    private void seedFullState(UUID uuid, String name) throws Exception {
        profileRepository.findOrCreate(uuid, name).get(TIMEOUT, TimeUnit.SECONDS);
        questProgressRepository.upsertState(uuid, PREMIERS_PAS, QuestState.ACTIVE, "s1").get(TIMEOUT, TimeUnit.SECONDS);
        storyProgressRepository.upsertProgress(uuid, "intro", StoryState.ACTIVE, 0).get(TIMEOUT, TimeUnit.SECONDS);
        variableRepository.set(uuid, ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        variableRepository.set(uuid, CUSTOM_UNLOCK, "true").get(TIMEOUT, TimeUnit.SECONDS);
        variableRepository.set(uuid, "RUNE_RAPPEL_GRANTED", "true").get(TIMEOUT, TimeUnit.SECONDS);
        progressionRepository.setTotalXp(uuid, SkillType.GLOBAL, 750).get(TIMEOUT, TimeUnit.SECONDS);
        portalCooldownRepository.setCooldown(uuid, "hub_to_wild", Instant.now().plusSeconds(600)).get(TIMEOUT, TimeUnit.SECONDS);
        itemTravelCooldownRepository.setCooldown(uuid, RpgItemKeys.RUNE_RAPPEL.toString(), Instant.now().plusSeconds(600)).get(TIMEOUT, TimeUnit.SECONDS);
        waystoneRepository.insertIfAbsent(new com.lodygames.rpgquest.waystone.model.Waystone(
                "ws_seed", "wild", 10, 65, 10, 0, 0, "Seed", Instant.now())).get(TIMEOUT, TimeUnit.SECONDS);
        waystoneRepository.recordDiscovery(uuid, "ws_seed", Instant.now()).get(TIMEOUT, TimeUnit.SECONDS);
        Claim claim = new Claim("main_" + uuid, uuid, "world", -2, 60, -2, 2, 70, 2,
                -50, 60, -50, 50, 70, 50, Set.of(), ClaimFlags.defaults());
        claimRepository.create(claim).get(TIMEOUT, TimeUnit.SECONDS);
        claimService.start(); // recharge le cache mémoire des claims
        await(() -> !claimService.claimsOwnedBy(uuid).isEmpty());
    }

    private boolean dbStateGoneFor(UUID uuid) throws Exception {
        return questProgressRepository.findAll(uuid).get(TIMEOUT, TimeUnit.SECONDS).isEmpty()
                && storyProgressRepository.findAll(uuid).get(TIMEOUT, TimeUnit.SECONDS).isEmpty()
                && variableRepository.get(uuid, ClaimService.CLAIM_TIER_1_KEY).get(TIMEOUT, TimeUnit.SECONDS).isEmpty()
                && variableRepository.get(uuid, CUSTOM_UNLOCK).get(TIMEOUT, TimeUnit.SECONDS).isEmpty()
                && progressionRepository.findAll(uuid).get(TIMEOUT, TimeUnit.SECONDS).isEmpty()
                && portalCooldownRepository.allForPlayer(uuid).get(TIMEOUT, TimeUnit.SECONDS).isEmpty()
                && itemTravelCooldownRepository.allForPlayer(uuid).get(TIMEOUT, TimeUnit.SECONDS).isEmpty()
                && waystoneRepository.discoveriesFor(uuid).get(TIMEOUT, TimeUnit.SECONDS).isEmpty();
    }

    @Test
    void resettingAnOfflinePlayerWipesEveryOnboardingSystemAndSetsThePendingInventoryFlag() throws Exception {
        UUID uuid = UUID.randomUUID();
        seedFullState(uuid, "OfflineTester");

        PlayerResetService.ResetSummary summary = runReset(uuid, "OfflineTester");

        assertFalse(summary.online());
        assertTrue(summary.inventoryDeferred());
        assertTrue(dbStateGoneFor(uuid), "toutes les données RPGQuest persistantes du joueur doivent être supprimées");
        assertFalse(claimRepository.allClaims().get(TIMEOUT, TimeUnit.SECONDS).stream()
                .anyMatch(c -> c.owner().equals(uuid)), "le claim principal doit être supprimé de la base");
        assertTrue(variableRepository.get(uuid, PlayerResetService.PENDING_INVENTORY_KEY)
                        .get(TIMEOUT, TimeUnit.SECONDS).isPresent(),
                "un joueur hors ligne doit recevoir le marqueur de nettoyage d'inventaire différé");
    }

    @Test
    void resettingAnOnlinePlayerAlsoRemovesRpgItemsButKeepsVanillaItems() throws Exception {
        PlayerMock player = server.addPlayer();
        seedFullState(player.getUniqueId(), player.getName());
        // Le plugin complet est chargé : StarterKitListener peut déjà avoir donné une Rune de départ.
        player.getInventory().addItem(customItemRegistry.create(RpgItemKeys.PIERRE_RETOUR, 1).orElseThrow());
        player.getInventory().addItem(customItemRegistry.create(RpgItemKeys.JOURNAL_QUETES, 1).orElseThrow());
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));

        PlayerResetService.ResetSummary summary = runReset(player.getUniqueId(), player.getName());

        assertTrue(summary.online());
        assertTrue(summary.inventoryItemsRemoved() >= 2,
                "au moins la Pierre de retour et le Journal ajoutés doivent avoir été retirés");
        assertFalse(java.util.Arrays.stream(player.getInventory().getContents())
                .filter(java.util.Objects::nonNull).anyMatch(customItemRegistry::isCustomItem),
                "tous les objets RPGQuest doivent être retirés");
        assertTrue(java.util.Arrays.stream(player.getInventory().getContents())
                .filter(java.util.Objects::nonNull).anyMatch(s -> s.getType() == Material.DIAMOND),
                "l'inventaire vanilla ne doit jamais être vidé");
        assertTrue(dbStateGoneFor(player.getUniqueId()));
    }

    @Test
    void resettingOnePlayerNeverTouchesAnother() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seedFullState(a, "PlayerA");
        seedFullState(b, "PlayerB");

        runReset(a, "PlayerA");

        assertFalse(dbStateGoneFor(b), "les données de l'autre joueur doivent rester intactes");
        assertTrue(claimRepository.allClaims().get(TIMEOUT, TimeUnit.SECONDS).stream()
                .anyMatch(c -> c.owner().equals(b)), "le claim de l'autre joueur ne doit pas être supprimé");
        assertEquals(750L, progressionRepository.findAll(b).get(TIMEOUT, TimeUnit.SECONDS).getOrDefault(SkillType.GLOBAL, 0L));
    }

    @Test
    void afterResetTheOnboardingPathCanBeStartedAgain() throws Exception {
        UUID uuid = UUID.randomUUID();
        seedFullState(uuid, "Restarter");

        runReset(uuid, "Restarter");

        assertFalse(claimService.hasClaimTierOne(uuid).get(TIMEOUT, TimeUnit.SECONDS),
                "CLAIM_TIER_1 doit être de nouveau verrouillé");
        assertEquals(QuestState.NOT_STARTED,
                questProgressEngine.stateOf(uuid, PREMIERS_PAS).get(TIMEOUT, TimeUnit.SECONDS),
                "la quête d'introduction doit pouvoir être reprise depuis zéro");
        assertTrue(storyProgressRepository.findAll(uuid).get(TIMEOUT, TimeUnit.SECONDS).isEmpty());
    }

    // ---- Issue #8 : preview / dry-run --------------------------------------------------------------

    @Test
    void previewOfAnOfflinePlayerListsAffectedCategoriesAndWritesNothing() throws Exception {
        UUID uuid = UUID.randomUUID();
        seedFullState(uuid, "PreviewOffline");

        PlayerResetService.ResetPreview preview = runPreview(uuid);

        assertFalse(preview.online());
        assertTrue(category(preview, "Quêtes").count() >= 1);
        assertTrue(category(preview, "Stories").count() >= 1);
        assertTrue(category(preview, "Variables / unlocks").count() >= 1);
        assertEquals(1, category(preview, "Déblocage CLAIM_TIER_1").count());
        assertTrue(category(preview, "Progression RPG").count() >= 1);
        assertEquals(1, category(preview, "Découvertes de Waystones").count());
        assertTrue(category(preview, "Cooldowns de portails").count() >= 1);
        assertTrue(category(preview, "Cooldowns de voyage par objet (Rune…)").count() >= 1);
        assertEquals(1, category(preview, "Claim principal").count());
        assertFalse(category(preview, "Inventaire (objets RPGQuest)").inspectable(),
                "l'inventaire d'un joueur hors ligne n'est pas inspectable par le preview");

        // Aucune écriture : tout l'état d'onboarding est encore là après le preview.
        assertFalse(dbStateGoneFor(uuid), "le preview ne doit rien supprimer en base");
        assertTrue(claimRepository.allClaims().get(TIMEOUT, TimeUnit.SECONDS).stream()
                .anyMatch(c -> c.owner().equals(uuid)), "le preview ne doit pas supprimer le claim");
        assertTrue(claimService.hasClaimTierOne(uuid).get(TIMEOUT, TimeUnit.SECONDS),
                "CLAIM_TIER_1 doit rester débloqué après un simple preview");
        assertTrue(variableRepository.get(uuid, PlayerResetService.PENDING_INVENTORY_KEY)
                        .get(TIMEOUT, TimeUnit.SECONDS).isEmpty(),
                "le preview ne doit jamais poser le marqueur de nettoyage d'inventaire différé");
    }

    @Test
    void previewOfAnOnlinePlayerCountsRpgItemsAndLeavesEverythingInPlace() throws Exception {
        PlayerMock player = server.addPlayer();
        seedFullState(player.getUniqueId(), player.getName());
        player.getInventory().addItem(customItemRegistry.create(RpgItemKeys.PIERRE_RETOUR, 1).orElseThrow());
        player.getInventory().addItem(customItemRegistry.create(RpgItemKeys.JOURNAL_QUETES, 1).orElseThrow());
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));

        PlayerResetService.ResetPreview preview = runPreview(player.getUniqueId());

        assertTrue(preview.online());
        PlayerResetService.ResetCategory inventory = category(preview, "Inventaire (objets RPGQuest)");
        assertTrue(inventory.inspectable());
        assertTrue(inventory.count() >= 2,
                "au moins la Pierre de retour et le Journal ajoutés doivent être comptés");

        assertTrue(java.util.Arrays.stream(player.getInventory().getContents())
                        .filter(java.util.Objects::nonNull).anyMatch(customItemRegistry::isCustomItem),
                "le preview ne doit retirer aucun objet de l'inventaire");
        assertFalse(dbStateGoneFor(player.getUniqueId()), "le preview ne doit rien supprimer en base");
    }

    @Test
    void previewOfAPristinePlayerReportsEveryCategoryAsEmptyOrNotApplicable() throws Exception {
        UUID uuid = UUID.randomUUID();

        PlayerResetService.ResetPreview preview = runPreview(uuid);

        assertFalse(preview.online());
        for (PlayerResetService.ResetCategory c : preview.categories()) {
            assertTrue(c.empty() || !c.inspectable(),
                    "catégorie inattendue non vide pour un joueur vierge : " + c.label());
        }
        assertEquals(0, category(preview, "Déblocage CLAIM_TIER_1").count());
    }
}
