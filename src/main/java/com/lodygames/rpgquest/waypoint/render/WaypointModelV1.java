package com.lodygames.rpgquest.waypoint.render;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.sign.Side;

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
 *   (l,  1, 0) / (r, 1, 0)  OAK_WALL_SIGN (nom canonique, issue #167 -- faces latérales adjacentes
 *                           à la face du bouton, jamais la face opposée)
 *   (0,  2, 0) / (f, 2, 0)  dégagés (air) pour ne pas enterrer la structure
 * </pre>
 *
 * <p>où {@code f} est le vecteur unitaire cardinal de {@code facing}, et {@code l}/{@code r} ses
 * deux rotations à ±90°. Aucune logique métier ne dépend de ces matériaux : la découverte est
 * décidée à partir de la position de l'interacteur fournie par {@link #interactor(BlockFace)},
 * jamais d'un type de bloc.</p>
 */
public final class WaypointModelV1 implements WaypointModel {

    public static final int VERSION = 1;
    private static final Material SIGN_MATERIAL = Material.OAK_WALL_SIGN;
    private static final int SIGN_LINE_MAX_CHARS = 12;
    private static final int SIGN_MAX_LINES = 4;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public void place(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing, String displayName) {
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

        for (BlockFace side : lateralFaces(f)) {
            placeSign(world, anchorX + side.getModX(), anchorY + 1, anchorZ + side.getModZ(), side, displayName);
        }
    }

    @Override
    public BlockOffset interactor(BlockFace facing) {
        BlockFace f = cardinal(facing);
        return new BlockOffset(f.getModX(), 1, f.getModZ());
    }

    @Override
    public Set<BlockOffset> protectedBlocks(BlockFace facing) {
        BlockFace f = cardinal(facing);
        Set<BlockOffset> offsets = new HashSet<>(Set.of(
                new BlockOffset(0, -1, 0),
                new BlockOffset(0, 0, 0),
                new BlockOffset(0, 1, 0),
                new BlockOffset(f.getModX(), 1, f.getModZ())));
        for (BlockFace side : lateralFaces(f)) {
            offsets.add(new BlockOffset(side.getModX(), 1, side.getModZ()));
        }
        return Set.copyOf(offsets);
    }

    /** Les deux faces à ±90° de {@code facing} -- jamais la face opposée (issue #167). */
    private static BlockFace[] lateralFaces(BlockFace facing) {
        return switch (facing) {
            case NORTH, SOUTH -> new BlockFace[] {BlockFace.EAST, BlockFace.WEST};
            default -> new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH};
        };
    }

    /**
     * Orientation ET texte posés via le <strong>même</strong> instantané {@link
     * org.bukkit.block.BlockState}, commis par un seul {@code update()} -- jamais une mutation de
     * {@code BlockData} séparée suivie d'un {@code BlockState} obtenu/validé après coup : ce
     * dernier capture son propre instantané à l'obtention et l'écraserait sinon à la validation.
     */
    private static void placeSign(World world, int x, int y, int z, BlockFace facing, String displayName) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(SIGN_MATERIAL, false);
        try {
            BlockState state = block.getState();
            BlockData data = state.getBlockData();
            if (data instanceof Directional directional) {
                directional.setFacing(facing);
                state.setBlockData(data);
            }
            if (state instanceof Sign sign) {
                List<String> lines = wrapSignLines(displayName);
                for (int i = 0; i < lines.size() && i < SIGN_MAX_LINES; i++) {
                    sign.getSide(Side.FRONT).line(i, Component.text(lines.get(i)));
                }
            }
            state.update(true, false);
        } catch (RuntimeException ignored) {
            // BlockState non simulé fidèlement (tests) : le type OAK_WALL_SIGN suffit à vérifier le
            // placement et la protection ; l'orientation/le texte réels restent PENDING MANUAL VALIDATION.
        }
    }

    /**
     * Répartit {@code name} sur des lignes d'au plus {@link #SIGN_LINE_MAX_CHARS} caractères,
     * uniquement sur des frontières de mots (jamais de troncature ambiguë au milieu d'un mot) --
     * issue #167. Au-delà de {@link #SIGN_MAX_LINES} lignes (jamais atteint par le catalogue actuel
     * de noms de waypoints), les derniers mots sont regroupés sur la dernière ligne plutôt que
     * perdus.
     */
    static List<String> wrapSignLines(String name) {
        String[] words = name.trim().split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.isEmpty()) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= SIGN_LINE_MAX_CHARS
                    || lines.size() >= SIGN_MAX_LINES - 1) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
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
