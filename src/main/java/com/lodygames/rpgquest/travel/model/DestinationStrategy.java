package com.lodygames.rpgquest.travel.model;

/**
 * Comment {@code travel.WorldPortalTeleportListener} choisit la position d'arrivée d'un {@link
 * WorldPortalDefinition} dans son monde destination. Volontairement une simple énumération, pas un
 * système de stratégies extensible par plugin.
 *
 * <p>Un JAR antérieur à #566 refuse le fichier d'un portail {@link #FIXED_POINT} au chargement
 * (valeur inconnue) : il ne l'envoie jamais ailleurs que prévu.</p>
 */
public enum DestinationStrategy {
    /** Position d'arrivée = {@code World#getSpawnLocation()}, résolue à chaque activation. */
    WORLD_SPAWN,
    /**
     * Position aléatoire sûre autour du spawn du monde destination (voir {@code
     * travel.RandomSafeLocationFinder}) — repli automatique sur {@link #WORLD_SPAWN} si aucune
     * position sûre n'est trouvée.
     */
    RANDOM_SAFE,
    /**
     * Monde des claims (issue #73) : le joueur qui possède un claim principal dans ce monde arrive
     * SUR son claim ; sinon, une position sûre aléatoire tenue à distance de tout claim existant —
     * les premières entrées sont réparties, et un joueur réinitialisé ne retombe ni sur l'ancien
     * emplacement ni sur le terrain d'un autre. Repli sur {@link #RANDOM_SAFE} puis sur le spawn.
     */
    CLAIM_HOME,
    /**
     * Issue #566 : position et regard enregistrés ({@code WorldPortalDefinition#fixedPoint}). Si le
     * point n'est pas sûr au passage, recherche locale bornée autour de lui ({@code
     * travel.ArrivalSafety}) ; sinon la téléportation est refusée — jamais de repli lointain.
     */
    FIXED_POINT
}
