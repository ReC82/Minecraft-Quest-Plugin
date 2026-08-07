package com.lodygames.rpgquest.backpack;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marqueur du GUI de backpack — même rôle que {@code
 * economy.market.MarketInventoryHolder}/{@code MerchantShopInventoryHolder}
 * : {@link BackpackListener} l'utilise pour reconnaître « ceci est un
 * backpack » et appliquer les protections anti-imbrication/anti-objet
 * interdit. Purement virtuel : jamais adossé à un bloc du monde, donc
 * aucun vecteur de vol par hopper/entonnoir n'existe par construction.
 */
final class BackpackInventoryHolder implements InventoryHolder {

    private Inventory inventory;

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
