package com.lodygames.rpgquest.story;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Toujours enregistré — même conception que {@code quest.progress.QuestProgressConnectionListener}
 * : le suivi de progression Story doit fonctionner quel que soit l'ensemble de stories chargé.
 */
final class StoryConnectionListener implements Listener {

    private final StoryService service;

    StoryConnectionListener(StoryService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.loadForPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.unloadForPlayer(event.getPlayer().getUniqueId());
    }
}
