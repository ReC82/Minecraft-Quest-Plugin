package com.lodygames.rpgquest.quest.progress;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Toujours enregistré (contrairement aux listeners par type d'objectif,
 * conditionnels) : le suivi de progression doit fonctionner quel que soit
 * l'ensemble de quêtes chargé.
 */
final class QuestProgressConnectionListener implements Listener {

    private final QuestProgressEngine engine;

    QuestProgressConnectionListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        engine.loadForPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.unloadForPlayer(event.getPlayer().getUniqueId());
    }
}
