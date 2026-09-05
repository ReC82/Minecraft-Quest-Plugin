package com.lodygames.rpgquest.waystone.model;

import java.time.Instant;

/**
 * Une Waystone (« Pierre de voyage ») générée dans le monde d'exploration (mission « Waystones
 * Wild »). {@code x}/{@code y}/{@code z} = position du bloc <strong>interactif</strong> (le sommet
 * de la structure) : c'est ce bloc précis qu'un clic droit doit viser pour découvrir/utiliser la
 * Waystone, jamais un matériau particulier. {@code cellX}/{@code cellZ} = cellule de génération
 * (une Waystone au plus par cellule) — {@code (world, cellX, cellZ)} est unique en base.
 *
 * <p>La persistance (position + monde) est déjà suffisante comme destination pour un futur
 * « Hub → Waystone découverte », sans nouvelle migration.</p>
 */
public record Waystone(String id, String world, int x, int y, int z, long cellX, long cellZ,
                        String name, Instant createdAt) {

    public boolean isInteractBlock(String worldName, int bx, int by, int bz) {
        return world.equals(worldName) && x == bx && y == by && z == bz;
    }
}
