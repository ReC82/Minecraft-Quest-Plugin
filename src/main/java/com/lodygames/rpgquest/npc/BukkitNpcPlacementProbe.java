package com.lodygames.rpgquest.npc;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Traduit un monde Bukkit en {@link NpcPlacementPlanner.Probe}. Toute la décision reste dans le
 * planificateur pur ; cette classe ne fait que <strong>classer des blocs</strong>.
 *
 * <p><strong>Lecture seule stricte</strong> : aucun bloc n'est cassé ni posé, aucun monde n'est
 * chargé, et les coordonnées hors des bornes verticales du monde sont rendues
 * {@link NpcPlacementPlanner.BlockKind#UNKNOWN} plutôt que sondées — ce qui évite de forcer quoi que
 * ce soit en bord de monde.</p>
 *
 * <p><strong>À appeler sur le thread principal</strong> : lire un bloc est une opération monde.</p>
 */
public final class BukkitNpcPlacementProbe implements NpcPlacementPlanner.Probe {

    /** Blocs qui téléportent : jamais un emplacement de PNJ, même entourés d'air. */
    private static final Set<Material> PORTALS = Set.of(
            Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY,
            Material.END_PORTAL_FRAME);

    private final World world;
    private final java.util.Set<String> occupiedCells;

    /**
     * @param occupiedCells cases déjà occupées par un PNJ, au format {@code x:y:z} (coordonnées de
     *                      bloc). Pré-calculé par l'appelant depuis le registre Citizens, pour ne
     *                      pas interroger le registre à chaque case examinée.
     */
    public BukkitNpcPlacementProbe(World world, java.util.Set<String> occupiedCells) {
        this.world = world;
        this.occupiedCells = occupiedCells == null ? Set.of() : Set.copyOf(occupiedCells);
    }

    @Override
    public NpcPlacementPlanner.BlockKind kindAt(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return NpcPlacementPlanner.BlockKind.UNKNOWN;
        }
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        if (PORTALS.contains(type)) {
            return NpcPlacementPlanner.BlockKind.PORTAL;
        }
        if (block.isLiquid()) {
            return NpcPlacementPlanner.BlockKind.LIQUID;
        }
        if (type.isAir() || block.isPassable()) {
            return NpcPlacementPlanner.BlockKind.PASSABLE;
        }
        // Un bloc plein mais non « solide » au sens collision (ex. certaines plantes) ne porte pas
        // un PNJ : on exige une vraie surface.
        return type.isSolid() ? NpcPlacementPlanner.BlockKind.SOLID : NpcPlacementPlanner.BlockKind.PASSABLE;
    }

    @Override
    public boolean occupiedByNpc(int x, int y, int z) {
        return occupiedCells.contains(x + ":" + y + ":" + z);
    }

    /** Clé de case utilisée par {@link #occupiedByNpc} — même format des deux côtés. */
    public static String cellKey(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }
}
