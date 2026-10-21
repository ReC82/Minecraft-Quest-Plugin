package com.lodygames.rpgquest.quest.progress;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

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

    /**
     * Issue #636 : un joueur devant une fenêtre de dialogue ne se déplace pas — changer de bloc
     * (ou être téléporté) signale qu'elle est fermée, même par Échap, que le serveur ne voit pas.
     * Un seul test de map par joueur concerné, aucun scan.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock() && engine.hasDialogueFocus(event.getPlayer().getUniqueId())) {
            engine.dialogueMaybeLeftByMove(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (engine.hasDialogueFocus(event.getPlayer().getUniqueId())) {
            engine.dialogueEnded(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.unloadForPlayer(event.getPlayer().getUniqueId());
    }
}
