package com.lodygames.rpgquest.travel.beacon;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** Marqueur d'un inventaire du menu de voyage (issues #132/#150) — jamais un inventaire réel du monde. */
final class BeaconMenuHolder implements InventoryHolder {

    enum Kind { ROOT, WAYPOINTS, SEARCH, VILLAGES }

    private final Kind kind;
    private Inventory inventory;

    BeaconMenuHolder(Kind kind) {
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
