package com.lodygames.rpgquest.waypoint;

import org.bukkit.World;

/**
 * Autorise ou non la pose d'un bloc de waypoint à une position donnée (issue #124 : « ne pas
 * détruire arbitrairement une construction joueur pour placer un waypoint »). Le branchement réel
 * (bootstrap) refuse toute position située dans un claim ; les tests fournissent une implémentation
 * triviale. Volontairement isolé pour que le package {@code waypoint} ne dépende pas de
 * {@code claim}.
 */
@FunctionalInterface
public interface WaypointPlacementGuard {

    /** {@code true} si le système a le droit de poser/modifier un bloc en (x, y, z) de ce monde. */
    boolean isBuildable(World world, int x, int y, int z);

    /** Garde permissive (aucune restriction) — utile pour les tests et comme repli. */
    WaypointPlacementGuard ALLOW_ALL = (world, x, y, z) -> true;
}
