package be.lloyd.rpgquest;

import be.lloyd.rpgquest.command.RPGQuestCommand;
import be.lloyd.rpgquest.database.DatabaseManager;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.player.PlayerConnectionListener;
import be.lloyd.rpgquest.player.PlayerProfileService;
import org.bukkit.plugin.java.JavaPlugin;

public class RPGQuestPlugin extends JavaPlugin {

    private DatabaseManager databaseManager;
    private PlayerProfileService playerProfileService;

    @Override
    public void onEnable() {
        databaseManager = new DatabaseManager(getDataFolder().toPath().resolve("data.db"));
        databaseManager.initialize().exceptionally(error -> {
            getSLF4JLogger().error("Impossible d'initialiser la base de données RPGQuest.", error);
            return null;
        });

        PlayerProfileRepository profileRepository = new PlayerProfileRepository(databaseManager);
        playerProfileService = new PlayerProfileService(profileRepository);

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, playerProfileService), this);

        RPGQuestCommand command = new RPGQuestCommand(this);
        var rpgquest = getCommand("rpgquest");
        if (rpgquest != null) {
            rpgquest.setExecutor(command);
            rpgquest.setTabCompleter(command);
        }

        getSLF4JLogger().info("RPGQuest {} activé.", getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        getSLF4JLogger().info("RPGQuest désactivé.");
    }

    public PlayerProfileService getPlayerProfileService() {
        return playerProfileService;
    }
}
