package com.lodygames.rpgquest.travel.model;

/**
 * Comment {@code travel.WorldPortalTeleportListener} choisit la position d'arrivée d'un {@link
 * WorldPortalDefinition} dans son monde destination. Volontairement une simple énumération, pas un
 * système de stratégies extensible par plugin : seules deux valeurs sont nécessaires aujourd'hui.
 */
public enum DestinationStrategy {
    /** Position d'arrivée = {@code World#getSpawnLocation()}, résolue à chaque activation. */
    WORLD_SPAWN,
    /**
     * Position aléatoire sûre autour du spawn du monde destination (voir {@code
     * travel.RandomSafeLocationFinder}) — repli automatique sur {@link #WORLD_SPAWN} si aucune
     * position sûre n'est trouvée.
     */
    RANDOM_SAFE
}
