package be.lloyd.rpgquest;

import be.lloyd.rpgquest.command.RPGQuestCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class RPGQuestPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
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
        getSLF4JLogger().info("RPGQuest désactivé.");
    }
}
