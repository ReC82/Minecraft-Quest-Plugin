package com.lodygames.rpgquest.claim.model;

/**
 * Palier de claim : taille du cuboïde réellement protégé/constructible ({@link #activeSize()},
 * toujours un carré côté horizontal) et taille du cuboïde réservé autour de lui ({@link
 * #reservationSize()}) pour permettre une extension future sans jamais risquer de collision avec un
 * claim voisin posé entre-temps — voir {@link Claim#overlapsReservation}.
 *
 * <p><strong>Issue #179</strong> : {@code TIER_2}..{@code TIER_5} implémentent désormais la montée
 * de palier réelle (voir {@code ClaimService#upgradeTier}), pas seulement le modèle. La réservation
 * reste volontairement <strong>constante à 100</strong> pour les cinq paliers — exactement la valeur
 * déjà réservée dès la création d'un claim {@code TIER_1} (jamais changée pour les claims déjà posés
 * avant cette issue). Comme chaque {@code activeSize} ≤ 100, toute montée de palier reste à
 * l'intérieur de l'espace déjà exclusivement réservé pour ce claim depuis sa création — aucune
 * collision avec un claim voisin n'est donc possible par construction, même si la vérification
 * explicite ({@link Claim#overlapsReservation}) reste faite par prudence.</p>
 */
public enum ClaimTier {

    TIER_1(5, 100),
    TIER_2(10, 100),
    TIER_3(20, 100),
    TIER_4(40, 100),
    TIER_5(80, 100);

    private final int activeSize;
    private final int reservationSize;

    ClaimTier(int activeSize, int reservationSize) {
        this.activeSize = activeSize;
        this.reservationSize = reservationSize;
    }

    /** Côté (en blocs) du carré réellement protégé/constructible, centré sur la cible. */
    public int activeSize() {
        return activeSize;
    }

    /** Côté (en blocs) du carré réservé, centré sur la même cible, toujours >= {@link #activeSize()}. */
    public int reservationSize() {
        return reservationSize;
    }

    /**
     * Décalage (négatif) vers la borne minimale d'un carré de {@code size} blocs de côté centré sur
     * un bloc entier — {@code minOffset(size, center)} associé à {@link #maxOffset} donne toujours un
     * intervalle de <strong>exactement</strong> {@code size} blocs, y compris pour une taille paire
     * (100) où le centrage ne peut pas être parfaitement symétrique (voir {@link #maxOffset}).
     */
    public static int minOffset(int size) {
        return -(size / 2);
    }

    /** Décalage (positif) vers la borne maximale — voir {@link #minOffset}. */
    public static int maxOffset(int size) {
        return (size - 1) / 2;
    }
}
