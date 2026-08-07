package be.lloyd.rpgquest.item.model;

import java.util.List;

/**
 * Restrictions de fabrication portées par la définition. {@code craftable}
 * et {@code requiredPermissions} ne sont pour l'instant que des données
 * capturées et exposées (ex. par {@code /customitem inspect}) : aucun
 * système de recettes personnalisées n'existe encore pour les appliquer
 * (prévu pour une étape ultérieure — voir docs/ARCHITECTURE.md).
 */
public record CraftingRestrictions(boolean craftable, List<String> requiredPermissions) {

    public CraftingRestrictions {
        requiredPermissions = List.copyOf(requiredPermissions);
    }

    public static CraftingRestrictions notCraftable() {
        return new CraftingRestrictions(false, List.of());
    }
}
