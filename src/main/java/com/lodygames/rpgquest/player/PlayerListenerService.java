package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/** Enregistre/désenregistre un {@link Listener} au démarrage/arrêt du plugin. */
public final class PlayerListenerService implements PluginService {

    private final RPGQuestPlugin plugin;
    private final Listener listener;

    public PlayerListenerService(RPGQuestPlugin plugin, Listener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(listener);
    }
}
