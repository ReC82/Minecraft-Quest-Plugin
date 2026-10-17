package com.lodygames.rpgquest.storage;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marqueur du GUI d'un coffre personnel : porte son propriétaire et son {@link VaultId}, pour que
 * {@link PersonalVaultListener} sache quoi sauvegarder à la fermeture. Purement virtuel, jamais
 * adossé à un bloc : aucun hopper ni aucun autre joueur ne peut y accéder par construction.
 */
final class PersonalVaultHolder implements InventoryHolder {

    private final UUID owner;
    private final VaultId vaultId;
    private Inventory inventory;

    PersonalVaultHolder(UUID owner, VaultId vaultId) {
        this.owner = owner;
        this.vaultId = vaultId;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    UUID owner() {
        return owner;
    }

    VaultId vaultId() {
        return vaultId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
