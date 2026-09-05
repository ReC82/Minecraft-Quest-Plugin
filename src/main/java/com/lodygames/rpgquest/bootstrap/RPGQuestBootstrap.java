package com.lodygames.rpgquest.bootstrap;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.admin.FlattenService;
import com.lodygames.rpgquest.admin.RpgAdminCommand;
import com.lodygames.rpgquest.backpack.BackpackService;
import com.lodygames.rpgquest.claim.ClaimBorderEntryListener;
import com.lodygames.rpgquest.claim.ClaimBorderRenderer;
import com.lodygames.rpgquest.claim.ClaimNetherTravelListener;
import com.lodygames.rpgquest.claim.ClaimProtectionListener;
import com.lodygames.rpgquest.claim.ClaimSelectionService;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.claim.ClaimTeleportService;
import com.lodygames.rpgquest.claim.ClaimWandListener;
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
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.MarketRepository;
import com.lodygames.rpgquest.database.PortalCooldownRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.ResourceNodeRepository;
import com.lodygames.rpgquest.database.StoreDeliveryRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository;
import com.lodygames.rpgquest.database.WalletRepository;
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
import com.lodygames.rpgquest.hub.HubWorldProtectionListener;
import com.lodygames.rpgquest.hub.HubWorldRulesService;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.SoulboundItemService;
import com.lodygames.rpgquest.item.SpiderFangDropListener;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.behavior.EquipmentBehaviorService;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.ability.ExplosiveOnAttackAbilityService;
import com.lodygames.rpgquest.mob.ability.SplitOnHitAbilityListener;
import com.lodygames.rpgquest.mob.ability.StrongerExplosionAbilityListener;
import com.lodygames.rpgquest.mod.ModCompatService;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.player.PlayerConnectionListener;
import com.lodygames.rpgquest.player.PlayerListenerService;
import com.lodygames.rpgquest.player.PlayerProfileService;
import com.lodygames.rpgquest.player.NewPlayerResetJoinListener;
import com.lodygames.rpgquest.player.PlayerResetService;
import com.lodygames.rpgquest.player.ResourcePackListener;
import com.lodygames.rpgquest.player.StarterKitListener;
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
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.store.StoreClient;
import com.lodygames.rpgquest.store.StoreDeliveryService;
import com.lodygames.rpgquest.store.StoreProductRegistry;
import com.lodygames.rpgquest.story.StoryRegistry;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.travel.ItemTravelService;
import com.lodygames.rpgquest.travel.PortalService;
import com.lodygames.rpgquest.travel.WorldPortalRegistry;
import com.lodygames.rpgquest.travel.WorldPortalDebugService;
import com.lodygames.rpgquest.travel.WildEntryWarningService;
import com.lodygames.rpgquest.travel.WorldPortalTeleportListener;
import com.lodygames.rpgquest.travel.YamlDestinationRegistry;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.travel.model.ItemTravelDefinition;
import com.lodygames.rpgquest.ui.QuestJournalBookService;
import com.lodygames.rpgquest.ui.QuestJournalService;
import com.lodygames.rpgquest.database.WaystoneRepository;
import com.lodygames.rpgquest.waystone.SimpleWaystoneStructurePlacer;
import com.lodygames.rpgquest.waystone.WaystoneCellPlanner;
import com.lodygames.rpgquest.waystone.WaystoneService;
import com.lodygames.rpgquest.web.WebSnapshotWriter;
import com.lodygames.rpgquest.world.WorldService;
import com.lodygames.rpgquest.zone.ZoneProtectionListener;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import com.lodygames.rpgquest.zone.ZoneSelectionService;
import com.lodygames.rpgquest.zone.ZoneWandListener;
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
    private final QuestMessagesService questMessagesService;
    private final YamlCustomItemRegistry customItemRegistry;
    private final ResourceNodeRegistry resourceNodeRegistry;
    private final YamlCraftingRegistry craftingRegistry;
    private final FlattenService flattenService;
    private final ZoneRegistry zoneRegistry;
    private final ZoneSelectionService zoneSelectionService;
    private final YamlMerchantRegistry merchantRegistry;
    private final YamlPortalRegistry portalRegistry;
    private final YamlDestinationRegistry destinationRegistry;
    private final WorldPortalRegistry worldPortalRegistry;
    private WorldPortalDebugService worldPortalDebugService;
    private final StoryRegistry storyRegistry;
    private final ClaimSelectionService claimSelectionService;
    private final SpecialMobRegistry mobRegistry;
    private final StoreProductRegistry storeProductRegistry;
    private EquipmentBehaviorService equipmentBehaviorService;
    private PlayerProfileService playerProfileService;
    private QuestProgressEngine questProgressEngine;
    private YamlDialogueEngine dialogueEngine;
    private DialogueSessionEngine dialogueSessionEngine;
    private QuestJournalService questJournalService;
    private ResourceNodeService resourceNodeService;
    private SpecialMobService mobService;
    private ProgressionService progressionService;
    private EntitlementService entitlementService;
    private BackpackService backpackService;
    private EconomyService economyService;
    private MerchantTradeService merchantTradeService;
    private MarketRepository marketRepository;
    private MarketService marketService;
    private PortalService portalService;
    private ClaimService claimService;
    private ClaimTeleportService claimTeleportService;
    private ItemTravelService itemTravelService;
    private WaystoneService waystoneService;
    private PlayerResetService playerResetService;
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
        this.storeProductRegistry = new StoreProductRegistry(
                plugin.getDataFolder().toPath().resolve("store-products"), plugin.getSLF4JLogger());
        this.spawnService = new SpawnService(
                plugin, plugin.getDataFolder().toPath().resolve("spawn.yml"), plugin.getSLF4JLogger());
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
        registry.start(new PlayerListenerService(plugin, new HubWorldProtectionListener(() -> configService.current().hub())));

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
        mobService = new SpecialMobService(plugin, mobRegistry, zoneRegistry, customItemRegistry, plugin.getSLF4JLogger());
        registry.start(mobService);
        registry.start(new PlayerListenerService(plugin, new StrongerExplosionAbilityListener(mobService)));
        registry.start(new PlayerListenerService(plugin, new SplitOnHitAbilityListener(mobService)));
        registry.start(new ExplosiveOnAttackAbilityService(plugin, mobRegistry, mobService));

        equipmentBehaviorService = new EquipmentBehaviorService(plugin, customItemRegistry, configService);
        registry.start(equipmentBehaviorService);
        registry.start(new PlayerListenerService(plugin, equipmentBehaviorService.weaponListener()));
        registry.start(new PlayerListenerService(plugin, equipmentBehaviorService.toolListener()));
        registry.start(new PlayerListenerService(plugin, equipmentBehaviorService.cooldownCleanupListener()));

        QuestProgressRepository progressRepository = new QuestProgressRepository(databaseService.databaseManager());
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(databaseService.databaseManager());
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, questMessagesService, npcIdentityService);
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

        WalletRepository walletRepository = new WalletRepository(databaseService.databaseManager());
        economyService = new EconomyService(walletRepository);
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
        // Avertissement avant entrée dans le Wild sans Rune de rappel (mission « boucle joueur ») :
        // politique branchée sur le portail simple, jamais codée dedans.
        worldPortalTeleportListener.setEntryGuard(new WildEntryWarningService(
                plugin, customItemRegistry, () -> configService.current().travel().wildWorld(), worldPortalTeleportListener));
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
        itemTravelService.register(new ItemTravelDefinition(
                RpgItemKeys.RUNE_RAPPEL,
                configService.current().travel().rune().channelSeconds(),
                configService.current().travel().rune().cooldownSeconds(),
                spawnService::resolve,
                () -> Optional.of(configService.current().travel().wildWorld())));

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

        dialogueEngine = new YamlDialogueEngine(
                plugin.getDataFolder().toPath().resolve("dialogues"), plugin.getSLF4JLogger(),
                configService.current().dialogue().allowedCommands());
        registry.start(dialogueEngine);

        dialogueSessionEngine = new DialogueSessionEngine(
                plugin, dialogueEngine, questProgressEngine, variableRepository, merchantTradeService, npcIdentityService,
                claimService, customItemRegistry);
        registry.start(dialogueSessionEngine);
        dialogueSessionEngine.setRenderer(createRenderer(dialogueSessionEngine));
        registry.start(new PlayerListenerService(plugin, dialogueSessionEngine.npcInteractListener()));
        var citizensDialogueListener = dialogueSessionEngine.citizensNpcInteractListener();
        if (citizensDialogueListener != null) {
            registry.start(new PlayerListenerService(plugin, citizensDialogueListener));
        }

        questJournalService = new QuestJournalService(
                plugin, questEngine, questProgressEngine, variableRepository, configService.current().journal());
        registry.start(questJournalService);
        registry.start(new PlayerListenerService(plugin, questJournalService.listener()));

        QuestJournalBookService questJournalBookService = new QuestJournalBookService(
                plugin, customItemRegistry, questProgressEngine, questEngine, storyService);
        registry.start(questJournalBookService);
        registry.start(new PlayerListenerService(plugin, questJournalBookService.listener()));

        // Reset admin « nouveau joueur » (/rpgadmin player resetnew) : orchestre les resets déjà
        // existants (quêtes, stories, claims/CLAIM_TIER_1, découvertes de Waystones) + les
        // suppressions par joueur manquantes (variables, progression RPG, cooldowns persistants).
        playerResetService = new PlayerResetService(
                plugin, questProgressEngine, storyService, waystoneService, claimService, progressionService,
                questJournalService, portalService, itemTravelService, variableRepository, progressionRepository,
                portalCooldownRepository, itemTravelCooldownRepository, customItemRegistry);
        registry.start(new PlayerListenerService(plugin,
                new NewPlayerResetJoinListener(plugin, variableRepository, customItemRegistry)));

        registerCommands();
    }

    private DialogueRenderer createRenderer(DialogueSessionEngine handler) {
        ChatDialogueRenderer chat = new ChatDialogueRenderer(handler);
        if (configService.current().dialogue().renderer() == RendererKind.PAPER_DIALOG) {
            return new FallbackDialogueRenderer(new PaperDialogRenderer(handler), chat, plugin.getSLF4JLogger());
        }
        return chat;
    }

    public void stop() {
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
                worldPortalDebugService, storyService, waystoneService, playerResetService, plugin);
        var rpgadmin = plugin.getCommand("rpgadmin");
        if (rpgadmin != null) {
            rpgadmin.setExecutor(rpgAdminCommand);
            rpgadmin.setTabCompleter(rpgAdminCommand);
        }
    }
}
