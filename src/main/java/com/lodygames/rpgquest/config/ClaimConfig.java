package com.lodygames.rpgquest.config;

/**
 * {@code world} : nom du monde dédié aux claims « Acte de propriété » (voir
 * {@code claim.DeedClaimListener}/{@code claim.ClaimsWorldRulesListener}) — jamais codé en dur
 * ailleurs, même patron que {@link HubConfig#world()}. N'affecte pas {@code /claim create} à la
 * baguette, utilisable dans n'importe quel monde autre que le Hub.
 *
 * <p>{@code blockNetherTravel} : tant que le rôle futur du Nether n'est pas décidé, un portail
 * Nether activé depuis {@code world} est refusé par défaut (voir {@code claim.ClaimNetherTravelListener})
 * — simple bascule pour réautoriser plus tard sans changement de code.</p>
 */
public record ClaimConfig(int maxWidth, int maxHeight, int maxClaimsPerPlayer, int portalBufferBlocks, String world,
                           boolean blockNetherTravel) {
}
