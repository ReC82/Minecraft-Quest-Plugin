package com.lodygames.rpgquest.claim.growth;

import com.lodygames.rpgquest.claim.model.Claim;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.MangrovePropagule;
import org.bukkit.block.data.type.Sapling;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.PluginManager;

/**
 * Issue #917 : rattrapage des pousses d'arbres (phase 2 de #776), appelé par {@link
 * PlantGrowthSimulator}.
 *
 * <p>Vanilla : à chaque random tick, si la lumière au-dessus atteint 9, une chance sur 7 fait avancer
 * la pousse — stade 0 → 1, puis tentative d'arbre. Ici : succès attendus = random ticks × part de
 * lumière / 7 ; le premier fait passer au stade 1, les suivants (au plus {@link #MAX_TREE_ATTEMPTS})
 * tentent l'arbre.</p>
 *
 * <p>Politique « tout ou rien » : l'arbre est d'abord calculé à blanc ({@link TreePlanner}, générateur
 * vanilla de Paper), puis refusé en entier si un seul de ses blocs sort du claim, tombe dans un chunk
 * non chargé ou remplacerait autre chose que de l'air, du feuillage, de la végétation remplaçable ou de
 * la terre (sol converti en podzol/terre). Accepté, il passe par un {@link StructureGrowEvent} (même
 * événement qu'en jeu) avant d'être posé ; annulé ou refusé, les pousses sont remises à l'identique.
 * Le générateur n'est appelé que si tous les chunks à {@link #TREE_REACH} blocs autour sont chargés :
 * il ne peut donc charger aucun chunk.</p>
 */
final class TreeGrowthSimulator {

    static final int SAPLING_CHANCE = 7;
    static final int MAX_TREE_ATTEMPTS = 4;
    /** Portée horizontale lue par le générateur (houppiers géants, racines de palétuvier compris). */
    static final int TREE_REACH = 16;

    static final Set<Material> SAPLINGS = EnumSet.of(Material.OAK_SAPLING, Material.SPRUCE_SAPLING,
            Material.BIRCH_SAPLING, Material.JUNGLE_SAPLING, Material.ACACIA_SAPLING, Material.DARK_OAK_SAPLING,
            Material.CHERRY_SAPLING, Material.PALE_OAK_SAPLING, Material.MANGROVE_PROPAGULE);

    /** Blocs non végétaux que les racines de palétuvier traversent en jeu (tag vanilla). */
    private static final Set<Material> MANGROVE_GROUND = EnumSet.of(Material.MUD, Material.MUDDY_MANGROVE_ROOTS,
            Material.MANGROVE_ROOTS, Material.MOSS_CARPET, Material.SNOW, Material.WATER);

    /** Part du jour où la lumière du ciel {@code s} (indice) reste ≥ 9 une fois assombrie par la nuit. */
    private static final double[] DAYLIGHT_SHARE = daylightShares();

    private final RandomGenerator random;
    private final PluginManager pluginManager;
    private final TreePlanner planner;

    TreeGrowthSimulator(RandomGenerator random, PluginManager pluginManager, TreePlanner planner) {
        this.random = random;
        this.pluginManager = pluginManager;
        this.planner = planner;
    }

    int simulate(Block sapling, Claim claim, double randomTicks) {
        if (sapling.getBlockData() instanceof MangrovePropagule propagule && propagule.isHanging()) {
            return hangingPropagule(sapling, propagule, randomTicks);
        }
        double light = lightShare(PlantGrowthSimulator.relative(sapling, 0, 1, 0));
        long advances = PlantGrowthSimulator.successes(random, randomTicks, light / SAPLING_CHANCE);
        if (advances <= 0) {
            return 0;
        }
        int changed = 0;
        Sapling data = (Sapling) sapling.getBlockData();
        if (data.getStage() < data.getMaximumStage()) {
            data.setStage(data.getMaximumStage());
            sapling.setBlockData(data, false);
            changed = 1;
            advances--;
        }
        for (long attempt = 0; attempt < Math.min(advances, MAX_TREE_ATTEMPTS); attempt++) {
            int placed = growTree(sapling, claim);
            if (placed != 0) {
                return placed;
            }
        }
        return changed;
    }

    /** Vanilla : une propagule suspendue gagne un âge par random tick, sans jamais devenir un arbre. */
    private int hangingPropagule(Block block, MangrovePropagule data, double randomTicks) {
        int target = (int) Math.min(data.getMaximumAge(),
                data.getAge() + PlantGrowthSimulator.successes(random, randomTicks, 1.0));
        if (target <= data.getAge()) {
            return 0;
        }
        data.setAge(target);
        block.setBlockData(data, false);
        return 1;
    }

    /**
     * Une tentative d'arbre. Les pousses sont retirées pendant le calcul (vanilla fait de même : le
     * tronc prend leur place), puis remises si l'arbre est refusé.
     *
     * @return blocs posés, 0 si l'arbre est refusé ou impossible
     */
    private int growTree(Block sapling, Claim claim) {
        if (!neighbourhoodLoaded(sapling)) {
            return 0;
        }
        List<Block> saplings = twoByTwo(sapling);
        Block origin = saplings.isEmpty() ? sapling : saplings.get(0);
        TreeType type = species(sapling.getType(), !saplings.isEmpty());
        if (type == null) {
            return 0; // chêne noir / chêne pâle isolé : comme en jeu, il faut un carré de 2×2
        }
        if (saplings.isEmpty()) {
            saplings = List.of(sapling);
        }
        for (Block part : saplings) {
            if (!PlantGrowthSimulator.inClaim(claim, part)) {
                return 0;
            }
        }
        List<BlockData> before = new ArrayList<>(saplings.size());
        for (Block part : saplings) {
            before.add(part.getBlockData());
            part.setBlockData(emptied(part.getBlockData()), false);
        }
        List<BlockState> planned = planner.plan(origin.getLocation(), type, new Random(random.nextLong()));
        if (!planned.isEmpty() && acceptable(planned, claim)) {
            StructureGrowEvent event = new StructureGrowEvent(origin.getLocation(), type, false, null, planned);
            pluginManager.callEvent(event);
            if (!event.isCancelled() && acceptable(event.getBlocks(), claim)) {
                for (BlockState state : event.getBlocks()) {
                    state.update(true, false);
                }
                return event.getBlocks().size();
            }
        }
        for (int i = 0; i < saplings.size(); i++) {
            saplings.get(i).setBlockData(before.get(i), false);
        }
        return 0;
    }

    /** Chaque bloc de l'arbre : dans le claim, chunk chargé, ne remplace rien de construit. */
    static boolean acceptable(List<BlockState> planned, Claim claim) {
        if (planned.isEmpty()) {
            return false;
        }
        for (BlockState state : planned) {
            World world = state.getWorld();
            if (!claim.contains(world.getName(), state.getX(), state.getY(), state.getZ())
                    || !world.isChunkLoaded(state.getX() >> 4, state.getZ() >> 4)) {
                return false;
            }
            Material current = world.getBlockAt(state.getX(), state.getY(), state.getZ()).getType();
            if (!replaceable(current, state.getType())) {
                return false;
            }
        }
        return true;
    }

    /** Ce qu'un arbre vanilla peut recouvrir — tout le reste (construction, coffre, culture…) bloque. */
    static boolean replaceable(Material current, Material placed) {
        if (current.isAir() || current == placed || Tag.LEAVES.isTagged(current)
                || Tag.REPLACEABLE_BY_TREES.isTagged(current) || SAPLINGS.contains(current)) {
            return true;
        }
        if (Tag.DIRT.isTagged(current) && Tag.DIRT.isTagged(placed)) {
            return true; // sol sous le tronc ou podzol des grands sapins
        }
        return MANGROVE_GROUND.contains(current)
                && (placed == Material.MANGROVE_ROOTS || placed == Material.MUDDY_MANGROVE_ROOTS);
    }

    /**
     * Carré de 2×2 pousses identiques contenant {@code sapling} (ordre vanilla : coin nord-ouest
     * d'abord, puis les décalages −1), coin nord-ouest en tête ; vide s'il n'y en a pas.
     */
    private static List<Block> twoByTwo(Block sapling) {
        Material type = sapling.getType();
        if (type == Material.MANGROVE_PROPAGULE) {
            return List.of();
        }
        for (int dx = 0; dx >= -1; dx--) {
            for (int dz = 0; dz >= -1; dz--) {
                List<Block> square = new ArrayList<>(4);
                for (int[] offset : new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
                    Block part = PlantGrowthSimulator.relative(sapling, dx + offset[0], 0, dz + offset[1]);
                    if (part == null || part.getType() != type) {
                        break;
                    }
                    square.add(part);
                }
                if (square.size() == 4) {
                    return square;
                }
            }
        }
        return List.of();
    }

    /** Essence vanilla ({@code TreeGrower}), variantes aléatoires comprises ; {@code null} = rien. */
    TreeType species(Material sapling, boolean twoByTwo) {
        return switch (sapling) {
            case OAK_SAPLING -> random.nextFloat() < 0.1F ? TreeType.BIG_TREE : TreeType.TREE;
            case SPRUCE_SAPLING -> twoByTwo
                    ? (random.nextBoolean() ? TreeType.MEGA_REDWOOD : TreeType.MEGA_PINE) : TreeType.REDWOOD;
            case BIRCH_SAPLING -> TreeType.BIRCH;
            case JUNGLE_SAPLING -> twoByTwo ? TreeType.JUNGLE : TreeType.SMALL_JUNGLE;
            case ACACIA_SAPLING -> TreeType.ACACIA;
            case CHERRY_SAPLING -> TreeType.CHERRY;
            case DARK_OAK_SAPLING -> twoByTwo ? TreeType.DARK_OAK : null;
            case PALE_OAK_SAPLING -> twoByTwo ? TreeType.PALE_OAK : null;
            case MANGROVE_PROPAGULE -> random.nextFloat() < 0.85F ? TreeType.TALL_MANGROVE : TreeType.MANGROVE;
            default -> null;
        };
    }

    /** Tous les chunks lus par le générateur sont chargés : il ne peut alors en charger aucun. */
    private static boolean neighbourhoodLoaded(Block sapling) {
        World world = sapling.getWorld();
        for (int cx = (sapling.getX() - TREE_REACH) >> 4; cx <= (sapling.getX() + TREE_REACH) >> 4; cx++) {
            for (int cz = (sapling.getZ() - TREE_REACH) >> 4; cz <= (sapling.getZ() + TREE_REACH) >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Ce qui reste à la place d'une pousse retirée : l'eau d'une propagule immergée, sinon l'air. */
    private static BlockData emptied(BlockData sapling) {
        return sapling instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()
                ? Material.WATER.createBlockData() : Material.AIR.createBlockData();
    }

    /**
     * Vanilla : lumière locale ≥ 9 au-dessus de la pousse, la lumière du ciel étant assombrie la nuit.
     * Une lumière de blocs ≥ 9 compte tout le temps ; sinon la part du jour où le ciel suffit.
     */
    static double lightShare(Block above) {
        if (above == null) {
            return 0.0;
        }
        if (above.getLightFromBlocks() >= PlantGrowthSimulator.MIN_LIGHT) {
            return 1.0;
        }
        return DAYLIGHT_SHARE[Math.max(0, Math.min(15, above.getLightFromSky()))];
    }

    /**
     * Assombrissement vanilla du ciel (temps clair) sur un jour de 24 000 ticks : {@code skyDarken =
     * ⌊(1 − f) × 11⌋}, {@code f = 0,5 + 2 × clamp(cos(2π × timeOfDay), −¼, ¼)}.
     */
    private static double[] daylightShares() {
        int[] darkTicks = new int[12];
        for (int tick = 0; tick < 24_000; tick++) {
            double d = tick / 24_000.0 - 0.25;
            d -= Math.floor(d);
            double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
            double timeOfDay = (d * 2.0 + e) / 3.0;
            double f = 0.5 + 2.0 * Math.max(-0.25, Math.min(0.25, Math.cos(timeOfDay * Math.PI * 2.0)));
            darkTicks[(int) ((1.0 - f) * 11.0)]++;
        }
        double[] shares = new double[16];
        for (int sky = 0; sky <= 15; sky++) {
            int enough = 0;
            for (int darken = 0; darken < darkTicks.length; darken++) {
                if (sky - darken >= PlantGrowthSimulator.MIN_LIGHT) {
                    enough += darkTicks[darken];
                }
            }
            shares[sky] = enough / 24_000.0;
        }
        return shares;
    }
}
