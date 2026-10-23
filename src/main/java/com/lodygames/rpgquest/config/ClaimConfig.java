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
 *
 * <p>{@code offlineGrowth} : rattrapage de la croissance des plantations au rechargement d'un chunk
 * de claim (issue #776, voir {@link OfflineGrowthConfig}).</p>
 */
public record ClaimConfig(int maxWidth, int maxHeight, int maxClaimsPerPlayer, int portalBufferBlocks, String world,
                           boolean blockNetherTravel, int arrivalMinRadius, int arrivalMaxRadius,
                           int arrivalSpacing, OfflineGrowthConfig offlineGrowth) {

    /** Arrivée « CLAIM_HOME » par défaut (issue #73) : 64 à 2 000 blocs du spawn, 100 blocs de tout claim. */
    public static final int DEFAULT_ARRIVAL_MIN_RADIUS = 64;
    public static final int DEFAULT_ARRIVAL_MAX_RADIUS = 2000;
    public static final int DEFAULT_ARRIVAL_SPACING = 100;

    public ClaimConfig {
        offlineGrowth = offlineGrowth == null ? OfflineGrowthConfig.DEFAULTS : offlineGrowth;
    }

    /** Forme antérieure à #776 : rattrapage de croissance par défaut. */
    public ClaimConfig(int maxWidth, int maxHeight, int maxClaimsPerPlayer, int portalBufferBlocks, String world,
                       boolean blockNetherTravel, int arrivalMinRadius, int arrivalMaxRadius, int arrivalSpacing) {
        this(maxWidth, maxHeight, maxClaimsPerPlayer, portalBufferBlocks, world, blockNetherTravel,
                arrivalMinRadius, arrivalMaxRadius, arrivalSpacing, OfflineGrowthConfig.DEFAULTS);
    }

    /** Forme antérieure à #73 : paramètres d'arrivée par défaut. */
    public ClaimConfig(int maxWidth, int maxHeight, int maxClaimsPerPlayer, int portalBufferBlocks, String world,
                       boolean blockNetherTravel) {
        this(maxWidth, maxHeight, maxClaimsPerPlayer, portalBufferBlocks, world, blockNetherTravel,
                DEFAULT_ARRIVAL_MIN_RADIUS, DEFAULT_ARRIVAL_MAX_RADIUS, DEFAULT_ARRIVAL_SPACING);
    }
}
