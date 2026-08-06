package be.lloyd.rpgquest.bootstrap;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.command.RPGQuestCommand;
import be.lloyd.rpgquest.config.ConfigService;
import be.lloyd.rpgquest.database.DatabaseService;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.player.PlayerConnectionListener;
import be.lloyd.rpgquest.player.PlayerListenerService;
import be.lloyd.rpgquest.player.PlayerProfileService;

/**
 * Construit les services du plugin et orchestre leur démarrage/arrêt dans un
 * ordre garanti : configuration d'abord (les autres services en dépendent),
 * puis base de données, puis les listeners qui exploitent les deux.
 */
public final class RPGQuestBootstrap {

    private final RPGQuestPlugin plugin;
    private final PluginServiceRegistry registry;
    private final ConfigService configService;
    private final DatabaseService databaseService;
    private PlayerProfileService playerProfileService;

    public RPGQuestBootstrap(RPGQuestPlugin plugin) {
        this.plugin = plugin;
        this.registry = new PluginServiceRegistry(plugin.getSLF4JLogger());
        this.configService = new ConfigService(plugin);
        this.databaseService = new DatabaseService(
                plugin.getDataFolder().toPath(), configService, plugin.getSLF4JLogger());
    }

    public void start() {
        registry.start(configService);
        registry.start(databaseService);

        PlayerProfileRepository profileRepository = new PlayerProfileRepository(databaseService.databaseManager());
        playerProfileService = new PlayerProfileService(profileRepository);
        registry.start(new PlayerListenerService(
                plugin, new PlayerConnectionListener(plugin, playerProfileService)));

        registerCommand();
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

    private void registerCommand() {
        RPGQuestCommand command = new RPGQuestCommand(plugin, this);
        var rpgquest = plugin.getCommand("rpgquest");
        if (rpgquest != null) {
            rpgquest.setExecutor(command);
            rpgquest.setTabCompleter(command);
        }
    }
}
