package com.lodygames.rpgquest.store.model;

/**
 * Ce qu'un produit de la boutique peut accorder — volontairement limité à
 * deux types, aucun autre (mission étape 22, point 13 : philosophie
 * pay-to-convenience, jamais d'avantage compétitif). Ni attribut, ni objet
 * offensif, ni bonus de vitesse/dégâts ne peut être défini par ce mécanisme,
 * par construction. Voir docs/STORE.md.
 */
public enum StoreGrantType {
    /** Taille de backpack (Small/Medium/Large) — {@link com.lodygames.rpgquest.backpack.model.BackpackSize}. */
    BACKPACK_SIZE,
    /** Avantage générique via {@link com.lodygames.rpgquest.entitlement.EntitlementService} (VIP, cosmétique...). */
    ENTITLEMENT
}
