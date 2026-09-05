package com.lodygames.rpgquest.claim.model;

/**
 * Seule permission réellement configurable d'un claim (mission étape 17) :
 * les autres protections (blocs, conteneurs, animaux, armor stands,
 * explosions, pistons traversant la frontière) sont fixes, jamais
 * désactivables par le propriétaire.
 */
public record ClaimFlags(boolean allowPublicRedstone) {

    public static ClaimFlags defaults() {
        return new ClaimFlags(false);
    }
}
