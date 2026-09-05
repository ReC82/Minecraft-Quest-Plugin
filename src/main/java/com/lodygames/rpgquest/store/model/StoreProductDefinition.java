package com.lodygames.rpgquest.store.model;

import com.lodygames.rpgquest.backpack.model.BackpackSize;

/**
 * Ce qu'un identifiant de produit accorde en jeu — jamais son nom ni son
 * prix, qui n'existent que côté web-api (mission point 1, "catalogue de
 * produits séparé des avantages techniques"). {@code id} doit correspondre
 * exactement à l'id du produit défini dans {@code web-api/products.json}.
 */
public record StoreProductDefinition(
        String id,
        StoreGrantType grantType,
        BackpackSize backpackSize,
        String entitlementKey,
        String entitlementTier
) {
    public StoreProductDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id ne peut pas être vide.");
        }
        if (grantType == null) {
            throw new IllegalArgumentException("grant-type ne peut pas être nul.");
        }
        if (grantType == StoreGrantType.BACKPACK_SIZE && backpackSize == null) {
            throw new IllegalArgumentException("backpack-size est requis pour grant-type BACKPACK_SIZE.");
        }
        if (grantType == StoreGrantType.ENTITLEMENT
                && ((entitlementKey == null || entitlementKey.isBlank()) || (entitlementTier == null || entitlementTier.isBlank()))) {
            throw new IllegalArgumentException(
                    "entitlement-key et entitlement-tier sont requis pour grant-type ENTITLEMENT.");
        }
    }
}
