package be.lloyd.rpgquest.economy.merchant;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marqueur des inventaires de vitrine de marchand : même rôle que {@code
 * JournalInventoryHolder} — c'est sur ce type que {@link MerchantShopListener}
 * reconnaît « ceci est un menu du plugin » et bloque tout déplacement
 * d'objet, quel que soit le type de clic.
 */
final class MerchantShopInventoryHolder implements InventoryHolder {

    private Inventory inventory;

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
