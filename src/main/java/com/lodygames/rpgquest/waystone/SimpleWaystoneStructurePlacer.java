package com.lodygames.rpgquest.waystone;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Petite structure identifiable, posée en blocs vanilla (mission « Waystones Wild ») : un socle
 * 3×3 en pierre polie sombre, quatre montants d'angle éclairés, et au centre — un bloc plus haut —
 * une pierre-repère ({@link Material#LODESTONE}) : c'est <em>ce</em> bloc que le joueur vise au clic
 * droit, mais rien dans le code ne dépend de ce matériau (voir {@link WaystoneStructurePlacer}).
 *
 * <p>{@code topY} est la position de la pierre-repère : le socle est en {@code topY-1}, les montants
 * en {@code topY-1..topY}. La zone est aplanie/dégagée avant la pose pour que la structure reste
 * lisible même sur un terrain accidenté.</p>
 */
public final class SimpleWaystoneStructurePlacer implements WaystoneStructurePlacer {

    @Override
    public void place(World world, int topX, int topY, int topZ) {
        int baseY = topY - 1;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(world, topX + dx, baseY, topZ + dz, Material.POLISHED_DEEPSLATE);
                // Dégage l'espace au-dessus du socle (2 blocs) pour ne pas enterrer la structure.
                set(world, topX + dx, baseY + 1, topZ + dz, Material.AIR);
                set(world, topX + dx, baseY + 2, topZ + dz, Material.AIR);
            }
        }

        for (int[] corner : new int[][] {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}}) {
            int cx = topX + corner[0];
            int cz = topZ + corner[1];
            set(world, cx, baseY + 1, cz, Material.CHISELED_DEEPSLATE);
            set(world, cx, baseY + 2, cz, Material.SEA_LANTERN);
        }

        set(world, topX, baseY + 1, topZ, Material.LODESTONE);
    }

    private void set(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != material) {
            block.setType(material, false);
        }
    }
}
