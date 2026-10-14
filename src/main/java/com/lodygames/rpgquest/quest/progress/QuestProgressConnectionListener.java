package com.lodygames.rpgquest.quest.progress;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
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

    /**
     * Issue #318 : une quête en attente de place se termine dès que le joueur a pu en libérer —
     * après avoir réorganisé son inventaire ou jeté un objet. Le tick suivant voit l'inventaire
     * réellement à jour.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            engine.retryAwaitingTurnInsNextTick(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        engine.retryAwaitingTurnInsNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.unloadForPlayer(event.getPlayer().getUniqueId());
    }
}
