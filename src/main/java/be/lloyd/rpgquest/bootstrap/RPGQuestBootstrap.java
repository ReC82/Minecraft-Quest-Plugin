package be.lloyd.rpgquest.bootstrap;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.command.DialogueCommand;
import be.lloyd.rpgquest.command.QuestCommand;
import be.lloyd.rpgquest.command.QuestsCommand;
import be.lloyd.rpgquest.command.RPGQuestCommand;
import be.lloyd.rpgquest.config.ConfigService;
import be.lloyd.rpgquest.config.RendererKind;
import be.lloyd.rpgquest.database.DatabaseService;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.database.PlayerVariableRepository;
import be.lloyd.rpgquest.database.QuestProgressRepository;
import be.lloyd.rpgquest.dialogue.YamlDialogueEngine;
import be.lloyd.rpgquest.dialogue.render.ChatDialogueRenderer;
import be.lloyd.rpgquest.dialogue.render.DialogueRenderer;
import be.lloyd.rpgquest.dialogue.render.PaperDialogRenderer;
import be.lloyd.rpgquest.dialogue.session.DialogueSessionEngine;
import be.lloyd.rpgquest.player.PlayerConnectionListener;
import be.lloyd.rpgquest.player.PlayerListenerService;
import be.lloyd.rpgquest.player.PlayerProfileService;
import be.lloyd.rpgquest.quest.QuestMessagesService;
import be.lloyd.rpgquest.quest.YamlQuestEngine;
import be.lloyd.rpgquest.quest.progress.QuestProgressEngine;
import be.lloyd.rpgquest.ui.QuestJournalService;

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
    private PlayerProfileService playerProfileService;
    private QuestProgressEngine questProgressEngine;
    private YamlDialogueEngine dialogueEngine;
    private DialogueSessionEngine dialogueSessionEngine;
    private QuestJournalService questJournalService;

    public RPGQuestBootstrap(RPGQuestPlugin plugin) {
        this.plugin = plugin;
        this.registry = new PluginServiceRegistry(plugin.getSLF4JLogger());
        this.configService = new ConfigService(plugin);
        this.databaseService = new DatabaseService(
                plugin.getDataFolder().toPath(), configService, plugin.getSLF4JLogger());
        this.questEngine = new YamlQuestEngine(
                plugin.getDataFolder().toPath().resolve("quests"), plugin.getSLF4JLogger());
        this.questMessagesService = new QuestMessagesService(plugin);
    }

    public void start() {
        registry.start(configService);
        registry.start(databaseService);

        PlayerProfileRepository profileRepository = new PlayerProfileRepository(databaseService.databaseManager());
        playerProfileService = new PlayerProfileService(profileRepository);
        registry.start(new PlayerListenerService(
                plugin, new PlayerConnectionListener(plugin, playerProfileService)));

        registry.start(questEngine);
        registry.start(questMessagesService);

        QuestProgressRepository progressRepository = new QuestProgressRepository(databaseService.databaseManager());
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(databaseService.databaseManager());
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, questMessagesService);
        registry.start(questProgressEngine);
        registry.start(new PlayerListenerService(plugin, questProgressEngine.connectionListener()));

        dialogueEngine = new YamlDialogueEngine(
                plugin.getDataFolder().toPath().resolve("dialogues"), plugin.getSLF4JLogger(),
                configService.current().dialogue().allowedCommands());
        registry.start(dialogueEngine);

        dialogueSessionEngine = new DialogueSessionEngine(plugin, dialogueEngine, questProgressEngine, variableRepository);
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
    }
}
