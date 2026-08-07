package be.lloyd.rpgquest.item.behavior;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Efface les cooldowns d'un joueur à la déconnexion (rule 5 : nettoyer les cooldowns expirés/morts). */
final class EquipmentCooldownCleanupListener implements Listener {

    private final CooldownManager cooldowns;

    EquipmentCooldownCleanupListener(CooldownManager cooldowns) {
        this.cooldowns = cooldowns;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.clear(event.getPlayer().getUniqueId());
    }
}
