package com.lodygames.rpgquest;

import com.lodygames.rpgquest.bootstrap.RPGQuestBootstrap;
import org.bukkit.plugin.java.JavaPlugin;

public class RPGQuestPlugin extends JavaPlugin {

    private RPGQuestBootstrap bootstrap;

    @Override
    public void onEnable() {
        bootstrap = new RPGQuestBootstrap(this);
        try {
            bootstrap.start();
            getSLF4JLogger().info("RPGQuest {} activé.", getPluginMeta().getVersion());
        } catch (RuntimeException e) {
            getSLF4JLogger().error("Démarrage de RPGQuest interrompu : configuration ou service invalide.", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
        getSLF4JLogger().info("RPGQuest désactivé.");
    }

    public RPGQuestBootstrap bootstrap() {
        return bootstrap;
    }
}
