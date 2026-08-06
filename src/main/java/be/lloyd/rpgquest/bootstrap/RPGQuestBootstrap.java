package be.lloyd.rpgquest.bootstrap;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.command.QuestCommand;
import be.lloyd.rpgquest.command.RPGQuestCommand;
import be.lloyd.rpgquest.config.ConfigService;
import be.lloyd.rpgquest.database.DatabaseService;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.database.PlayerVariableRepository;
import be.lloyd.rpgquest.database.QuestProgressRepository;
import be.lloyd.rpgquest.player.PlayerConnectionListener;
import be.lloyd.rpgquest.player.PlayerListenerService;
import be.lloyd.rpgquest.player.PlayerProfileService;
import be.lloyd.rpgquest.quest.QuestMessagesService;
import be.lloyd.rpgquest.quest.YamlQuestEngine;
import be.lloyd.rpgquest.quest.progress.QuestProgressEngine;

/**
 * Construit les services du plugin et orchestre leur démarrage/arrêt dans un
 * ordre garanti : configuration d'abord (les autres services en dépendent),
 * puis base de données, puis les listeners qui exploitent les deux, puis le
 * moteur de définitions de quêtes, puis les messages, puis le moteur de
 * progression (qui a besoin des quêtes déjà chargées pour construire son
 * index dès son propre démarrage).
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

        registerCommands();
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
    }
}
