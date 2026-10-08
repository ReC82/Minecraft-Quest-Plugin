package com.lodygames.rpgquest.bootstrap;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.admin.FlattenService;
import com.lodygames.rpgquest.admin.RpgAdminCommand;
import com.lodygames.rpgquest.backpack.BackpackService;
import com.lodygames.rpgquest.claim.ClaimBorderEntryListener;
import com.lodygames.rpgquest.claim.ClaimBorderRenderer;
import com.lodygames.rpgquest.claim.ClaimNetherTravelListener;
import com.lodygames.rpgquest.claim.ClaimProtectionListener;
import com.lodygames.rpgquest.claim.ClaimReturnService;
import com.lodygames.rpgquest.claim.ClaimSelectionService;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.claim.ClaimTeleportService;
import com.lodygames.rpgquest.claim.ClaimWandListener;
import com.lodygames.rpgquest.claim.ClaimWorldAccessGuard;
import com.lodygames.rpgquest.claim.ClaimWorldSafetyListener;
import com.lodygames.rpgquest.claim.ClaimsWorldRulesListener;
import com.lodygames.rpgquest.claim.DeedClaimListener;
import com.lodygames.rpgquest.command.BackpackCommand;
import com.lodygames.rpgquest.command.ClaimCommand;
import com.lodygames.rpgquest.command.CustomItemCommand;
import com.lodygames.rpgquest.command.DialogueCommand;
import com.lodygames.rpgquest.command.MarketCommand;
import com.lodygames.rpgquest.command.MerchantCommand;
import com.lodygames.rpgquest.command.MoneyCommand;
import com.lodygames.rpgquest.command.ProfileCommand;
import com.lodygames.rpgquest.command.QuestCommand;
import com.lodygames.rpgquest.command.QuestsCommand;
import com.lodygames.rpgquest.command.ResourceNodeCommand;
import com.lodygames.rpgquest.command.RPGQuestCommand;
import com.lodygames.rpgquest.command.SkillsCommand;
import com.lodygames.rpgquest.command.StoreCommand;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.content.reload.ContentReloadService;
import com.lodygames.rpgquest.config.RendererKind;
import com.lodygames.rpgquest.crafting.RecipeCraftGuardListener;
import com.lodygames.rpgquest.crafting.YamlCraftingRegistry;
import com.lodygames.rpgquest.database.BackpackRepository;
import com.lodygames.rpgquest.database.DatabaseService;
import com.lodygames.rpgquest.database.EntitlementRepository;
import com.lodygames.rpgquest.database.ItemTravelCooldownRepository;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlacedBlockRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.TravelBeaconRepository;
import com.lodygames.rpgquest.database.VillageCenterRepository;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.MarketRepository;
import com.lodygames.rpgquest.database.PortalCooldownRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.ResourceNodeRepository;
import com.lodygames.rpgquest.database.StoreDeliveryRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.dialogue.DialogueTextPlaceholders;
import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.dialogue.render.ChatDialogueRenderer;
import com.lodygames.rpgquest.dialogue.render.DialogueRenderer;
import com.lodygames.rpgquest.dialogue.render.FallbackDialogueRenderer;
import com.lodygames.rpgquest.dialogue.render.PaperDialogRenderer;
import com.lodygames.rpgquest.dialogue.session.DialogueSessionEngine;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.economy.market.MarketService;
import com.lodygames.rpgquest.economy.merchant.MerchantTradeService;
import com.lodygames.rpgquest.economy.merchant.YamlMerchantRegistry;
import com.lodygames.rpgquest.entitlement.EntitlementService;
import com.lodygames.rpgquest.hub.HubGuideRegistry;
import com.lodygames.rpgquest.hub.HubComfortService;
import com.lodygames.rpgquest.hub.HubRescueFallbackService;
import com.lodygames.rpgquest.hub.HubWorldProtectionListener;
import com.lodygames.rpgquest.hub.HubWorldRulesService;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.SoulboundItemService;
import com.lodygames.rpgquest.item.SpiderFangDropListener;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.behavior.EquipmentBehaviorService;
import com.lodygames.rpgquest.mob.MobSpawnSettingsStore;
import com.lodygames.rpgquest.mob.SpecialMobDefinitionStore;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.ability.EnragedAbilityService;
import com.lodygames.rpgquest.mob.ability.ExplosiveOnAttackAbilityService;
import com.lodygames.rpgquest.mob.ability.SplitOnHitAbilityListener;
import com.lodygames.rpgquest.mob.ability.StrongerExplosionAbilityListener;
import com.lodygames.rpgquest.mob.ability.SummonOnDamageAbilityListener;
import com.lodygames.rpgquest.mod.ModCompatService;
import com.lodygames.rpgquest.npc.NpcDefinitionStore;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.npc.QuestGiverStore;
import com.lodygames.rpgquest.npc.YamlNpcEngine;
import com.lodygames.rpgquest.ops.ConsoleTap;
import com.lodygames.rpgquest.ops.ServerLogBuffer;
import com.lodygames.rpgquest.ops.ServerOpsService;
import com.lodygames.rpgquest.player.PlayerConnectionListener;
import com.lodygames.rpgquest.player.PlayerListenerService;
import com.lodygames.rpgquest.player.PlayerProfileService;
import com.lodygames.rpgquest.player.NewPlayerResetJoinListener;
import com.lodygames.rpgquest.player.PlayerResetService;
import com.lodygames.rpgquest.player.ResourcePackListener;
import com.lodygames.rpgquest.player.StarterKitListener;
import com.lodygames.rpgquest.player.StarterToolKitService;
import com.lodygames.rpgquest.progression.PlacedBlockTracker;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.listener.CombatXpListener;
import com.lodygames.rpgquest.progression.listener.ExplorationXpListener;
import com.lodygames.rpgquest.progression.listener.FarmingXpListener;
import com.lodygames.rpgquest.progression.listener.FishingXpListener;
import com.lodygames.rpgquest.progression.listener.MiningXpListener;
import com.lodygames.rpgquest.progression.listener.QuestCompletionXpListener;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.resource.ResourceNodeBreakListener;
import com.lodygames.rpgquest.resource.ResourceNodeRegistry;
import com.lodygames.rpgquest.resource.ResourceNodeService;
import com.lodygames.rpgquest.spawn.SpawnService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.progress.DeliveryStatusText;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.store.StoreClient;
import com.lodygames.rpgquest.store.StoreDeliveryService;
import com.lodygames.rpgquest.store.StoreProductRegistry;
import com.lodygames.rpgquest.story.StoryRegistry;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.travel.ChatWildEntryPromptPresenter;
import com.lodygames.rpgquest.travel.CompositeWorldPortalEntryGuard;
import com.lodygames.rpgquest.travel.FallbackWildEntryPromptPresenter;
import com.lodygames.rpgquest.travel.ItemTravelService;
import com.lodygames.rpgquest.travel.PortalService;
import com.lodygames.rpgquest.travel.WorldPortalRegistry;
import com.lodygames.rpgquest.travel.WorldPortalDebugService;
import com.lodygames.rpgquest.travel.PaperDialogWildEntryPromptPresenter;
import com.lodygames.rpgquest.travel.WildConditionsService;
import com.lodygames.rpgquest.travel.WildEntryPromptPresenter;
import com.lodygames.rpgquest.travel.WildEntryWarningService;
import com.lodygames.rpgquest.travel.WorldPortalTeleportListener;
import com.lodygames.rpgquest.travel.YamlDestinationRegistry;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.travel.beacon.TravelBeaconService;
import com.lodygames.rpgquest.travel.model.ItemTravelDefinition;
import com.lodygames.rpgquest.ui.QuestJournalService;
import com.lodygames.rpgquest.database.WaypointRepository;
import com.lodygames.rpgquest.database.WaystoneRepository;
import com.lodygames.rpgquest.waypoint.WaypointGenerationPlanner;
import com.lodygames.rpgquest.waypoint.WaypointIdentityResolver;
import com.lodygames.rpgquest.waypoint.WaypointService;
import com.lodygames.rpgquest.waypoint.render.WaypointModelRegistry;
import com.lodygames.rpgquest.waypoint.render.WaypointModelV1;
import com.lodygames.rpgquest.waystone.SimpleWaystoneStructurePlacer;
import com.lodygames.rpgquest.waystone.WaystoneCellPlanner;
import com.lodygames.rpgquest.waystone.WaystoneService;
import com.lodygames.rpgquest.web.WebSnapshotWriter;
import com.lodygames.rpgquest.web.admin.BukkitHealthSource;
import com.lodygames.rpgquest.web.admin.HealthSource;
import com.lodygames.rpgquest.web.admin.WebAdminServer;
import com.lodygames.rpgquest.web.agent.AgentActionExecutor;
import com.lodygames.rpgquest.web.agent.BukkitAgentActions;
import com.lodygames.rpgquest.web.agent.AgentConfig;
import com.lodygames.rpgquest.web.agent.AgentConfigLoader;
import com.lodygames.rpgquest.web.agent.BukkitPlayerDirectory;
import com.lodygames.rpgquest.web.agent.HeartbeatPayload;
import com.lodygames.rpgquest.web.agent.PlugAdminAgent;
import com.lodygames.rpgquest.world.WorldService;
import com.lodygames.rpgquest.zone.ZoneProtectionListener;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import com.lodygames.rpgquest.zone.ZoneSelectionService;
import com.lodygames.rpgquest.zone.ZoneWandListener;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Construit les services du plugin et orchestre leur démarrage/arrêt dans un
 * ordre garanti : configuration d'abord (les autres services en dépendent),
 * puis base de données, puis les listeners qui exploitent les deux, puis le
 * moteur de définitions de quêtes, puis les messages, puis le moteur de
 * progression, puis les dialogues (qui référencent les quêtes/variables déjà
 * prêtes). {@code YamlDialogueEngine} n'est construit qu'à l'intérieur de
 * {@link #start()} (jamais dans le constructeur du bootstrap) car il a
 * besoin de la liste blanche de commandes de {@code config.yml}, disponible
 * seulement une fois {@code ConfigService} réellement démarré.
 */
public final class RPGQuestBootstrap {

    private final RPGQuestPlugin plugin;
    private final PluginServiceRegistry registry;
    private final ConfigService configService;
    private final DatabaseService databaseService;
    private final YamlQuestEngine questEngine;
    private final YamlNpcEngine npcEngine;
    private final QuestMessagesService questMessagesService;
    private final YamlCustomItemRegistry customItemRegistry;
    private final ResourceNodeRegistry resourceNodeRegistry;
    private final YamlCraftingRegistry craftingRegistry;
    private final FlattenService flattenService;
    private final ZoneRegistry zoneRegistry;
    private final ZoneSelectionService zoneSelectionService;
    /**
     * Issue #213 — emplacements de construction. Créé ici (et non dans {@code start()}) parce que
     * la commande d'administration et le listener de l'outil le partagent : un seul cache, une seule
     * base.
     */
    private com.lodygames.rpgquest.building.BuildingSiteService buildingSiteService;
    private final YamlMerchantRegistry merchantRegistry;
    private final YamlPortalRegistry portalRegistry;
    private final YamlDestinationRegistry destinationRegistry;
    private final WorldPortalRegistry worldPortalRegistry;
    private WorldPortalDebugService worldPortalDebugService;
    private final StoryRegistry storyRegistry;
    private final ClaimSelectionService claimSelectionService;
    private final SpecialMobRegistry mobRegistry;
    private final MobSpawnSettingsStore mobSpawnSettingsStore;
    private final SpecialMobDefinitionStore mobDefinitionStore;
    private final StoreProductRegistry storeProductRegistry;
    private EquipmentBehaviorService equipmentBehaviorService;
    private PlayerProfileService playerProfileService;
    private QuestProgressEngine questProgressEngine;
    private PlayerVariableRepository variableRepository;
    /** Kit de départ à paliers (issue #218) — partagé entre le dialogue du Guide et /rpgadmin kit. */
    private StarterToolKitService starterToolKitService;
    private YamlDialogueEngine dialogueEngine;
    private DialogueSessionEngine dialogueSessionEngine;
    private HubGuideRegistry hubGuideRegistry;
    private QuestJournalService questJournalService;
    private ResourceNodeService resourceNodeService;
    private SpecialMobService mobService;
    private ProgressionService progressionService;
    private EntitlementService entitlementService;
    private BackpackService backpackService;
    private EconomyService economyService;
    /** Issue #140 — exposé à l'agent pour la lecture du journal des transactions. */
    private WalletRepository walletRepository;
    private MerchantTradeService merchantTradeService;
    private MarketRepository marketRepository;
    private MarketService marketService;
    private PortalService portalService;
    private ClaimService claimService;
    private ClaimTeleportService claimTeleportService;
    private ItemTravelService itemTravelService;
    private WaystoneService waystoneService;
    private WaypointService waypointService;
    private TravelBeaconService travelBeaconService;
    /** Issue #191 : autorisation explicite et temporaire d'altérer waypoints/bornes (jamais via OP seul). */
    private final com.lodygames.rpgquest.travel.TravelMaintenanceMode travelMaintenanceMode =
            new com.lodygames.rpgquest.travel.TravelMaintenanceMode();
    private PlayerResetService playerResetService;
    /** Issue #131 — service central de rechargement du contenu dans le runtime. */
    private ContentReloadService contentReloadService;
    /** Issue #95 — tampon de console et son branchement Log4j (exploitation serveur). */
    private ServerLogBuffer serverLogBuffer;
    private ConsoleTap consoleTap;
    /** Motif d'indisponibilité de la capture de console, ou vide si elle fonctionne. */
    private java.util.Optional<String> consoleUnavailableReason = java.util.Optional.empty();
    private WebSnapshotWriter webSnapshotWriter;
    private StoreClient storeClient;
    private StoreDeliveryService storeDeliveryService;
    private ModCompatService modCompatService;
    private NpcIdentityService npcIdentityService;
    private StoryService storyService;
    private final SpawnService spawnService;
    private final WorldService worldService;

    public RPGQuestBootstrap(RPGQuestPlugin plugin) {
        this.plugin = plugin;
        this.registry = new PluginServiceRegistry(plugin.getSLF4JLogger());
        this.configService = new ConfigService(plugin);
        this.databaseService = new DatabaseService(
                plugin.getDataFolder().toPath(), configService, plugin.getSLF4JLogger());
        this.questEngine = new YamlQuestEngine(
                plugin.getDataFolder().toPath().resolve("quests"), plugin.getSLF4JLogger());
        this.npcEngine = new YamlNpcEngine(
                plugin.getDataFolder().toPath().resolve("npcs"), plugin.getSLF4JLogger());
        this.questMessagesService = new QuestMessagesService(plugin);
        this.customItemRegistry = new YamlCustomItemRegistry(
                plugin.getDataFolder().toPath().resolve("items"), plugin.getSLF4JLogger());
        this.resourceNodeRegistry = new ResourceNodeRegistry(
                plugin.getDataFolder().toPath().resolve("resource-nodes"), plugin.getSLF4JLogger());
        this.craftingRegistry = new YamlCraftingRegistry(
                plugin.getDataFolder().toPath().resolve("recipes"), customItemRegistry, plugin.getSLF4JLogger());
        this.flattenService = new FlattenService(plugin, configService, plugin.getSLF4JLogger());
        this.zoneRegistry = new ZoneRegistry(
                plugin.getDataFolder().toPath().resolve("zones"), plugin.getSLF4JLogger());
        this.zoneSelectionService = new ZoneSelectionService();
        this.merchantRegistry = new YamlMerchantRegistry(
                plugin.getDataFolder().toPath().resolve("merchants"), plugin.getSLF4JLogger());
        this.portalRegistry = new YamlPortalRegistry(
                plugin.getDataFolder().toPath().resolve("portals"), plugin.getSLF4JLogger());
        this.destinationRegistry = new YamlDestinationRegistry(
                plugin.getDataFolder().toPath().resolve("destinations"), plugin.getSLF4JLogger());
        this.worldPortalRegistry = new WorldPortalRegistry(
                plugin.getDataFolder().toPath().resolve("world-portals"), plugin.getSLF4JLogger());
        this.storyRegistry = new StoryRegistry(
                plugin.getDataFolder().toPath().resolve("stories"), plugin.getSLF4JLogger());
        this.claimSelectionService = new ClaimSelectionService();
        this.mobRegistry = new SpecialMobRegistry(
                plugin.getDataFolder().toPath().resolve("mobs"), plugin.getSLF4JLogger());
        this.mobSpawnSettingsStore = new MobSpawnSettingsStore(
                plugin.getDataFolder().toPath().resolve("mobs"), plugin.getSLF4JLogger());
        this.mobDefinitionStore = new SpecialMobDefinitionStore(
                plugin.getDataFolder().toPath().resolve("mobs"));
        this.storeProductRegistry = new StoreProductRegistry(
                plugin.getDataFolder().toPath().resolve("store-products"), plugin.getSLF4JLogger());
        this.spawnService = new SpawnService(
                plugin, plugin.getDataFolder().toPath().resolve("spawn.yml"), plugin.getSLF4JLogger(),
                () -> configService.current().hub().world());
        this.worldService = new WorldService(
                plugin, plugin.getDataFolder().toPath().resolve("worlds.yml"), plugin.getSLF4JLogger());
    }

    public void start() {
        registry.start(configService);
        registry.start(databaseService);
        npcIdentityService = new NpcIdentityService(plugin, new NpcIdRepository(databaseService.databaseManager()),
                new NpcBindingRepository(databaseService.databaseManager()));
        modCompatService = new ModCompatService(plugin, () -> configService.current().clientMod(), plugin.getSLF4JLogger());
        registry.start(modCompatService);
        registry.start(flattenService);
        registry.start(zoneRegistry);
        registry.start(new PlayerListenerService(plugin, new ZoneProtectionListener(zoneRegistry, npcIdentityService)));
        registry.start(new PlayerListenerService(plugin, new ZoneWandListener(zoneSelectionService)));

        // Issue #213 — emplacements de construction. La base est la source de vérité ; le cache est
        // chargé ici, une fois, et toute écriture repasse par le repository. Un échec de chargement
        // n'empêche PAS le serveur de démarrer : les emplacements sont un outil d'administration,
        // pas une mécanique de jeu, et un plugin qui refuse de démarrer pour ça serait pire.
        buildingSiteService = new com.lodygames.rpgquest.building.BuildingSiteService(
                new com.lodygames.rpgquest.database.BuildingSiteRepository(
                        databaseService.databaseManager()));
        buildingSiteService.load()
                .thenAccept(count -> plugin.getSLF4JLogger().info(
                        "{} emplacement(s) de construction chargé(s).", count))
                .exceptionally(error -> {
                    plugin.getSLF4JLogger().error(
                            "Chargement des emplacements de construction impossible : la page "
                                    + "« Bâtiments » restera vide jusqu'au prochain démarrage.", error);
                    return null;
                });
        registry.start(new PlayerListenerService(plugin,
                new com.lodygames.rpgquest.building.BuildingSiteToolListener(buildingSiteService)));

        PlayerProfileRepository profileRepository = new PlayerProfileRepository(databaseService.databaseManager());
        playerProfileService = new PlayerProfileService(profileRepository);
        registry.start(new PlayerListenerService(
                plugin, new PlayerConnectionListener(plugin, playerProfileService)));
        registry.start(new PlayerListenerService(
                plugin, new ResourcePackListener(configService, plugin.getSLF4JLogger())));

        registry.start(spawnService);
        registry.start(new PlayerListenerService(plugin, spawnService.listener()));
        registry.start(worldService);

        HubWorldRulesService hubWorldRulesService = new HubWorldRulesService(
                worldService, () -> configService.current().hub(), plugin.getSLF4JLogger());
        registry.start(hubWorldRulesService);
        registry.start(new PlayerListenerService(plugin, hubWorldRulesService.listener()));
        HubWorldProtectionListener hubWorldProtectionListener =
                new HubWorldProtectionListener(() -> configService.current().hub(), npcIdentityService);
        registry.start(new PlayerListenerService(plugin, hubWorldProtectionListener));
        // Nettoyage ciblé (issues #121/#155) des mobs indésirables déjà présents dans les chunks
        // déjà chargés au démarrage (ex. spawn) — jamais un chargement forcé de tout le monde.
        worldService.find(configService.current().hub().world())
                .ifPresent(hubWorldProtectionListener::sweepAlreadyLoaded);

        HubComfortService hubComfortService = new HubComfortService(
                plugin, () -> configService.current().hub(), plugin.getSLF4JLogger());
        registry.start(hubComfortService);
        registry.start(new PlayerListenerService(plugin, hubComfortService.listener()));

        // Filet de secours graphique du Hub (issue #154) : garantit la Rune de rappel accessible
        // même après un inventaire plein à la première connexion (voir StarterKitListener), et
        // propose un accès de secours purement graphique (sans objet) si elle ne peut toujours pas
        // être redonnée.
        HubRescueFallbackService hubRescueFallbackService = new HubRescueFallbackService(
                plugin, () -> configService.current().hub(), customItemRegistry, spawnService, plugin.getSLF4JLogger());
        registry.start(hubRescueFallbackService);
        registry.start(new PlayerListenerService(plugin, hubRescueFallbackService.listener()));

        registry.start(questEngine);
        registry.start(questMessagesService);
        registry.start(customItemRegistry);
        registry.start(new PlayerListenerService(plugin, new SpiderFangDropListener(customItemRegistry)));
        registry.start(craftingRegistry);
        registry.start(new PlayerListenerService(plugin, new RecipeCraftGuardListener(craftingRegistry, customItemRegistry)));
        registry.start(resourceNodeRegistry);

        ResourceNodeRepository resourceNodeRepository = new ResourceNodeRepository(databaseService.databaseManager());
        resourceNodeService = new ResourceNodeService(
                plugin, resourceNodeRegistry, resourceNodeRepository, customItemRegistry, plugin.getSLF4JLogger());
        registry.start(resourceNodeService);
        registry.start(new PlayerListenerService(plugin, new ResourceNodeBreakListener(resourceNodeService)));

        registry.start(mobRegistry);
        mobService = new SpecialMobService(
                plugin, mobRegistry, zoneRegistry, customItemRegistry, plugin.getSLF4JLogger(), mobSpawnSettingsStore);
        registry.start(mobService);
        registry.start(new PlayerListenerService(plugin, new StrongerExplosionAbilityListener(mobService)));
        registry.start(new PlayerListenerService(plugin, new SplitOnHitAbilityListener(mobService)));
        registry.start(new ExplosiveOnAttackAbilityService(plugin, mobRegistry, mobService));
        registry.start(new EnragedAbilityService(plugin, mobRegistry, mobService));
        registry.start(new PlayerListenerService(plugin, new SummonOnDamageAbilityListener(plugin, mobService)));

        equipmentBehaviorService = new EquipmentBehaviorService(plugin, customItemRegistry, configService);
        registry.start(equipmentBehaviorService);
        registry.start(new PlayerListenerService(plugin, equipmentBehaviorService.weaponListener()));
        registry.start(new PlayerListenerService(plugin, equipmentBehaviorService.toolListener()));
        registry.start(new PlayerListenerService(plugin, equipmentBehaviorService.cooldownCleanupListener()));

        QuestProgressRepository progressRepository = new QuestProgressRepository(databaseService.databaseManager());
        variableRepository = new PlayerVariableRepository(databaseService.databaseManager());
        // Portefeuille construit AVANT le moteur de quêtes, et non plus avec les marchands : depuis
        // l'issue #16, une quête peut payer une récompense monétaire, donc le moteur a besoin du
        // service économique. Ces deux lignes ne dépendent que du gestionnaire de base de données —
        // les remonter ici ne change donc aucun ordre d'initialisation réel.
        walletRepository = new WalletRepository(databaseService.databaseManager());
        economyService = new EconomyService(walletRepository);
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, questMessagesService, npcIdentityService,
                economyService);
        registry.start(questProgressEngine);
        registry.start(new PlayerListenerService(plugin, questProgressEngine.connectionListener()));

        ProgressionRepository progressionRepository = new ProgressionRepository(databaseService.databaseManager());
        progressionService = new ProgressionService(
                plugin, progressionRepository, () -> configService.current().progression(), plugin.getSLF4JLogger());
        registry.start(progressionService);

        webSnapshotWriter = new WebSnapshotWriter(
                plugin, plugin.getDataFolder().toPath(), progressionRepository, customItemRegistry,
                () -> configService.current().webExport(), plugin.getSLF4JLogger());
        registry.start(webSnapshotWriter);

        // Bridge d'administration HTTP (issue #37) — désactivé sauf RPGQUEST_WEB_ADMIN_ENABLED=true
        // + RPGQUEST_WEB_ADMIN_TOKEN (env, jamais config.yml). Seule voie d'intégration du Control Panel.
        HealthSource healthSource = new BukkitHealthSource(plugin, worldService, () -> configService.current());
        registry.start(new WebAdminServer(healthSource, plugin.getSLF4JLogger()));

        // Agent sortant PlugAdmin (issue #51) — RPGQuest/VeryGames initie une connexion HTTPS
        // SORTANTE vers PlugAdmin/AWS (heartbeat + file d'actions whitelistées). Fail-closed :
        // inerte tant que plugins/RPGQuest/plugadmin-agent.properties (hors Git) n'active pas
        // l'agent et ne fournit pas base-url + agent-id + token. Réutilise HealthSource (#37),
        // ne recalcule jamais le health.
        // NB : l'agent est démarré plus bas (après construction de storyService et
        // playerResetService), dont dépendent les actions métier whitelistées.
        AgentConfig agentConfig = new AgentConfigLoader(plugin.getDataFolder().toPath(), plugin.getSLF4JLogger()).load();

        PlacedBlockRepository placedBlockRepository = new PlacedBlockRepository(databaseService.databaseManager());
        PlacedBlockTracker placedBlockTracker = new PlacedBlockTracker(plugin, placedBlockRepository, plugin.getSLF4JLogger());
        registry.start(placedBlockTracker);

        registry.start(new PlayerListenerService(plugin, new CombatXpListener(
                plugin, progressionService, mobService, () -> configService.current().progression())));
        registry.start(new PlayerListenerService(plugin, new MiningXpListener(
                progressionService, placedBlockTracker, () -> configService.current().progression())));
        registry.start(new PlayerListenerService(plugin, new FarmingXpListener(
                progressionService, () -> configService.current().progression())));
        registry.start(new PlayerListenerService(plugin, new FishingXpListener(
                progressionService, () -> configService.current().progression())));
        registry.start(new PlayerListenerService(plugin, new ExplorationXpListener(
                progressionService, zoneRegistry, () -> configService.current().progression())));

        QuestCompletionXpListener questCompletionXpListener = new QuestCompletionXpListener(
                progressionService, questProgressEngine, () -> configService.current().progression(), plugin.getSLF4JLogger());
        questProgressEngine.onProgressChanged(questCompletionXpListener::onProgressChanged);

        EntitlementRepository entitlementRepository = new EntitlementRepository(databaseService.databaseManager());
        entitlementService = entitlementRepository;
        BackpackRepository backpackRepository = new BackpackRepository(databaseService.databaseManager());
        backpackService = new BackpackService(
                plugin, backpackRepository, entitlementService, () -> configService.current().backpacks(), plugin.getSLF4JLogger());
        registry.start(backpackService);

        registry.start(storeProductRegistry);
        StoreDeliveryRepository storeDeliveryRepository = new StoreDeliveryRepository(databaseService.databaseManager());
        storeClient = new StoreClient(() -> configService.current().store());
        storeDeliveryService = new StoreDeliveryService(
                plugin, storeClient, storeProductRegistry, storeDeliveryRepository, profileRepository,
                entitlementService, backpackService, () -> configService.current().store(),
                () -> configService.current().backpacks(), plugin.getSLF4JLogger());
        registry.start(storeDeliveryService);

        registry.start(merchantRegistry);
        merchantTradeService = new MerchantTradeService(
                plugin, merchantRegistry, economyService, customItemRegistry, questProgressEngine);
        registry.start(merchantTradeService);
        registry.start(new PlayerListenerService(plugin, merchantTradeService.listener()));

        marketRepository = new MarketRepository(databaseService.databaseManager());
        marketService = new MarketService(plugin, marketRepository, economyService);
        registry.start(marketService);
        registry.start(new PlayerListenerService(plugin, marketService.listener()));

        registry.start(portalRegistry);
        registry.start(destinationRegistry);
        PortalCooldownRepository portalCooldownRepository = new PortalCooldownRepository(databaseService.databaseManager());
        portalService = new PortalService(
                plugin, portalRegistry, destinationRegistry, economyService, questProgressEngine, portalCooldownRepository);
        registry.start(portalService);
        registry.start(new PlayerListenerService(plugin, portalService.listener()));

        registry.start(worldPortalRegistry);
        WorldPortalTeleportListener worldPortalTeleportListener = new WorldPortalTeleportListener(
                plugin, worldPortalRegistry, worldService,
                () -> configService.current().randomSafeArrival(), plugin.getSLF4JLogger());
        // Le garde d'entrée du portail simple est installé plus bas (setEntryGuard), une fois
        // claimService disponible : il compose l'avertissement d'entrée dans le Wild (« boucle
        // joueur ») ET le contrôle d'accès au monde des claims (issues #21/#22/#23).
        registry.start(new PlayerListenerService(plugin, worldPortalTeleportListener));
        worldPortalDebugService = new WorldPortalDebugService(plugin, worldPortalRegistry, plugin.getSLF4JLogger());
        registry.start(worldPortalDebugService);

        registry.start(storyRegistry);
        StoryProgressRepository storyProgressRepository = new StoryProgressRepository(databaseService.databaseManager());
        storyService = new StoryService(plugin, storyRegistry, storyProgressRepository, profileRepository,
                questProgressEngine, questEngine, questMessagesService, plugin.getSLF4JLogger());
        registry.start(storyService);
        registry.start(new PlayerListenerService(plugin, storyService.connectionListener()));
        // Branche la progression automatique de Story sur toute mutation de progression de quête —
        // même patron que QuestCompletionXpListener, mais directement une référence de méthode : pas
        // besoin d'une classe de listener séparée, StoryService a déjà tout l'état nécessaire.
        questProgressEngine.onProgressChanged(storyService::onQuestProgressChanged);

        ClaimRepository claimRepository = new ClaimRepository(databaseService.databaseManager());
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        registry.start(claimService);
        claimTeleportService = new ClaimTeleportService(plugin, claimService);
        registry.start(new PlayerListenerService(plugin, new ClaimProtectionListener(claimService)));
        registry.start(new PlayerListenerService(plugin, new ClaimWandListener(claimSelectionService)));
        ClaimsWorldRulesListener claimsWorldRulesListener =
                new ClaimsWorldRulesListener(plugin, claimService, () -> configService.current().claims());
        registry.start(new PlayerListenerService(plugin, claimsWorldRulesListener));
        // Le monde des claims est généralement déjà chargé à ce stade (les mondes se chargent avant
        // les plugins) : WorldLoadEvent ne se déclenchera donc jamais pour lui — purge explicite unique.
        claimsWorldRulesListener.purgeAlreadyLoadedWorld();
        registry.start(new PlayerListenerService(plugin,
                new ClaimNetherTravelListener(() -> configService.current().claims())));

        // Parcours Claims cohérent (issues #21/#22/#23) : accès au monde des claims réservé au
        // déblocage réel du premier terrain (CLAIM_TIER_1 / claim existant), composé avec
        // l'avertissement d'entrée dans le Wild — un seul garde côté WorldPortalTeleportListener.
        // Moyen de repartir du monde des claims (issue #22), partagé par le garde d'entrée et le
        // filet d'arrivée. La destination est EXACTEMENT celle de la Pierre de retour elle-même
        // (ItemTravelDefinition ci-dessous) : si elle ne se résout pas, l'objet ne mène nulle part
        // et l'entrée doit être refusée plutôt que de piéger le joueur.
        ClaimReturnService claimReturnService =
                new ClaimReturnService(plugin, customItemRegistry, spawnService::resolve);
        ClaimWorldAccessGuard claimWorldAccessGuard = new ClaimWorldAccessGuard(
                plugin, claimService, claimReturnService, () -> configService.current().claims(),
                worldPortalTeleportListener);
        // Avertissement de danger avant l'entrée dans le Wild (issue #161, qui couvre la partie B de
        // #26) : générique, sans aucune inspection d'inventaire, avec confirmation explicite et
        // option persistante « ne plus afficher ». Même choix de renderer que les dialogues
        // (config.yml → dialogue.renderer), avec repli chat automatique.
        WildEntryWarningService wildEntryWarningService = new WildEntryWarningService(
                plugin, variableRepository, () -> configService.current().travel().wildWorld(),
                worldPortalTeleportListener, createWildEntryPromptPresenter());
        registry.start(new PlayerListenerService(plugin, wildEntryWarningService));
        worldPortalTeleportListener.setEntryGuard(new CompositeWorldPortalEntryGuard(List.of(
                claimWorldAccessGuard,
                wildEntryWarningService)));
        // Filet de sécurité : personne ne reste coincé dans le monde des claims, et un retour Hub
        // sans commande y est toujours possible (Pierre de retour donnée si absente ; joueur non
        // éligible arrivé autrement que par le portail renvoyé au village).
        registry.start(new PlayerListenerService(plugin, new ClaimWorldSafetyListener(
                plugin, claimService, claimReturnService, () -> configService.current().claims(),
                () -> spawnService.resolve().or(() -> worldService.find(configService.current().hub().world())
                        .map(w -> w.getSpawnLocation())))));

        ClaimBorderRenderer claimBorderRenderer = new ClaimBorderRenderer(plugin);
        registry.start(claimBorderRenderer);
        registry.start(new PlayerListenerService(plugin, new ClaimBorderEntryListener(claimService, claimBorderRenderer)));
        registry.start(new PlayerListenerService(plugin,
                new DeedClaimListener(plugin, claimService, customItemRegistry, claimBorderRenderer, () -> configService.current().claims())));

        ItemTravelCooldownRepository itemTravelCooldownRepository =
                new ItemTravelCooldownRepository(databaseService.databaseManager());
        itemTravelService = new ItemTravelService(
                plugin, customItemRegistry, itemTravelCooldownRepository, plugin.getSLF4JLogger());
        registry.start(itemTravelService);
        registry.start(new PlayerListenerService(plugin, itemTravelService.listener()));
        itemTravelService.register(new ItemTravelDefinition(
                RpgItemKeys.PIERRE_RETOUR, 3, spawnService::resolve,
                () -> Optional.of(configService.current().claims().world())));
        // Rune de rappel (mission « boucle joueur ») : wild → Hub, canalisation + cooldown depuis
        // config.yml (travel.rune), lus au démarrage. Restreinte au monde d'exploration configuré.
        // Secours Hub (issue #154) : dans le Hub, le même objet téléporte en plus immédiatement et
        // gratuitement vers le spawn configuré -- jamais une sortie gratuite du Wild, uniquement un
        // second comportement propre au Hub, totalement indépendant de la restriction ci-dessus.
        itemTravelService.register(new ItemTravelDefinition(
                RpgItemKeys.RUNE_RAPPEL,
                configService.current().travel().rune().channelSeconds(),
                configService.current().travel().rune().cooldownSeconds(),
                spawnService::resolve,
                () -> Optional.of(configService.current().travel().wildWorld()),
                () -> Optional.of(configService.current().hub().world())));

        // Anti-perte générique (mission « système soulbound générique ») : un seul écouteur pour
        // tous les objets permanents du plugin, plutôt qu'un écouteur dédié recopié par objet.
        SoulboundItemService soulboundItemService = new SoulboundItemService(customItemRegistry);
        soulboundItemService.register(RpgItemKeys.ACTE_PROPRIETE);
        soulboundItemService.register(RpgItemKeys.PIERRE_RETOUR);
        soulboundItemService.register(RpgItemKeys.JOURNAL_QUETES);
        soulboundItemService.register(RpgItemKeys.RUNE_RAPPEL);
        registry.start(soulboundItemService);
        registry.start(new PlayerListenerService(plugin, soulboundItemService.listener()));

        // Kit de départ (mission « boucle joueur ») : une Rune de rappel remise une seule fois à
        // chaque joueur, à sa première connexion — marqueur persistant, jamais de duplication.
        registry.start(new PlayerListenerService(plugin,
                new StarterKitListener(plugin, variableRepository, customItemRegistry)));

        // Waystones (mission « Waystones Wild ») : génération paresseuse déterministe dans le monde
        // d'exploration, découverte individuelle par joueur, retour au Hub par canalisation courte.
        waystoneService = new WaystoneService(plugin,
                new WaystoneRepository(databaseService.databaseManager()),
                new WaystoneCellPlanner(), new SimpleWaystoneStructurePlacer(), spawnService,
                () -> configService.current().travel());
        registry.start(waystoneService);
        registry.start(new PlayerListenerService(plugin, waystoneService.listener()));

        // Waypoints par instance de biome (issue #124) : distincts des Waystones (réseau de voyage
        // sur grille) — génération paresseuse et unique quand un joueur entre dans une zone de biome
        // sans repère, découverte par clic explicite sur le bouton, rendu versionné, blocs protégés.
        // Bâti sur la même infra (DatabaseManager, PluginService, RandomSafeLocationFinder). La
        // garde de placement refuse toute position située dans un claim (jamais de destruction de
        // construction joueur pour poser un waypoint).
        WaypointModelRegistry waypointModelRegistry = new WaypointModelRegistry(
                configService.current().travel().waypoint().modelVersion(), new WaypointModelV1());
        waypointService = new WaypointService(plugin,
                new WaypointRepository(databaseService.databaseManager()),
                new WaypointIdentityResolver(), new WaypointGenerationPlanner(), waypointModelRegistry,
                (world, x, y, z) -> claimService.claimAt(world.getName(), x, y, z).isEmpty(),
                () -> configService.current().travel());
        registry.start(waypointService);

        // Issue #185 : l'objectif « découvrir N waypoints » se branche ICI, et nulle part ailleurs.
        // Le système de waypoints ne connaît pas le moteur de quêtes et réciproquement : il publie
        // ses PREMIÈRES découvertes et sait les compter, le moteur s'y abonne. Aucun waypoint n'est
        // jamais débloqué ni aucune découverte supprimée pour satisfaire une quête.
        waypointService.onFirstDiscovery((player, waypoint) ->
                questProgressEngine.handleWaypointDiscovered(player, waypoint.world()));
        questProgressEngine.setDiscoveredWaypointCounter(waypointService::discoveredCount);

        registry.start(new PlayerListenerService(plugin, waypointService.listener()));
        registry.start(new PlayerListenerService(plugin, waypointService.protectionListener(travelMaintenanceMode)));

        // Réseau de voyage (issues #132/#150) : borne physique (bouton bois + bloc diamant, même
        // support qu'un waypoint) ouvrant un menu graphique vers les waypoints déjà découverts par
        // le joueur. Strictement distinct de waypoint/waystone (aucune fusion d'identité/table) ;
        // aucune génération automatique ici (#149) — placement manuel via /rpgadmin travel beacon set.
        travelBeaconService = new TravelBeaconService(
                plugin, new TravelBeaconRepository(databaseService.databaseManager()), waypointService,
                claimService, new VillageCenterRepository(databaseService.databaseManager()),
                () -> configService.current().travel(), () -> configService.current().hub().world());
        registry.start(travelBeaconService);
        registry.start(new PlayerListenerService(plugin, travelBeaconService.listener()));
        registry.start(new PlayerListenerService(plugin, travelBeaconService.protectionListener(travelMaintenanceMode)));

        // Issue #168 : hostiles de jour comme de nuit dans les mondes Wild configurés, immunité au
        // soleil (jamais aux autres dégâts de feu) et araignées agressives en pleine lumière.
        // Strictement limité aux mondes Wild : Hub et Claims inchangés.
        registry.start(new com.lodygames.rpgquest.wild.WildHostileRulesService(plugin,
                () -> configService.current().wild(), () -> configService.current().travel().wildWorld()));

        dialogueEngine = new YamlDialogueEngine(
                plugin.getDataFolder().toPath().resolve("dialogues"), plugin.getSLF4JLogger(),
                configService.current().dialogue().allowedCommands());
        registry.start(dialogueEngine);
        registry.start(npcEngine);

        // Structure d'aide/orientation par Hub (issue #11, partie A) : mapping Hub → dialogue d'aide
        // + accueil/spécialité/orientations, en données (hub-guides/*.yml). Le contenu du menu d'aide
        // vit dans le dialogue référencé — voir docs/HUB_GUIDE.md.
        hubGuideRegistry = new HubGuideRegistry(
                plugin.getDataFolder().toPath().resolve("hub-guides"), plugin.getSLF4JLogger());
        registry.start(hubGuideRegistry);

        // Kit d'outils en bois (issue #26, partie A) : demandé explicitement au Guide, droit
        // renouvelé à chaque mort — distinct de StarterKitListener (Rune de rappel, remise unique).
        starterToolKitService = new StarterToolKitService(
                plugin, variableRepository, () -> configService.current().starterToolKit());
        registry.start(new PlayerListenerService(plugin, starterToolKitService));

        dialogueSessionEngine = new DialogueSessionEngine(
                plugin, dialogueEngine, questProgressEngine, variableRepository, merchantTradeService, npcIdentityService,
                claimService, customItemRegistry, starterToolKitService);
        registry.start(dialogueSessionEngine);
        dialogueSessionEngine.setRenderer(createRenderer(dialogueSessionEngine));
        // Issue #24 : le Garde peut renseigner l'état RÉEL du Wild (jour/nuit + météo globale) sur
        // demande du joueur, via %wild_conditions% dans dialogues/guard.yml. Lecture seule — ni
        // l'heure, ni la météo, ni le cycle jour/nuit du Wild ne sont modifiés.
        WildConditionsService wildConditionsService = new WildConditionsService(
                worldService::find, () -> configService.current().travel().wildWorld());
        // Issue #123 : %delivery_status% rend, pour le PNJ porteur du dialogue courant, ce qui a
        // déjà été remis et ce qui manque — lecture pure de la progression en mémoire.
        dialogueSessionEngine.setPlaceholders(new DialogueTextPlaceholders(Map.of(
                "wild_conditions", context -> wildConditionsService.describe(),
                "delivery_status", context -> DeliveryStatusText.render(questProgressEngine.pendingDeliveries(
                        context.player().getUniqueId(), context.npcId())))));
        registry.start(new PlayerListenerService(plugin, dialogueSessionEngine.npcInteractListener()));
        var citizensDialogueListener = dialogueSessionEngine.citizensNpcInteractListener();
        if (citizensDialogueListener != null) {
            registry.start(new PlayerListenerService(plugin, citizensDialogueListener));
        }

        // Issue #12 : signal visuel discret et PROPRE A CHAQUE JOUEUR au-dessus d'un PNJ, pour
        // une quete reellement disponible ou un dialogue accessible jamais lu. Branche ici parce
        // qu'il a besoin du moteur de dialogue (evaluation des conditions, hook de lecture) et du
        // moteur de progression (disponibilite reelle d'une quete) — aucune regle dupliquee.
        var dialogueReadRepository = new com.lodygames.rpgquest.database.DialogueReadRepository(
                databaseService.databaseManager());
        var npcHintService = new com.lodygames.rpgquest.npc.hint.NpcHintService(
                plugin, configService.current().npcHints(), npcIdentityService, questEngine,
                questProgressEngine, dialogueEngine, dialogueReadRepository,
                dialogueSessionEngine::reachableNodes, plugin.getSLF4JLogger());
        // Un noeud REELLEMENT affiche devient lu : seul endroit ou « lu » est ecrit.
        dialogueSessionEngine.setNodePresentedListener((player, dialogueId, nodeId) ->
                npcHintService.onNodePresented(player, dialogueId.toString(), nodeId));
        // Toute progression de quete peut changer la disponibilite : le signal suit sans attendre
        // l'expiration du cache.
        questProgressEngine.onProgressChanged(npcHintService::invalidate);
        registry.start(npcHintService);
        registry.start(new PlayerListenerService(plugin,
                new com.lodygames.rpgquest.npc.hint.NpcHintListener(npcHintService)));

        // Journal des quêtes : GUI paginée à deux onglets (en cours / terminées), ouverte par un
        // clic droit sur l'item rpgquest:journal_quetes (remis par le Libraire) ou par /quests.
        // Ne liste jamais les quêtes non découvertes (pas de catalogue) — voir docs/RPGQUEST_BIBLE.md.
        questJournalService = new QuestJournalService(
                plugin, questEngine, questProgressEngine, variableRepository, customItemRegistry,
                economyService, configService.current().journal());
        registry.start(questJournalService);
        registry.start(new PlayerListenerService(plugin, questJournalService.listener()));

        // Reset admin « nouveau joueur » (/rpgadmin player resetnew) : orchestre les resets déjà
        // existants (quêtes, stories, claims/CLAIM_TIER_1, découvertes de Waystones) + les
        // suppressions par joueur manquantes (variables, progression RPG, cooldowns persistants).
        playerResetService = new PlayerResetService(
                plugin, questProgressEngine, storyService, waystoneService, claimService, progressionService,
                questJournalService, portalService, itemTravelService, variableRepository, progressionRepository,
                portalCooldownRepository, itemTravelCooldownRepository, customItemRegistry);
        registry.start(new PlayerListenerService(plugin,
                new NewPlayerResetJoinListener(plugin, variableRepository, customItemRegistry)));

        // Rechargement du contenu dans le runtime (issue #131) : service CENTRAL, seul point qui
        // permute les ensembles actifs. Les six registres savaient déjà se recharger, mais rien ne
        // l'exposait — et la permission ACTION_CONTENT_RELOAD du panel n'avait aucune action
        // derrière elle. /rpgadmin et l'agent PlugAdmin en sont désormais de simples appelants.
        //
        // L'invalidateur de cache est passé ici plutôt que câblé dans le service : le service ne
        // doit rien savoir du signal visuel des PNJ (#12), seulement qu'un cache dérivé existe.
        contentReloadService = new ContentReloadService(
                questEngine, storyRegistry, dialogueEngine, npcEngine, customItemRegistry, mobRegistry,
                plugin.getSLF4JLogger(),
                families -> npcHintService.invalidateAll());

        // Exploitation serveur (issue #95) : tampon borné des lignes de console + annonce globale,
        // consommés par les actions agent « server.logs.tail » et « server.announce ».
        //
        // La capture passe par un appender Log4j2 et non par un Handler java.util.logging : Paper
        // route getSLF4JLogger() directement vers Log4j2, donc un Handler JUL ne verrait PAS nos
        // propres lignes. Si Log4j est absent ou incompatible, install() renvoie une raison lisible
        // et le plugin démarre NORMALEMENT — la console du panel s'affiche « indisponible » avec ce
        // motif, jamais un serveur en panne pour un confort d'administration.
        serverLogBuffer = new ServerLogBuffer(500);
        consoleTap = new ConsoleTap(serverLogBuffer);
        consoleUnavailableReason = consoleTap.install(plugin.getSLF4JLogger());
        ServerOpsService serverOpsService =
                new ServerOpsService(plugin, serverLogBuffer, () -> consoleUnavailableReason);

        // Agent sortant PlugAdmin (issue #51 + outillage Control Panel) — RPGQuest/VeryGames initie
        // une connexion HTTPS SORTANTE vers PlugAdmin/AWS (heartbeat + file d'actions whitelistées).
        // Fail-closed : inerte tant que plugins/RPGQuest/plugadmin-agent.properties (hors Git)
        // n'active pas l'agent (base-url + agent-id + token). Réutilise HealthSource (#37) pour le
        // heartbeat et les services métier existants pour les actions (jamais de commande texte).
        registry.start(new PlugAdminAgent(
                plugin, agentConfig, new HeartbeatPayload(healthSource),
                new AgentActionExecutor(new BukkitPlayerDirectory(plugin), variableRepository::get,
                        new BukkitAgentActions(plugin, questEngine, questProgressEngine, storyService,
                                customItemRegistry, playerResetService, variableRepository::set,
                                dialogueEngine, npcIdentityService,
                                new NpcBindingRepository(databaseService.databaseManager()),
                                new com.lodygames.rpgquest.database.NpcSkinSourceRepository(
                                        databaseService.databaseManager()),
                                npcEngine, new NpcDefinitionStore(npcEngine.directory()),
                                new QuestGiverStore(plugin.getDataFolder().toPath().resolve("quests")),
                                this::rpgWorldWhitelist,
                                new com.lodygames.rpgquest.dialogue.DialogueDefinitionStore(
                                        plugin.getDataFolder().toPath().resolve("dialogues"),
                                        configService.current().dialogue().allowedCommands()),
                                new com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor(
                                        plugin.getDataFolder().toPath().resolve("dialogues"),
                                        configService.current().dialogue().allowedCommands()),
                                waypointService, travelBeaconService, mobRegistry, mobService, mobDefinitionStore,
                                mobSpawnSettingsStore, () -> configService.current().travel().wildWorld(),
                                () -> configService.current().hub(),
                                serverOpsService, contentReloadService,
                                // Issue #210 — même source de position sûre que la Pierre de retour
                                // et le filet de sécurité des claims : jamais une coordonnée figée.
                                () -> spawnService.resolve().or(() -> worldService
                                        .find(configService.current().hub().world())
                                        .map(org.bukkit.World::getSpawnLocation)),
                                // Issue #140 — administration de la monnaie. Le portefeuille
                                // persistant reste l'unique source de vérité du solde.
                                economyService, walletRepository,
                                // Issue #213 — le MÊME service que l'outil en jeu : le panel et le
                                // clic lisent et écrivent le même cache et la même base.
                                buildingSiteService))));

        registerCommands();
    }

    /**
     * Liste blanche des mondes où l'action {@code npc.citizens.create} (#81 phase 2) peut faire
     * apparaître un PNJ : les trois mondes RPGQuest de la config (hub / claims / exploration). Pas
     * d'ACL géographique — le monde doit en plus être réellement chargé, vérifié côté agent.
     */
    private java.util.Set<String> rpgWorldWhitelist() {
        var cfg = configService.current();
        java.util.Set<String> worlds = new java.util.LinkedHashSet<>();
        worlds.add(cfg.hub().world());
        worlds.add(cfg.claims().world());
        worlds.add(cfg.travel().wildWorld());
        worlds.removeIf(w -> w == null || w.isBlank());
        return worlds;
    }

    private DialogueRenderer createRenderer(DialogueSessionEngine handler) {
        ChatDialogueRenderer chat = new ChatDialogueRenderer(handler);
        if (configService.current().dialogue().renderer() == RendererKind.PAPER_DIALOG) {
            return new FallbackDialogueRenderer(new PaperDialogRenderer(handler), chat, plugin.getSLF4JLogger());
        }
        return chat;
    }

    /**
     * Même règle que {@link #createRenderer} pour l'avertissement d'entrée dans le Wild (issue
     * #161) : la préférence {@code dialogue.renderer} du serveur décide fenêtre Paper ou chat
     * cliquable, avec repli automatique — un serveur qui a volontairement choisi {@code chat} pour
     * éviter l'API expérimentale ne doit pas la voir réapparaître ici.
     */
    private WildEntryPromptPresenter createWildEntryPromptPresenter() {
        ChatWildEntryPromptPresenter chat = new ChatWildEntryPromptPresenter();
        if (configService.current().dialogue().renderer() == RendererKind.PAPER_DIALOG) {
            return new FallbackWildEntryPromptPresenter(
                    new PaperDialogWildEntryPromptPresenter(), chat, plugin.getSLF4JLogger());
        }
        return chat;
    }

    public void stop() {
        // Détacher l'appender AVANT d'arrêter les services : sinon les dernières lignes d'arrêt
        // alimenteraient un tampon que plus personne ne lira, et l'appender survivrait à un
        // /reload du plugin (fuite d'appender, lignes dupliquées au rechargement suivant).
        if (consoleTap != null) {
            consoleTap.uninstall();
            consoleTap = null;
        }
        registry.stopAll();
    }

    public ConfigService configService() {
        return configService;
    }

    public PlayerProfileService playerProfileService() {
        return playerProfileService;
    }

    public YamlQuestEngine questEngine() {
        return questEngine;
    }

    public QuestProgressEngine questProgressEngine() {
        return questProgressEngine;
    }

    public StoryService storyService() {
        return storyService;
    }

    public YamlDialogueEngine dialogueEngine() {
        return dialogueEngine;
    }

    public DialogueSessionEngine dialogueSessionEngine() {
        return dialogueSessionEngine;
    }

    public QuestJournalService questJournalService() {
        return questJournalService;
    }

    public YamlCustomItemRegistry customItemRegistry() {
        return customItemRegistry;
    }

    public EquipmentBehaviorService equipmentBehaviorService() {
        return equipmentBehaviorService;
    }

    public ResourceNodeRegistry resourceNodeRegistry() {
        return resourceNodeRegistry;
    }

    public YamlCraftingRegistry craftingRegistry() {
        return craftingRegistry;
    }

    public FlattenService flattenService() {
        return flattenService;
    }

    public ZoneRegistry zoneRegistry() {
        return zoneRegistry;
    }

    public ZoneSelectionService zoneSelectionService() {
        return zoneSelectionService;
    }

    public ResourceNodeService resourceNodeService() {
        return resourceNodeService;
    }

    public SpecialMobRegistry mobRegistry() {
        return mobRegistry;
    }

    public SpecialMobService mobService() {
        return mobService;
    }

    public MobSpawnSettingsStore mobSpawnSettingsStore() {
        return mobSpawnSettingsStore;
    }

    public SpecialMobDefinitionStore mobDefinitionStore() {
        return mobDefinitionStore;
    }

    public ProgressionService progressionService() {
        return progressionService;
    }

    public EntitlementService entitlementService() {
        return entitlementService;
    }

    public BackpackService backpackService() {
        return backpackService;
    }

    public YamlMerchantRegistry merchantRegistry() {
        return merchantRegistry;
    }

    public EconomyService economyService() {
        return economyService;
    }

    public MerchantTradeService merchantTradeService() {
        return merchantTradeService;
    }

    public MarketService marketService() {
        return marketService;
    }

    public YamlPortalRegistry portalRegistry() {
        return portalRegistry;
    }

    public YamlDestinationRegistry destinationRegistry() {
        return destinationRegistry;
    }

    public WorldPortalRegistry worldPortalRegistry() {
        return worldPortalRegistry;
    }

    public PortalService portalService() {
        return portalService;
    }

    public ClaimService claimService() {
        return claimService;
    }

    public WebSnapshotWriter webSnapshotWriter() {
        return webSnapshotWriter;
    }

    public StoreProductRegistry storeProductRegistry() {
        return storeProductRegistry;
    }

    public StoreDeliveryService storeDeliveryService() {
        return storeDeliveryService;
    }

    public ModCompatService modCompatService() {
        return modCompatService;
    }

    public SpawnService spawnService() {
        return spawnService;
    }

    public WaystoneService waystoneService() {
        return waystoneService;
    }

    public WaypointService waypointService() {
        return waypointService;
    }

    public WorldService worldService() {
        return worldService;
    }

    private void registerCommands() {
        RPGQuestCommand rpgquestCommand = new RPGQuestCommand(plugin, this);
        var rpgquest = plugin.getCommand("rpgquest");
        if (rpgquest != null) {
            rpgquest.setExecutor(rpgquestCommand);
            rpgquest.setTabCompleter(rpgquestCommand);
        }

        QuestCommand questCommand = new QuestCommand(plugin, questEngine, questProgressEngine, questMessagesService);
        var quest = plugin.getCommand("quest");
        if (quest != null) {
            quest.setExecutor(questCommand);
            quest.setTabCompleter(questCommand);
        }

        DialogueCommand dialogueCommand = new DialogueCommand(dialogueSessionEngine);
        var dialogue = plugin.getCommand("dialogue");
        if (dialogue != null) {
            dialogue.setExecutor(dialogueCommand);
            dialogue.setTabCompleter(dialogueCommand);
        }

        QuestsCommand questsCommand = new QuestsCommand(questJournalService);
        var quests = plugin.getCommand("quests");
        if (quests != null) {
            quests.setExecutor(questsCommand);
        }

        CustomItemCommand customItemCommand = new CustomItemCommand(customItemRegistry);
        var customitem = plugin.getCommand("customitem");
        if (customitem != null) {
            customitem.setExecutor(customItemCommand);
            customitem.setTabCompleter(customItemCommand);
        }

        ResourceNodeCommand resourceNodeCommand = new ResourceNodeCommand(resourceNodeRegistry, resourceNodeService);
        var resourcenode = plugin.getCommand("resourcenode");
        if (resourcenode != null) {
            resourcenode.setExecutor(resourceNodeCommand);
            resourcenode.setTabCompleter(resourceNodeCommand);
        }

        MoneyCommand moneyCommand = new MoneyCommand(plugin, economyService);
        var money = plugin.getCommand("money");
        if (money != null) {
            money.setExecutor(moneyCommand);
            money.setTabCompleter(moneyCommand);
        }

        MerchantCommand merchantCommand = new MerchantCommand(merchantRegistry);
        var merchant = plugin.getCommand("merchant");
        if (merchant != null) {
            merchant.setExecutor(merchantCommand);
            merchant.setTabCompleter(merchantCommand);
        }

        MarketCommand marketCommand = new MarketCommand(plugin, marketService, marketRepository);
        var market = plugin.getCommand("market");
        if (market != null) {
            market.setExecutor(marketCommand);
            market.setTabCompleter(marketCommand);
        }

        ClaimCommand claimCommand = new ClaimCommand(plugin, claimService, claimSelectionService, claimTeleportService);
        var claim = plugin.getCommand("claim");
        if (claim != null) {
            claim.setExecutor(claimCommand);
            claim.setTabCompleter(claimCommand);
        }

        ProfileCommand profileCommand = new ProfileCommand(progressionService);
        var profile = plugin.getCommand("profile");
        if (profile != null) {
            profile.setExecutor(profileCommand);
        }

        SkillsCommand skillsCommand = new SkillsCommand(progressionService);
        var skills = plugin.getCommand("skills");
        if (skills != null) {
            skills.setExecutor(skillsCommand);
            skills.setTabCompleter(skillsCommand);
        }

        BackpackCommand backpackCommand = new BackpackCommand(backpackService, entitlementService, plugin.getSLF4JLogger());
        var backpack = plugin.getCommand("backpack");
        if (backpack != null) {
            backpack.setExecutor(backpackCommand);
            backpack.setTabCompleter(backpackCommand);
        }

        StoreCommand storeCommand = new StoreCommand(storeClient, plugin.getSLF4JLogger());
        var store = plugin.getCommand("store");
        if (store != null) {
            store.setExecutor(storeCommand);
            store.setTabCompleter(storeCommand);
        }

        RpgAdminCommand rpgAdminCommand = new RpgAdminCommand(
                flattenService, zoneRegistry, zoneSelectionService, portalRegistry, destinationRegistry,
                mobRegistry, mobService, npcIdentityService, spawnService, worldService, worldPortalRegistry,
                worldPortalDebugService, storyService, waystoneService, playerResetService, hubGuideRegistry,
                questProgressEngine, questEngine, variableRepository, travelBeaconService, waypointService,
                travelMaintenanceMode, claimService, contentReloadService, starterToolKitService,
                buildingSiteService, plugin);
        var rpgadmin = plugin.getCommand("rpgadmin");
        if (rpgadmin != null) {
            rpgadmin.setExecutor(rpgAdminCommand);
            rpgadmin.setTabCompleter(rpgAdminCommand);
        }
    }
}
