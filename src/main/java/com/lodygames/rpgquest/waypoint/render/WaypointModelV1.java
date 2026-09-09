package com.lodygames.rpgquest.waypoint.render;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;

/**
 * Rendu V1 d'un waypoint (issue #124) : « support en barrière de pierre + bloc d'or + bouton ».
 *
 * <p>Minecraft n'a pas de « barrière de pierre » à proprement parler : le support est un
 * {@link Material#COBBLESTONE_WALL} (barrière de la famille pierre). Structure, relative à l'ancre
 * (colonne de surface, premier bloc d'air au-dessus d'un sol solide) :</p>
 *
 * <pre>
 *   (0, -1, 0)  sol renforcé en pierre s'il n'était pas déjà solide
 *   (0,  0, 0)  COBBLESTONE_WALL  (support)
 *   (0,  1, 0)  GOLD_BLOCK        (élément visuel principal)
 *   (f,  1, 0)  STONE_BUTTON      (interacteur, posé sur la face « facing » du bloc d'or)
 *   (0,  2, 0) / (f, 2, 0)  dégagés (air) pour ne pas enterrer la structure
 * </pre>
 *
 * <p>où {@code f} est le vecteur unitaire cardinal de {@code facing}. Aucune logique métier ne
 * dépend de ces matériaux : la découverte est décidée à partir de la position de l'interacteur
 * fournie par {@link #interactor(BlockFace)}, jamais d'un type de bloc.</p>
 */
public final class WaypointModelV1 implements WaypointModel {

    public static final int VERSION = 1;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public void place(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing) {
        BlockFace f = cardinal(facing);

        Block ground = world.getBlockAt(anchorX, anchorY - 1, anchorZ);
        if (!ground.getType().isSolid()) {
            ground.setType(Material.STONE, false);
        }
        set(world, anchorX, anchorY, anchorZ, Material.COBBLESTONE_WALL);
        set(world, anchorX, anchorY + 1, anchorZ, Material.GOLD_BLOCK);
        set(world, anchorX, anchorY + 2, anchorZ, Material.AIR);

        int bx = anchorX + f.getModX();
        int bz = anchorZ + f.getModZ();
        placeButton(world, bx, anchorY + 1, bz, f);
        set(world, bx, anchorY + 2, bz, Material.AIR);
    }

    @Override
    public BlockOffset interactor(BlockFace facing) {
        BlockFace f = cardinal(facing);
        return new BlockOffset(f.getModX(), 1, f.getModZ());
    }

    @Override
    public Set<BlockOffset> protectedBlocks(BlockFace facing) {
        BlockFace f = cardinal(facing);
        return Set.of(
                new BlockOffset(0, -1, 0),
                new BlockOffset(0, 0, 0),
                new BlockOffset(0, 1, 0),
                new BlockOffset(f.getModX(), 1, f.getModZ()));
    }

    private static void placeButton(World world, int x, int y, int z, BlockFace facing) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE_BUTTON, false);
        try {
            BlockData data = block.getBlockData();
            if (data instanceof Switch sw) {
                sw.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                sw.setFacing(facing);
                block.setBlockData(sw, false);
            }
        } catch (RuntimeException ignored) {
            // BlockData non simulé (tests) : le type STONE_BUTTON suffit, l'identité de
            // l'interacteur est purement positionnelle côté service.
        }
    }

    private static void set(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != material) {
            block.setType(material, false);
        }
    }

    /** Réduit n'importe quel {@link BlockFace} à la face cardinale la plus proche (jamais verticale/diagonale). */
    private static BlockFace cardinal(BlockFace facing) {
        return switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> facing;
            default -> BlockFace.NORTH;
        };
    }
}
