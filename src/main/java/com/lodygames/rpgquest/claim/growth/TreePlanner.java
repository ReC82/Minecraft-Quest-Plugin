package com.lodygames.rpgquest.claim.growth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.block.BlockState;

/**
 * Issue #917 : génération <strong>à blanc</strong> d'un arbre — les blocs qu'il poserait, sans rien
 * modifier. Séparée du simulateur pour pouvoir être remplacée en test (MockBukkit ne génère pas
 * d'arbres).
 */
@FunctionalInterface
public interface TreePlanner {

    /**
     * Blocs (positions et états) que poserait un arbre {@code type} planté en {@code origin}.
     *
     * @return liste vide si le générateur vanilla refuse (place, sol, hauteur)
     */
    List<BlockState> plan(Location origin, TreeType type, Random random);

    /**
     * Générateur vanilla de Paper ({@code RegionAccessor#generateTree} avec prédicat) : Paper calcule
     * l'arbre dans une liste tampon puis n'applique que les blocs acceptés par le prédicat — qui les
     * refuse tous ici et se contente de les recueillir.
     */
    static TreePlanner paper() {
        return (origin, type, random) -> {
            List<BlockState> planned = new ArrayList<>();
            Predicate<BlockState> collect = state -> {
                planned.add(state);
                return false;
            };
            boolean placed = origin.getWorld().generateTree(origin, random, type, collect);
            return placed ? planned : List.of();
        };
    }
}
