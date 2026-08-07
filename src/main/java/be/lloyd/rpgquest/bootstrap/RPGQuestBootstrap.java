package be.lloyd.rpgquest.bootstrap;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.admin.FlattenService;
import be.lloyd.rpgquest.admin.RpgAdminCommand;
import be.lloyd.rpgquest.backpack.BackpackService;
import be.lloyd.rpgquest.claim.ClaimProtectionListener;
import be.lloyd.rpgquest.claim.ClaimSelectionService;
import be.lloyd.rpgquest.claim.ClaimService;
import be.lloyd.rpgquest.claim.ClaimWandListener;
import be.lloyd.rpgquest.command.BackpackCommand;
import be.lloyd.rpgquest.command.ClaimCommand;
import be.lloyd.rpgquest.command.CustomItemCommand;
import be.lloyd.rpgquest.command.DialogueCommand;
import be.lloyd.rpgquest.command.MarketCommand;
import be.lloyd.rpgquest.command.MerchantCommand;
import be.lloyd.rpgquest.command.MoneyCommand;
import be.lloyd.rpgquest.command.ProfileCommand;
import be.lloyd.rpgquest.command.QuestCommand;
import be.lloyd.rpgquest.command.QuestsCommand;
import be.lloyd.rpgquest.command.ResourceNodeCommand;
import be.lloyd.rpgquest.command.RPGQuestCommand;
import be.lloyd.rpgquest.command.SkillsCommand;
import be.lloyd.rpgquest.config.ConfigService;
import be.lloyd.rpgquest.config.RendererKind;
import be.lloyd.rpgquest.crafting.RecipeCraftGuardListener;
import be.lloyd.rpgquest.crafting.YamlCraftingRegistry;
import be.lloyd.rpgquest.database.BackpackRepository;
import be.lloyd.rpgquest.database.DatabaseService;
import be.lloyd.rpgquest.database.EntitlementRepository;
import be.lloyd.rpgquest.database.PlacedBlockRepository;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.database.PlayerVariableRepository;
import be.lloyd.rpgquest.database.ClaimRepository;
import be.lloyd.rpgquest.database.MarketRepository;
import be.lloyd.rpgquest.database.PortalCooldownRepository;
import be.lloyd.rpgquest.database.ProgressionRepository;
import be.lloyd.rpgquest.database.QuestProgressRepository;
import be.lloyd.rpgquest.database.ResourceNodeRepository;
import be.lloyd.rpgquest.database.WalletRepository;
import be.lloyd.rpgquest.dialogue.YamlDialogueEngine;
import be.lloyd.rpgquest.dialogue.render.ChatDialogueRenderer;
import be.lloyd.rpgquest.dialogue.render.DialogueRenderer;
import be.lloyd.rpgquest.dialogue.render.PaperDialogRenderer;
import be.lloyd.rpgquest.dialogue.session.DialogueSessionEngine;
import be.lloyd.rpgquest.economy.EconomyService;
import be.lloyd.rpgquest.economy.market.MarketService;
import be.lloyd.rpgquest.economy.merchant.MerchantTradeService;
import be.lloyd.rpgquest.economy.merchant.YamlMerchantRegistry;
import be.lloyd.rpgquest.entitlement.EntitlementService;
import be.lloyd.rpgquest.item.SpiderFangDropListener;
import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import be.lloyd.rpgquest.item.behavior.EquipmentBehaviorService;
import be.lloyd.rpgquest.mob.SpecialMobRegistry;
import be.lloyd.rpgquest.mob.SpecialMobService;
import be.lloyd.rpgquest.mob.ability.ExplosiveOnAttackAbilityService;
import be.lloyd.rpgquest.mob.ability.SplitOnHitAbilityListener;
import be.lloyd.rpgquest.mob.ability.StrongerExplosionAbilityListener;
import be.lloyd.rpgquest.player.PlayerConnectionListener;
import be.lloyd.rpgquest.player.PlayerListenerService;
import be.lloyd.rpgquest.player.PlayerProfileService;
import be.lloyd.rpgquest.player.ResourcePackListener;
import be.lloyd.rpgquest.progression.PlacedBlockTracker;
import be.lloyd.rpgquest.progression.ProgressionService;
import be.lloyd.rpgquest.progression.listener.CombatXpListener;
import be.lloyd.rpgquest.progression.listener.ExplorationXpListener;
import be.lloyd.rpgquest.progression.listener.FarmingXpListener;
import be.lloyd.rpgquest.progression.listener.FishingXpListener;
import be.lloyd.rpgquest.progression.listener.MiningXpListener;
import be.lloyd.rpgquest.progression.listener.QuestCompletionXpListener;
import be.lloyd.rpgquest.quest.QuestMessagesService;
import be.lloyd.rpgquest.resource.ResourceNodeBreakListener;
import be.lloyd.rpgquest.resource.ResourceNodeRegistry;
import be.lloyd.rpgquest.resource.ResourceNodeService;
import be.lloyd.rpgquest.quest.YamlQuestEngine;
import be.lloyd.rpgquest.quest.progress.QuestProgressEngine;
import be.lloyd.rpgquest.travel.PortalService;
import be.lloyd.rpgquest.travel.YamlDestinationRegistry;
import be.lloyd.rpgquest.travel.YamlPortalRegistry;
import be.lloyd.rpgquest.ui.QuestJournalService;
import be.lloyd.rpgquest.web.WebSnapshotWriter;
import be.lloyd.rpgquest.zone.ZoneProtectionListener;
import be.lloyd.rpgquest.zone.ZoneRegistry;
import be.lloyd.rpgquest.zone.ZoneSelectionService;
import be.lloyd.rpgquest.zone.ZoneWandListener;

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
    private final ClaimSelectionService claimSelectionService;
    private final SpecialMobRegistry mobRegistry;
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
    private WebSnapshotWriter webSnapshotWriter;

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
        this.claimSelectionService = new ClaimSelectionService();
        this.mobRegistry = new SpecialMobRegistry(
                plugin.getDataFolder().toPath().resolve("mobs"), plugin.getSLF4JLogger());
    }

    public void start() {
        registry.start(configService);
        registry.start(databaseService);
        registry.start(flattenService);
        registry.start(zoneRegistry);
        registry.start(new PlayerListenerService(plugin, new ZoneProtectionListener(zoneRegistry)));
        registry.start(new PlayerListenerService(plugin, new ZoneWandListener(zoneSelectionService)));

        PlayerProfileRepository profileRepository = new PlayerProfileRepository(databaseService.databaseManager());
        playerProfileService = new PlayerProfileService(profileRepository);
        registry.start(new PlayerListenerService(
                plugin, new PlayerConnectionListener(plugin, playerProfileService)));
        registry.start(new PlayerListenerService(
                plugin, new ResourcePackListener(configService, plugin.getSLF4JLogger())));

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
                plugin, questEngine, progressRepository, variableRepository, questMessagesService);
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

        ClaimRepository claimRepository = new ClaimRepository(databaseService.databaseManager());
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService, progressionService);
        registry.start(claimService);
        registry.start(new PlayerListenerService(plugin, new ClaimProtectionListener(claimService)));
        registry.start(new PlayerListenerService(plugin, new ClaimWandListener(claimSelectionService)));

        dialogueEngine = new YamlDialogueEngine(
                plugin.getDataFolder().toPath().resolve("dialogues"), plugin.getSLF4JLogger(),
                configService.current().dialogue().allowedCommands());
        registry.start(dialogueEngine);

        dialogueSessionEngine = new DialogueSessionEngine(
                plugin, dialogueEngine, questProgressEngine, variableRepository, merchantTradeService);
        registry.start(dialogueSessionEngine);
        dialogueSessionEngine.setRenderer(createRenderer(dialogueSessionEngine));
        registry.start(new PlayerListenerService(plugin, dialogueSessionEngine.npcInteractListener()));

        questJournalService = new QuestJournalService(
                plugin, questEngine, questProgressEngine, variableRepository, configService.current().journal());
        registry.start(questJournalService);
        registry.start(new PlayerListenerService(plugin, questJournalService.listener()));

        registerCommands();
    }

    private DialogueRenderer createRenderer(DialogueSessionEngine handler) {
        return configService.current().dialogue().renderer() == RendererKind.PAPER_DIALOG
                ? new PaperDialogRenderer(handler)
                : new ChatDialogueRenderer(handler);
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

    public PortalService portalService() {
        return portalService;
    }

    public ClaimService claimService() {
        return claimService;
    }

    public WebSnapshotWriter webSnapshotWriter() {
        return webSnapshotWriter;
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

        ClaimCommand claimCommand = new ClaimCommand(plugin, claimService, claimSelectionService);
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

        RpgAdminCommand rpgAdminCommand = new RpgAdminCommand(
                flattenService, zoneRegistry, zoneSelectionService, portalRegistry, destinationRegistry,
                mobRegistry, mobService);
        var rpgadmin = plugin.getCommand("rpgadmin");
        if (rpgadmin != null) {
            rpgadmin.setExecutor(rpgAdminCommand);
            rpgadmin.setTabCompleter(rpgAdminCommand);
        }
    }
}
