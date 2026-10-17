package com.lodygames.rpgquest.storage;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Traduit fermeture de GUI et déconnexion en sauvegarde du coffre personnel concerné. */
final class PersonalVaultListener implements Listener {

    private final PersonalVaultService service;

    PersonalVaultListener(PersonalVaultService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PersonalVaultHolder) {
            service.handleClose(event.getView().getTopInventory(), event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer().getUniqueId());
    }
}
