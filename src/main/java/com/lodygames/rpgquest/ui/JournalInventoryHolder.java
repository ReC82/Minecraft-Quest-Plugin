package com.lodygames.rpgquest.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marqueur des inventaires appartenant au journal de quêtes : c'est sur ce
 * type (via {@code inventory.getHolder()}) que le listener de clic/drag
 * reconnaît « ceci est un menu du plugin » et bloque tout déplacement
 * d'objet, quel que soit le type de clic. {@code inventory} est lié après
 * construction ({@link #bind}) car {@code Bukkit.createInventory(holder,
 * ...)} exige le holder avant que l'inventaire n'existe.
 */
final class JournalInventoryHolder implements InventoryHolder {

    private final JournalKind kind;
    private Inventory inventory;

    JournalInventoryHolder(JournalKind kind) {
        this.kind = kind;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    JournalKind kind() {
        return kind;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
