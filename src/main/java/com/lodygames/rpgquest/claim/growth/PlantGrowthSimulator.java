package com.lodygames.rpgquest.claim.growth;

import com.lodygames.rpgquest.claim.model.Claim;
import java.util.EnumSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bamboo;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.plugin.PluginManager;

/**
 * Barème de rattrapage de croissance d'une plante (issue #776) : applique en une fois la croissance
 * <strong>moyenne</strong> que les random ticks vanilla auraient produite pendant une durée donnée,
 * plutôt que de rejouer chaque tick.
 *
 * <p>Un bloc reçoit en moyenne {@code randomTickSpeed / 4096} random tick par tick de jeu (voir {@link
 * #expectedRandomTicks}). Pour chaque plante, le nombre de succès attendu ({@code ticks × chance}) est
 * arrondi au hasard ({@link #successes}) puis appliqué avec les règles vanilla : lumière brute ≥ 9,
 * vitesse des cultures selon la terre labourée (humide ou sèche) et les voisines, plafond d'âge,
 * fruit de citrouille/pastèque posé sur terre, colonnes de canne/cactus limitées à 3, bambou limité à
 * 16. Le détail est documenté dans {@code docs/CLAIMS.md}.</p>
 *
 * <p>Garanties : seuls les blocs de plante, l'air au-dessus d'une colonne et l'air voisin d'une tige
 * mûre sont modifiés, toujours <strong>dans le même claim</strong> ; toute pose ou montée d'âge passe
 * par un {@link BlockGrowEvent} (un événement annulé arrête la plante) ; un voisin situé dans un chunk
 * non chargé est traité comme absent, jamais lu — le mécanisme ne charge aucun chunk.</p>
 */
public final class PlantGrowthSimulator {

    /** Blocs d'une section (16³) : un random tick vise un bloc au hasard parmi eux. */
    static final double SECTION_VOLUME = 4096.0;
    static final int MIN_LIGHT = 9;
    static final int COLUMN_MAX_HEIGHT = 3;
    static final int COLUMN_TICKS_PER_BLOCK = 16;
    static final int BAMBOO_MAX_HEIGHT = 16;
    static final int MAX_FRUIT_ATTEMPTS = 64;

    private static final BlockFace[] HORIZONTALS = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /** Sols acceptant un fruit de tige (vanilla : terre labourée ou tag {@code dirt}). */
    private static final Set<Material> FRUIT_SOILS = EnumSet.of(Material.FARMLAND, Material.DIRT,
            Material.GRASS_BLOCK, Material.PODZOL, Material.COARSE_DIRT, Material.MYCELIUM, Material.ROOTED_DIRT,
            Material.MOSS_BLOCK, Material.PALE_MOSS_BLOCK, Material.MUD, Material.MUDDY_MANGROVE_ROOTS);

    /** Toutes les plantes rattrapées — tout autre bloc n'est jamais lu comme point de départ. */
    public static final Set<Material> PLANTS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.PUMPKIN_STEM, Material.MELON_STEM, Material.NETHER_WART,
            Material.SWEET_BERRY_BUSH, Material.COCOA, Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO,
            Material.BAMBOO_SAPLING);

    private final RandomGenerator random;
    private final PluginManager pluginManager;

    public PlantGrowthSimulator(RandomGenerator random, PluginManager pluginManager) {
        this.random = random;
        this.pluginManager = pluginManager;
    }

    /** Random ticks moyens reçus par un bloc pendant {@code elapsedTicks} ticks de jeu. */
    public static double expectedRandomTicks(long elapsedTicks, int randomTickSpeed) {
        return elapsedTicks * (double) randomTickSpeed / SECTION_VOLUME;
    }

    /**
     * Rattrape la croissance de {@code plant} pour {@code randomTicks} random ticks moyens.
     *
     * @return nombre de blocs réellement modifiés (0 si rien n'a poussé)
     */
    public int simulate(Block plant, Claim claim, double randomTicks) {
        if (randomTicks <= 0 || !inClaim(claim, plant)) {
            return 0;
        }
        return switch (plant.getType()) {
            case WHEAT, CARROTS, POTATOES -> crop(plant, randomTicks, 1.0);
            // Vanilla : la betterave ignore un random tick sur trois.
            case BEETROOTS -> crop(plant, randomTicks, 2.0 / 3.0);
            case PUMPKIN_STEM -> stem(plant, claim, randomTicks, Material.PUMPKIN, Material.ATTACHED_PUMPKIN_STEM);
            case MELON_STEM -> stem(plant, claim, randomTicks, Material.MELON, Material.ATTACHED_MELON_STEM);
            case NETHER_WART -> age(plant, successes(randomTicks, 1.0 / 10.0));
            case COCOA -> age(plant, successes(randomTicks, 1.0 / 5.0));
            case SWEET_BERRY_BUSH -> rawLight(relative(plant, 0, 1, 0)) >= MIN_LIGHT
                    ? age(plant, successes(randomTicks, 1.0 / 5.0)) : 0;
            case SUGAR_CANE, CACTUS -> column(plant, claim, randomTicks);
            case BAMBOO_SAPLING -> bambooSapling(plant, claim, randomTicks);
            case BAMBOO -> bamboo(plant, claim, randomTicks);
            default -> 0;
        };
    }

    // ---- Cultures et tiges -------------------------------------------------------------------------

    private int crop(Block plant, double randomTicks, double tickShare) {
        if (rawLight(plant) < MIN_LIGHT) {
            return 0;
        }
        return age(plant, successes(randomTicks, tickShare * cropChance(plant)));
    }

    private int stem(Block plant, Claim claim, double randomTicks, Material fruit, Material attached) {
        if (rawLight(plant) < MIN_LIGHT) {
            return 0;
        }
        long advances = successes(randomTicks, cropChance(plant));
        Ageable data = (Ageable) plant.getBlockData();
        int needed = data.getMaximumAge() - data.getAge();
        int changed = 0;
        if (needed > 0 && advances > 0) {
            changed = age(plant, advances);
            if (changed == 0) {
                return 0; // montée d'âge annulée : la tige s'arrête là, comme en jeu
            }
            advances -= needed;
        }
        if (advances <= 0 || ((Ageable) plant.getBlockData()).getAge() < data.getMaximumAge()) {
            return changed;
        }
        return changed + placeFruit(plant, claim, advances, fruit, attached);
    }

    /** Chaque succès au-delà de l'âge maximal = une tentative vers une face horizontale au hasard. */
    private int placeFruit(Block stem, Claim claim, long advances, Material fruit, Material attached) {
        long attempts = Math.min(advances, MAX_FRUIT_ATTEMPTS);
        for (long attempt = 0; attempt < attempts; attempt++) {
            BlockFace face = HORIZONTALS[random.nextInt(HORIZONTALS.length)];
            Block target = relative(stem, face.getModX(), 0, face.getModZ());
            if (target == null || !target.getType().isAir() || !inClaim(claim, target)) {
                continue;
            }
            Block soil = relative(target, 0, -1, 0);
            if (soil == null || !FRUIT_SOILS.contains(soil.getType())) {
                continue;
            }
            if (!grow(target, fruit.createBlockData())) {
                return 0;
            }
            Directional attachedStem = (Directional) attached.createBlockData();
            attachedStem.setFacing(face);
            stem.setBlockData(attachedStem, false);
            return 2;
        }
        return 0;
    }

    /** Vanilla {@code CropBlock.getGrowthSpeed} puis chance {@code 1 / (⌊25 / f⌋ + 1)} par random tick. */
    private double cropChance(Block plant) {
        float speed = 1.0F;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block soil = relative(plant, dx, -1, dz);
                float share = 0.0F;
                if (soil != null && soil.getBlockData() instanceof Farmland farmland) {
                    share = farmland.getMoisture() > 0 ? 3.0F : 1.0F;
                }
                if (dx != 0 || dz != 0) {
                    share /= 4.0F;
                }
                speed += share;
            }
        }
        Material type = plant.getType();
        boolean row = isType(relative(plant, -1, 0, 0), type) || isType(relative(plant, 1, 0, 0), type);
        boolean column = isType(relative(plant, 0, 0, -1), type) || isType(relative(plant, 0, 0, 1), type);
        boolean diagonal = isType(relative(plant, -1, 0, -1), type) || isType(relative(plant, 1, 0, -1), type)
                || isType(relative(plant, 1, 0, 1), type) || isType(relative(plant, -1, 0, 1), type);
        if ((row && column) || diagonal) {
            speed /= 2.0F;
        }
        return 1.0 / ((int) (25.0F / speed) + 1);
    }

    /** Monte l'âge de {@code gained} crans, plafonné à l'âge maximal, par un seul {@link BlockGrowEvent}. */
    private int age(Block plant, long gained) {
        Ageable data = (Ageable) plant.getBlockData();
        int target = (int) Math.min(data.getMaximumAge(), data.getAge() + gained);
        if (target <= data.getAge()) {
            return 0;
        }
        Ageable grown = (Ageable) data.clone();
        grown.setAge(target);
        return grow(plant, grown) ? 1 : 0;
    }

    // ---- Canne à sucre et cactus --------------------------------------------------------------------

    /**
     * Vanilla : le bloc du sommet gagne un âge par random tick ; à l'âge 15, un bloc est posé au-dessus
     * (colonne de 3 au plus) et l'âge repart de 0. Seul le sommet d'une colonne est traité.
     */
    private int column(Block plant, Claim claim, double randomTicks) {
        Material type = plant.getType();
        Block above = relative(plant, 0, 1, 0);
        if (above == null || !above.getType().isAir()) {
            return 0;
        }
        int height = 1;
        while (height < COLUMN_MAX_HEIGHT && isType(relative(plant, 0, -height, 0), type)) {
            height++;
        }
        if (height >= COLUMN_MAX_HEIGHT) {
            return 0;
        }
        long ticks = ((Ageable) plant.getBlockData()).getAge() + successes(randomTicks, 1.0);
        Block top = plant;
        int changed = 0;
        while (ticks >= COLUMN_TICKS_PER_BLOCK && height < COLUMN_MAX_HEIGHT) {
            Block next = relative(top, 0, 1, 0);
            if (next == null || !next.getType().isAir() || !inClaim(claim, next)
                    || (type == Material.CACTUS && !cactusCanStand(next)) || !grow(next, type.createBlockData())) {
                break;
            }
            top.setBlockData(withAge(top, 0), false);
            top = next;
            height++;
            ticks -= COLUMN_TICKS_PER_BLOCK;
            changed++;
        }
        if (height < COLUMN_MAX_HEIGHT) {
            int age = (int) Math.min(ticks, COLUMN_TICKS_PER_BLOCK - 1);
            if (((Ageable) top.getBlockData()).getAge() != age) {
                top.setBlockData(withAge(top, age), false);
                changed = Math.max(changed, 1);
            }
        }
        return changed;
    }

    /** Vanilla : un cactus casse à côté d'un bloc solide ou de lave — on ne le pose jamais là. */
    private boolean cactusCanStand(Block position) {
        for (BlockFace face : HORIZONTALS) {
            Block side = relative(position, face.getModX(), 0, face.getModZ());
            if (side == null || side.getType().isSolid() || side.getType() == Material.LAVA) {
                return false;
            }
        }
        return true;
    }

    // ---- Bambou -------------------------------------------------------------------------------------

    private int bambooSapling(Block sapling, Claim claim, double randomTicks) {
        long gained = successes(randomTicks, 1.0 / 3.0);
        Block above = relative(sapling, 0, 1, 0);
        if (gained <= 0 || !canGrowInto(claim, above)) {
            return 0;
        }
        Bamboo shoot = (Bamboo) Material.BAMBOO.createBlockData();
        shoot.setLeaves(Bamboo.Leaves.SMALL);
        if (!grow(above, shoot)) {
            return 0;
        }
        sapling.setBlockData(Material.BAMBOO.createBlockData(), false);
        return 1 + growStalk(above, claim, gained - 1);
    }

    private int bamboo(Block plant, Claim claim, double randomTicks) {
        Block above = relative(plant, 0, 1, 0);
        if (above == null || !above.getType().isAir()) {
            return 0; // seul le sommet de la tige pousse
        }
        return growStalk(plant, claim, successes(randomTicks, 1.0 / 3.0));
    }

    private int growStalk(Block top, Claim claim, long gained) {
        int changed = 0;
        for (long step = 0; step < gained; step++) {
            Bamboo data = (Bamboo) top.getBlockData();
            Block above = relative(top, 0, 1, 0);
            int height = 1;
            while (height < BAMBOO_MAX_HEIGHT && isType(relative(top, 0, -height, 0), Material.BAMBOO)) {
                height++;
            }
            if (data.getStage() != 0 || height >= BAMBOO_MAX_HEIGHT || !canGrowInto(claim, above)
                    || !growBambooAbove(top, data, above, height)) {
                break;
            }
            top = above;
            changed++;
        }
        return changed;
    }

    /** Port de vanilla {@code BambooStalkBlock.growBamboo} : feuilles, épaisseur et arrêt de croissance. */
    private boolean growBambooAbove(Block top, Bamboo data, Block above, int height) {
        Block below = relative(top, 0, -1, 0);
        Block below2 = relative(top, 0, -2, 0);
        Bamboo belowData = isType(below, Material.BAMBOO) ? (Bamboo) below.getBlockData() : null;
        boolean below2Bamboo = isType(below2, Material.BAMBOO);
        Bamboo.Leaves leaves = Bamboo.Leaves.SMALL;
        boolean shiftLeaves = false;
        if (belowData != null && belowData.getLeaves() != Bamboo.Leaves.NONE) {
            leaves = Bamboo.Leaves.LARGE;
            shiftLeaves = below2Bamboo;
        }
        Bamboo grown = (Bamboo) Material.BAMBOO.createBlockData();
        grown.setAge(data.getAge() != 1 && !below2Bamboo ? 0 : 1);
        grown.setLeaves(leaves);
        grown.setStage((height < 11 || random.nextFloat() >= 0.25F) && height != 15 ? 0 : 1);
        if (!grow(above, grown)) {
            return false;
        }
        if (shiftLeaves) {
            belowData.setLeaves(Bamboo.Leaves.SMALL);
            below.setBlockData(belowData, false);
            Bamboo below2Data = (Bamboo) below2.getBlockData();
            below2Data.setLeaves(Bamboo.Leaves.NONE);
            below2.setBlockData(below2Data, false);
        }
        return true;
    }

    private boolean canGrowInto(Claim claim, Block above) {
        return above != null && above.getType().isAir() && inClaim(claim, above) && rawLight(above) >= MIN_LIGHT;
    }

    // ---- Outils -------------------------------------------------------------------------------------

    /** Succès attendus {@code trials × chance}, partie fractionnaire tirée au hasard (moyenne exacte). */
    long successes(double trials, double chance) {
        double mean = trials * chance;
        long whole = (long) mean;
        return whole + (random.nextDouble() < mean - whole ? 1 : 0);
    }

    /** Pose {@code data} via un {@link BlockGrowEvent} ; {@code false} si un plugin l'annule. */
    private boolean grow(Block target, BlockData data) {
        BlockState state = target.getState();
        state.setBlockData(data);
        BlockGrowEvent event = new BlockGrowEvent(target, state);
        pluginManager.callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        event.getNewState().update(true, false);
        return true;
    }

    private static BlockData withAge(Block block, int age) {
        Ageable data = (Ageable) block.getBlockData();
        data.setAge(age);
        return data;
    }

    /** Lumière brute vanilla ({@code getRawBrightness(pos, 0)}) : indépendante de l'heure. */
    private static int rawLight(Block block) {
        return block == null ? 0 : Math.max(block.getLightFromSky(), block.getLightFromBlocks());
    }

    private static boolean isType(Block block, Material type) {
        return block != null && block.getType() == type;
    }

    private static boolean inClaim(Claim claim, Block block) {
        return claim.contains(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * Bloc voisin, ou {@code null} hors des hauteurs du monde ou dans un autre chunk non chargé —
     * jamais de lecture qui chargerait un chunk.
     */
    static Block relative(Block from, int dx, int dy, int dz) {
        World world = from.getWorld();
        int y = from.getY() + dy;
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return null;
        }
        int x = from.getX() + dx;
        int z = from.getZ() + dz;
        if (((x >> 4) != (from.getX() >> 4) || (z >> 4) != (from.getZ() >> 4)) && !world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        return world.getBlockAt(x, y, z);
    }
}
