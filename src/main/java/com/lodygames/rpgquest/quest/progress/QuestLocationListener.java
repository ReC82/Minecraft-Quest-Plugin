package com.lodygames.rpgquest.quest.progress;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * {@code PlayerMoveEvent} se déclenche à chaque tick de mouvement (y
 * compris un simple changement d'orientation) : on ignore tout déplacement
 * qui ne change pas de bloc avant même de consulter l'index, pour ne pas
 * faire de calcul de distance sur un événement aussi fréquent sans raison.
 */
final class QuestLocationListener implements Listener {

    private final QuestProgressEngine engine;

    QuestLocationListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        String world = to.getWorld() != null ? to.getWorld().getName() : null;
        if (world == null) {
            return;
        }
        engine.handleReachLocation(event.getPlayer(), world, to.getX(), to.getY(), to.getZ());
    }
}
