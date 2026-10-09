package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.config.StarterKitTier;
import com.lodygames.rpgquest.config.StarterToolKitConfig;
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
import java.util.List;
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
    private StarterToolKitService starterToolKitService;

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
        EconomyService economyService = new EconomyService(new WalletRepository(database));
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, questProgressRepository, variableRepository, messagesService, npcIdentityService,
                economyService);
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

        SpawnService spawnService = new SpawnService(plugin, tempDir.resolve("spawn.yml"), plugin.getSLF4JLogger(), () -> "world_hub");
        spawnService.start();
        TravelConfig travelConfig = new TravelConfig("wild", new RuneConfig(10, 1800),
                new WaystoneConfig(1000L, 0.6, 300, 16, 3));
        waystoneService = new WaystoneService(plugin, waystoneRepository, new WaystoneCellPlanner(),
                new SimpleWaystoneStructurePlacer(), spawnService, () -> travelConfig);
        waystoneService.start();

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        questJournalService = new QuestJournalService(
                plugin, questEngine, questProgressEngine, variableRepository, customItemRegistry, economyService,
                new JournalConfig(true));
        questJournalService.start();

        YamlDestinationRegistry destinationRegistry =
                new YamlDestinationRegistry(tempDir.resolve("destinations"), plugin.getSLF4JLogger());
        destinationRegistry.start();
        portalService = new PortalService(plugin, portalRegistry, destinationRegistry, economyService,
                questProgressEngine, portalCooldownRepository);
        portalService.start();

        itemTravelService = new ItemTravelService(
                plugin, customItemRegistry, itemTravelCooldownRepository, plugin.getSLF4JLogger());
        itemTravelService.start();

        starterToolKitService = new StarterToolKitService(plugin, variableRepository,
                () -> new StarterToolKitConfig(true, List.of(new StarterKitTier(
                        1, "Nouveau venu", List.of(Material.WOODEN_PICKAXE), null))));
        resetService = new PlayerResetService(plugin, questProgressEngine, storyService, waystoneService, claimService,
                progressionService, questJournalService, portalService, itemTravelService, variableRepository,
                progressionRepository, portalCooldownRepository, itemTravelCooldownRepository, customItemRegistry,
                starterToolKitService);
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

    /** La complétion de {@code reset} passe par le thread principal : il faut ticker le scheduler. */
    private PlayerResetService.ResetSummary runReset(UUID uuid, String name) throws Exception {
        return runReset(uuid, name, PlayerResetService.ResetScope.PROGRESSION);
    }

    private PlayerResetService.ResetSummary runReset(UUID uuid, String name,
                                                      PlayerResetService.ResetScope scope) throws Exception {
        CompletableFuture<PlayerResetService.ResetSummary> future = resetService.reset(uuid, name, scope);
        await(future::isDone);
        return future.get();
    }

    /** Comme {@link #runReset}, {@code previewReset} se termine sur le thread principal. */
    private PlayerResetService.ResetPreview runPreview(UUID uuid) throws Exception {
        return runPreview(uuid, PlayerResetService.ResetScope.PROGRESSION);
    }

    private PlayerResetService.ResetPreview runPreview(UUID uuid,
                                                        PlayerResetService.ResetScope scope) throws Exception {
        CompletableFuture<PlayerResetService.ResetPreview> future = resetService.previewReset(uuid, scope);
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

    // ================================================================================
    //  Issue #235 — deux portées, et la différence est l'inventaire
    // ================================================================================

    /**
     * La cause exacte du double kit observé en jeu, figée en test.
     *
     * <p>Un reset de progression rouvre le droit au kit <strong>et</strong> laisse les outils déjà
     * reçus en place : le joueur peut donc en demander un second. Ce comportement est voulu pour
     * cette portée — on ne touche pas aux affaires du joueur — mais il doit être <em>annoncé</em>,
     * et c'est ce que vérifie l'aperçu plus bas.</p>
     */
    @Test
    void aProgressionResetReopensTheKitRightWhileLeavingTheKitItemsInPlace() throws Exception {
        PlayerMock player = server.addPlayer();
        seedFullState(player.getUniqueId(), player.getName());
        // Kit déjà reçu : droit consommé, et les outils VANILLA du kit dans l'inventaire.
        variableRepository.set(player.getUniqueId(), StarterToolKitService.AVAILABLE_KEY, "false")
                .get(TIMEOUT, TimeUnit.SECONDS);
        player.getInventory().addItem(new ItemStack(Material.WOODEN_PICKAXE, 1));
        player.getInventory().addItem(new ItemStack(Material.WOODEN_SWORD, 1));

        runReset(player.getUniqueId(), player.getName(), PlayerResetService.ResetScope.PROGRESSION);

        assertTrue(variableRepository.get(player.getUniqueId(), StarterToolKitService.AVAILABLE_KEY)
                        .get(TIMEOUT, TimeUnit.SECONDS).isEmpty(),
                "le droit au kit doit être rétabli (variable effacée = disponible)");
        assertTrue(java.util.Arrays.stream(player.getInventory().getContents())
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(stack -> stack.getType() == Material.WOODEN_PICKAXE),
                "les outils du kit sont du VANILLA pur : cette portée ne les retire pas");
    }

    /**
     * Le reset complet, lui, produit un état réellement neuf.
     *
     * <p>Y compris l'armure, la main secondaire et le coffre de l'Ender : y laisser du matériel
     * rendrait « nouveau joueur » faux, et c'est l'incohérence que ce ticket corrige.</p>
     */
    @Test
    void aFullResetEmptiesInventoryArmourOffhandAndEnderChest() throws Exception {
        PlayerMock player = server.addPlayer();
        seedFullState(player.getUniqueId(), player.getName());
        player.getInventory().addItem(new ItemStack(Material.WOODEN_PICKAXE, 1));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        player.getInventory().setHelmet(new ItemStack(Material.LEATHER_HELMET, 1));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD, 1));
        player.getEnderChest().addItem(new ItemStack(Material.GOLD_INGOT, 3));

        PlayerResetService.ResetSummary summary =
                runReset(player.getUniqueId(), player.getName(), PlayerResetService.ResetScope.NEW_PLAYER);

        assertEquals(PlayerResetService.ResetScope.NEW_PLAYER, summary.scope());
        assertTrue(summary.inventoryItemsRemoved() >= 11,
                "1 pioche + 5 diamants + casque + bouclier + 3 lingots, trouvés : "
                        + summary.inventoryItemsRemoved());
        assertTrue(java.util.Arrays.stream(player.getInventory().getContents())
                        .allMatch(stack -> stack == null || stack.getType().isAir()),
                "inventaire, armure et main secondaire doivent être vides");
        assertTrue(java.util.Arrays.stream(player.getEnderChest().getContents())
                        .allMatch(stack -> stack == null || stack.getType().isAir()),
                "le coffre de l'Ender doit être vidé");
    }

    /** Les deux portées effacent les mêmes données : seule l'intention sur l'inventaire diffère. */
    @Test
    void bothScopesResetTheKitTierToOneAndWipeTheSameData() throws Exception {
        for (PlayerResetService.ResetScope scope : PlayerResetService.ResetScope.values()) {
            PlayerMock player = server.addPlayer();
            seedFullState(player.getUniqueId(), player.getName());
            variableRepository.set(player.getUniqueId(), StarterToolKitService.TIER_KEY, "2")
                    .get(TIMEOUT, TimeUnit.SECONDS);

            runReset(player.getUniqueId(), player.getName(), scope);

            assertTrue(variableRepository.get(player.getUniqueId(), StarterToolKitService.TIER_KEY)
                            .get(TIMEOUT, TimeUnit.SECONDS).isEmpty(),
                    scope + " : le palier doit revenir au palier 1 (variable effacée)");
            assertEquals(1, starterToolKitService.unlockedTier(player.getUniqueId())
                            .get(TIMEOUT, TimeUnit.SECONDS),
                    scope + " : palier 1 attendu");
            assertTrue(dbStateGoneFor(player.getUniqueId()), scope + " : données RPGQuest effacées");
        }
    }

    /**
     * Le palier affiché par le Guide suit le reset.
     *
     * <p>Il est tenu en mémoire pour pouvoir être affiché sans requête SQL : sans invalidation, le
     * Guide annoncerait encore le palier d'avant le reset.</p>
     */
    @Test
    void aResetInvalidatesTheCachedTierUsedForDisplay() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT, TimeUnit.SECONDS);
        variableRepository.set(player.getUniqueId(), StarterToolKitService.TIER_KEY, "2")
                .get(TIMEOUT, TimeUnit.SECONDS);
        starterToolKitService.reloadForPlayer(player.getUniqueId());
        await(() -> starterToolKitService.cachedTier(player.getUniqueId()) == 2);

        runReset(player.getUniqueId(), player.getName(), PlayerResetService.ResetScope.NEW_PLAYER);

        await(() -> starterToolKitService.cachedTier(player.getUniqueId()) == 1);
        assertEquals(1, starterToolKitService.cachedTier(player.getUniqueId()));
    }

    /** Le marqueur différé porte la PORTÉE : un reset complet le reste à la reconnexion. */
    @Test
    void theDeferredMarkerCarriesTheScope() throws Exception {
        UUID uuid = UUID.randomUUID();
        seedFullState(uuid, "OfflineFull");

        runReset(uuid, "OfflineFull", PlayerResetService.ResetScope.NEW_PLAYER);

        assertEquals(PlayerResetService.ResetScope.NEW_PLAYER.name(),
                variableRepository.get(uuid, PlayerResetService.PENDING_INVENTORY_KEY)
                        .get(TIMEOUT, TimeUnit.SECONDS).orElseThrow());
    }

    /**
     * Un marqueur illisible — ou celui du format d'avant #235 — ne vide jamais un inventaire.
     *
     * <p>Une base contenant déjà {@code "1"} doit garder exactement l'ancien comportement : c'est la
     * seule lecture qui ne peut pas détruire par erreur.</p>
     */
    @Test
    void aLegacyOrUnreadableMarkerFallsBackToTheLeastDestructiveScope() {
        assertEquals(PlayerResetService.ResetScope.PROGRESSION,
                PlayerResetService.ResetScope.ofMarker("1"), "format d'avant #235");
        assertEquals(PlayerResetService.ResetScope.PROGRESSION,
                PlayerResetService.ResetScope.ofMarker("n'importe quoi"));
        assertEquals(PlayerResetService.ResetScope.PROGRESSION,
                PlayerResetService.ResetScope.ofMarker(null));
        assertEquals(PlayerResetService.ResetScope.NEW_PLAYER,
                PlayerResetService.ResetScope.ofMarker("new_player"), "insensible à la casse");
    }

    // ---- Ce que l'aperçu ANNONCE, qui est le vrai sujet du ticket -------------------------------

    /** L'aperçu de la portée « progression » dit que l'inventaire est conservé, et pourquoi. */
    @Test
    void theProgressionPreviewAnnouncesThatTheInventoryIsKept() throws Exception {
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.WOODEN_PICKAXE, 1));

        PlayerResetService.ResetPreview preview =
                runPreview(player.getUniqueId(), PlayerResetService.ResetScope.PROGRESSION);

        PlayerResetService.ResetCategory inventory = category(preview, "Inventaire (objets RPGQuest)");
        assertTrue(inventory.detail().contains("vanilla") || inventory.detail().contains("aucun objet"),
                "l'aperçu doit parler du sort de l'inventaire vanilla : " + inventory.detail());
        assertTrue(java.util.Arrays.stream(player.getInventory().getContents())
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(stack -> stack.getType() == Material.WOODEN_PICKAXE),
                "un aperçu ne retire jamais rien");
    }

    /** L'aperçu de la portée complète annonce le vidage, et compte TOUT. */
    @Test
    void theFullPreviewAnnouncesTheWipeAndCountsEverything() throws Exception {
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        player.getEnderChest().addItem(new ItemStack(Material.GOLD_INGOT, 2));

        PlayerResetService.ResetPreview preview =
                runPreview(player.getUniqueId(), PlayerResetService.ResetScope.NEW_PLAYER);

        PlayerResetService.ResetCategory inventory =
                category(preview, "Inventaire COMPLET (vanilla inclus)");
        // Au moins, et non exactement : le plugin complet tourne, et StarterKitListener remet une
        // Rune de départ au premier tick — ticker le scheduler fait donc varier le total. Ce qui
        // compte ici est que le VANILLA et l'Ender soient comptés, pas le chiffre exact.
        assertTrue(inventory.count() >= 7,
                "5 diamants + 2 lingots dans l'Ender doivent être comptés, trouvé : " + inventory.count());
        assertTrue(inventory.detail().contains("Ender"), inventory.detail());
        assertTrue(java.util.Arrays.stream(player.getInventory().getContents())
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(stack -> stack.getType() == Material.DIAMOND),
                "un aperçu ne retire jamais rien, même dans cette portée");
    }

    /**
     * Le comptage inclut bien le coffre de l'Ender — vérifié sans ticker le scheduler, donc sans la
     * remise différée de la Rune de départ qui rend tout total absolu instable.
     */
    @Test
    void countingEverythingIncludesTheEnderChest() {
        PlayerMock player = server.addPlayer();

        int before = PlayerResetService.countEverything(player);
        player.getEnderChest().addItem(new ItemStack(Material.GOLD_INGOT, 2));

        assertEquals(before + 2, PlayerResetService.countEverything(player));
    }

    /** L'aperçu nomme explicitement le droit au kit et le palier — les deux causes du doublon. */
    @Test
    void thePreviewNamesTheKitRightAndTheKitTier() throws Exception {
        UUID uuid = UUID.randomUUID();
        profileRepository.findOrCreate(uuid, "KitTester").get(TIMEOUT, TimeUnit.SECONDS);
        variableRepository.set(uuid, StarterToolKitService.AVAILABLE_KEY, "false")
                .get(TIMEOUT, TimeUnit.SECONDS);
        variableRepository.set(uuid, StarterToolKitService.TIER_KEY, "2").get(TIMEOUT, TimeUnit.SECONDS);

        PlayerResetService.ResetPreview preview =
                runPreview(uuid, PlayerResetService.ResetScope.PROGRESSION);

        PlayerResetService.ResetCategory right = category(preview, "Droit au kit de départ");
        assertEquals(1, right.count());
        assertTrue(right.detail().contains("RÉTABLI"), right.detail());
        assertTrue(right.detail().contains("resteront dans l'inventaire"),
                "l'avertissement du double kit doit figurer dans cette portée : " + right.detail());

        PlayerResetService.ResetCategory tier = category(preview, "Palier de kit");
        assertTrue(tier.detail().contains("palier 2"), tier.detail());
        assertTrue(tier.detail().contains("retour au palier 1"), tier.detail());
    }

    /** Dans la portée complète, l'avertissement du double kit n'a plus de raison d'être. */
    @Test
    void theFullPreviewDoesNotWarnAboutLeftoverKitItems() throws Exception {
        UUID uuid = UUID.randomUUID();
        profileRepository.findOrCreate(uuid, "FullTester").get(TIMEOUT, TimeUnit.SECONDS);
        variableRepository.set(uuid, StarterToolKitService.AVAILABLE_KEY, "false")
                .get(TIMEOUT, TimeUnit.SECONDS);

        PlayerResetService.ResetPreview preview =
                runPreview(uuid, PlayerResetService.ResetScope.NEW_PLAYER);

        assertFalse(category(preview, "Droit au kit de départ").detail()
                        .contains("resteront dans l'inventaire"),
                "cette portée vide l'inventaire : l'avertissement serait faux");
    }
}
