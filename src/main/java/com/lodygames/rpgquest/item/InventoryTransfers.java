package com.lodygames.rpgquest.item;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Lecture partagée d'un {@link InventoryClickEvent} : quel objet du joueur ce clic ferait-il entrer
 * dans l'inventaire du haut de la vue (coffre, sac, GUI…) ? Une seule source de vérité pour les
 * écouteurs qui refusent un objet à l'entrée d'un stockage ({@code backpack.BackpackListener},
 * {@link StorageLockedItemListener}) plutôt qu'un {@code switch} recopié — et donc un geste oublié —
 * par écouteur.
 */
public final class InventoryTransfers {

    private InventoryTransfers() {
    }

    /**
     * Objet que ce clic déposerait dans l'inventaire du haut, ou {@code null} si le clic n'y dépose
     * rien : clic normal / droit / swap curseur dans le haut, shift-click depuis le bas, touche
     * numérique et touche de main secondaire (F) sur une case du haut. Le retrait d'un objet du haut
     * n'est jamais un dépôt.
     */
    public static ItemStack incomingToTop(InventoryClickEvent event, Player player) {
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return null;
        }
        return switch (event.getAction()) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> clicked == top ? event.getCursor() : null;
            case MOVE_TO_OTHER_INVENTORY -> clicked != top ? event.getCurrentItem() : null;
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> clicked == top ? hotbarSource(event, player) : null;
            default -> null;
        };
    }

    /**
     * Blocs qui prennent (ou rendent) un objet sur un simple clic droit, sans ouvrir d'inventaire :
     * bibliothèque sculptée, pot décoré, étagères. Partagé par {@link StorageLockedItemListener} et
     * {@code hub.HubResourceLockListener} (#675).
     */
    public static boolean takesItemsOnClick(Material type) {
        return type == Material.CHISELED_BOOKSHELF
                || type == Material.DECORATED_POT
                || type.name().endsWith("_SHELF");
    }

    private static ItemStack hotbarSource(InventoryClickEvent event, Player player) {
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return player.getInventory().getItemInOffHand();
        }
        int button = event.getHotbarButton();
        return button >= 0 ? player.getInventory().getItem(button) : null;
    }
}
