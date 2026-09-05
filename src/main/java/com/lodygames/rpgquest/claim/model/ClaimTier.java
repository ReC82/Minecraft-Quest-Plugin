package com.lodygames.rpgquest.claim.model;

/**
 * Palier de claim : taille du cuboïde réellement protégé/constructible ({@link #activeSize()},
 * toujours un carré côté horizontal) et taille du cuboïde réservé autour de lui ({@link
 * #reservationSize()}) pour permettre une extension future sans jamais risquer de collision avec un
 * claim voisin posé entre-temps — voir {@link Claim#overlapsReservation}.
 *
 * <p>Seul {@link #TIER_1} est réellement joignable aujourd'hui (mission « premier claim 5×5 »). Les
 * autres valeurs n'existent que pour que le modèle (cette énumération, {@link Claim}, {@code
 * ClaimRepository}) n'ait pas besoin d'être retouché quand un futur palier sera implémenté — aucune
 * logique d'amélioration/upgrade n'existe encore, volontairement (hors périmètre de cette étape).</p>
 */
public enum ClaimTier {

    TIER_1(5, 100);

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
