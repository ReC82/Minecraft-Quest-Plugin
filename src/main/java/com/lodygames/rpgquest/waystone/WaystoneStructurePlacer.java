package com.lodygames.rpgquest.waystone;

import org.bukkit.World;

/**
 * Pose la structure physique d'une Waystone. Abstraction volontaire (mission « Waystones Wild » :
 * « abstraction permettant plus tard un .schem ») : une future implémentation pourra coller un
 * schematic à la place de {@link SimpleWaystoneStructurePlacer}. Aucun gameplay ne dépend du
 * matériau exact posé — l'interaction est décidée à partir de la position persistée du bloc
 * sommital, jamais de son type.
 *
 * @param world  monde cible
 * @param topX   position du bloc interactif (sommet)
 * @param topY   idem
 * @param topZ   idem
 */
@FunctionalInterface
public interface WaystoneStructurePlacer {

    void place(World world, int topX, int topY, int topZ);
}
