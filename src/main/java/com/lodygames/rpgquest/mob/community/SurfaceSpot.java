package com.lodygames.rpgquest.mob.community;

import java.util.Optional;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Issue #471 — règle « apparition en surface uniquement » des boss communautaires, appliquée à une
 * colonne (x, z) dont le chunk est <strong>déjà chargé</strong>. Plus stricte que
 * {@link com.lodygames.rpgquest.travel.RandomSafeLocationFinder#findAccessibleColumn} :
 * <ul>
 *   <li>le sol est le plus haut bloc de la colonne (ciel ouvert : jamais une grotte) et appartient à
 *       une liste de <em>sols naturels</em> — herbe, terre, sable, pierre, neige compacte, terre cuite
 *       des badlands… Un toit, un plancher, du feuillage, un tronc, de la glace, de la neige poudreuse,
 *       de l'eau ou de la lave ne le sont pas (jamais d'apparition sur ou dans un bâtiment) ;</li>
 *   <li>trois blocs libres au-dessus, sans liquide (un boss peut être plus grand qu'un joueur) ;</li>
 *   <li>les 8 colonnes voisines sont à au plus {@link #MAX_STEP} blocs de hauteur et ne sont pas
 *       liquides : ni bord de ravin, ni falaise, ni berge ;</li>
 *   <li>le sol n'est pas sous {@code niveau de la mer - 2} : un fond de ravin ou de cuvette, d'où l'on
 *       ne voit pas le boss, est écarté.</li>
 * </ul>
 * Les voisins doivent être dans le même chunk ({@link #fitsInChunk}) : aucune lecture ne force jamais
 * le chargement d'un chunk voisin.
 */
public final class SurfaceSpot {

    static final int MAX_STEP = 2;
    static final int HEADROOM = 3;
    private static final int SEA_LEVEL_MARGIN = 2;

    private static final Set<Material> NATURAL_GROUND = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL,
            Material.MYCELIUM, Material.MUD, Material.MOSS_BLOCK, Material.CLAY, Material.SAND, Material.RED_SAND,
            Material.GRAVEL, Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF,
            Material.CALCITE, Material.DRIPSTONE_BLOCK, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.SNOW_BLOCK, Material.SNOW, Material.TERRACOTTA, Material.PACKED_ICE);

    private SurfaceSpot() {
    }

    /** Les 8 voisins de (x, z) restent dans le chunk de (x, z). */
    static boolean fitsInChunk(int x, int z) {
        int lx = x & 15;
        int lz = z & 15;
        return lx != 0 && lx != 15 && lz != 0 && lz != 15;
    }

    /** Position des pieds du boss si la colonne est une surface naturelle sûre, sinon vide. */
    public static Optional<Location> find(World world, int x, int z) {
        if (!fitsInChunk(x, z)) {
            return Optional.empty();
        }
        int groundY = world.getHighestBlockYAt(x, z);
        if (groundY <= world.getMinHeight() || groundY + HEADROOM >= world.getMaxHeight()
                || groundY < world.getSeaLevel() - SEA_LEVEL_MARGIN) {
            return Optional.empty();
        }
        if (!isNaturalGround(world.getBlockAt(x, groundY, z).getType())) {
            return Optional.empty();
        }
        for (int dy = 1; dy <= HEADROOM; dy++) {
            Material above = world.getBlockAt(x, groundY + dy, z).getType();
            if (above.isSolid() || isLiquidOrTrap(above)) {
                return Optional.empty();
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && !calmNeighbour(world, x + dx, z + dz, groundY)) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new Location(world, x + 0.5, groundY + 1, z + 0.5));
    }

    static boolean isNaturalGround(Material type) {
        return NATURAL_GROUND.contains(type) || type.name().endsWith("_TERRACOTTA") && !type.name().contains("GLAZED");
    }

    private static boolean calmNeighbour(World world, int x, int z, int groundY) {
        int y = world.getHighestBlockYAt(x, z);
        if (Math.abs(y - groundY) > MAX_STEP) {
            return false;
        }
        return !isLiquidOrTrap(world.getBlockAt(x, y, z).getType())
                && !isLiquidOrTrap(world.getBlockAt(x, y + 1, z).getType());
    }

    private static boolean isLiquidOrTrap(Material type) {
        return type == Material.WATER || type == Material.LAVA || type == Material.POWDER_SNOW
                || type == Material.BUBBLE_COLUMN || type == Material.FIRE || type == Material.SOUL_FIRE
                || type == Material.MAGMA_BLOCK || type == Material.CACTUS || type == Material.SWEET_BERRY_BUSH;
    }
}
