package be.lloyd.rpgquest.economy.market;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marqueur de l'inventaire de vitrine du marché — même rôle que
 * {@code JournalInventoryHolder}/{@code MerchantShopInventoryHolder} :
 * {@link MarketListener} l'utilise pour reconnaître « ceci est un menu du
 * plugin » et bloquer tout déplacement d'objet.
 */
final class MarketInventoryHolder implements InventoryHolder {

    private Inventory inventory;

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
