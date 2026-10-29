package com.lodygames.rpgquest.claim.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.MangrovePropagule;
import org.bukkit.block.data.type.Sapling;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Issue #917 : pousses d'arbres (voir {@link TreeGrowthSimulator}). MockBukkit ne génère pas
 * d'arbres : un générateur factice renvoie un arbre fixe (tronc de 4, feuillage 5×5 sur deux
 * niveaux, 2×2 pour les grands arbres) et note chaque appel.
 */
class TreeGrowthSimulatorTest {

    private static final double ONE_DAY = PlantGrowthSimulator.expectedRandomTicks(24 * 72_000L, 3);
    private static final Claim CLAIM = new Claim("farm", UUID.randomUUID(), "claims",
            0, 0, 0, 31, 100, 31, Set.of(), ClaimFlags.defaults());

    private ServerMock server;
    private WorldMock world;
    private Plugin plugin;
    private PlantGrowthSimulator simulator;
    private final List<TreeType> planned = new ArrayList<>();
    private final List<Location> origins = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = new WorldMock(Material.AIR, 0);
        world.setName("claims");
        server.addWorld(world);
        for (int cx = -1; cx <= 3; cx++) {
            for (int cz = -1; cz <= 3; cz++) {
                world.getChunkAt(cx, cz).load();
            }
        }
        simulator = new PlantGrowthSimulator(new Random(42), server.getPluginManager(), this::fakeTree);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private List<BlockState> fakeTree(Location origin, TreeType type, Random random) {
        planned.add(type);
        origins.add(origin);
        int trunk = isMega(type) ? 2 : 1;
        List<BlockState> states = new ArrayList<>();
        for (int y = 0; y < 4; y++) {
            for (int dx = 0; dx < trunk; dx++) {
                for (int dz = 0; dz < trunk; dz++) {
                    states.add(state(origin, dx, y, dz, Material.OAK_LOG));
                }
            }
        }
        for (int y = 3; y <= 4; y++) {
            for (int dx = -2; dx <= 1 + trunk; dx++) {
                for (int dz = -2; dz <= 1 + trunk; dz++) {
                    boolean log = y == 3 && dx >= 0 && dx < trunk && dz >= 0 && dz < trunk;
                    if (!log && Math.abs(dx) <= 2 + trunk && Math.abs(dz) <= 2 + trunk) {
                        states.add(state(origin, dx, y, dz, Material.OAK_LEAVES));
                    }
                }
            }
        }
        states.add(state(origin, 0, -1, 0, Material.DIRT)); // sol sous le tronc, comme vanilla
        return states;
    }

    private static boolean isMega(TreeType type) {
        return type == TreeType.MEGA_REDWOOD || type == TreeType.MEGA_PINE || type == TreeType.JUNGLE
                || type == TreeType.DARK_OAK || type == TreeType.PALE_OAK;
    }

    private BlockState state(Location origin, int dx, int dy, int dz, Material type) {
        BlockState state = world.getBlockAt(origin.getBlockX() + dx, origin.getBlockY() + dy,
                origin.getBlockZ() + dz).getState();
        state.setType(type);
        return state;
    }

    private Block sapling(int x, int z, Material type) {
        world.getBlockAt(x, 10, z).setType(Material.GRASS_BLOCK);
        BlockMock sapling = (BlockMock) world.getBlockAt(x, 11, z);
        sapling.setType(type);
        ((BlockMock) world.getBlockAt(x, 12, z)).setLightFromSky((byte) 15);
        return sapling;
    }

    private static int stage(Block block) {
        return ((Sapling) block.getBlockData()).getStage();
    }

    @Test
    void oakSaplingBecomesATreeAfterOneDayThroughAStructureGrowEvent() {
        List<StructureGrowEvent> events = new ArrayList<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void seen(StructureGrowEvent event) {
                events.add(event);
            }
        }, plugin);
        Block oak = sapling(8, 8, Material.OAK_SAPLING);

        assertTrue(simulator.simulate(oak, CLAIM, ONE_DAY) > 1);

        assertEquals(Material.OAK_LOG, oak.getType());
        assertEquals(Material.OAK_LEAVES, world.getBlockAt(10, 15, 10).getType());
        assertEquals(Material.DIRT, world.getBlockAt(8, 10, 8).getType());
        assertEquals(1, events.size());
        assertFalse(events.get(0).isFromBonemeal());
        assertTrue(Set.of(TreeType.TREE, TreeType.BIG_TREE).contains(events.get(0).getSpecies()));
    }

    @Test
    void eachEssenceUsesItsVanillaTreeType() {
        Material[] saplings = {Material.BIRCH_SAPLING, Material.SPRUCE_SAPLING, Material.JUNGLE_SAPLING,
                Material.ACACIA_SAPLING, Material.CHERRY_SAPLING};
        TreeType[] expected = {TreeType.BIRCH, TreeType.REDWOOD, TreeType.SMALL_JUNGLE, TreeType.ACACIA,
                TreeType.CHERRY};
        for (int i = 0; i < saplings.length; i++) {
            Block sapling = sapling(4 + 6 * (i % 4), 4 + 12 * (i / 4), saplings[i]);
            simulator.simulate(sapling, CLAIM, ONE_DAY);
            assertEquals(expected[i], planned.get(planned.size() - 1), saplings[i].name());
            assertEquals(Material.OAK_LOG, sapling.getType(), saplings[i] + " a poussé");
        }
    }

    @Test
    void twoByTwoSpruceAndJungleGrowGiantTreesFromTheNorthWestCorner() {
        for (int[] corner : new int[][] {{6, 6}, {20, 20}}) {
            Material type = corner[0] == 6 ? Material.SPRUCE_SAPLING : Material.JUNGLE_SAPLING;
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    sapling(corner[0] + dx, corner[1] + dz, type);
                }
            }
            // On part du coin sud-est : le carré est retrouvé, l'arbre part du coin nord-ouest.
            simulator.simulate(world.getBlockAt(corner[0] + 1, 11, corner[1] + 1), CLAIM, ONE_DAY);

            Location origin = origins.get(origins.size() - 1);
            assertEquals(corner[0], origin.getBlockX());
            assertEquals(corner[1], origin.getBlockZ());
            assertTrue(isMega(planned.get(planned.size() - 1)), type + " → arbre géant");
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    assertEquals(Material.OAK_LOG, world.getBlockAt(corner[0] + dx, 11, corner[1] + dz).getType());
                }
            }
        }
    }

    @Test
    void darkOakNeedsASquareOfFourSaplings() {
        Block alone = sapling(5, 5, Material.DARK_OAK_SAPLING);
        simulator.simulate(alone, CLAIM, ONE_DAY);
        assertEquals(Material.DARK_OAK_SAPLING, alone.getType());
        assertEquals(1, stage(alone), "le stade avance, l'arbre attend un carré");
        assertTrue(planned.isEmpty());

        for (int[] p : new int[][] {{20, 20}, {21, 20}, {20, 21}, {21, 21}}) {
            sapling(p[0], p[1], Material.DARK_OAK_SAPLING);
        }
        simulator.simulate(world.getBlockAt(20, 11, 20), CLAIM, ONE_DAY);
        assertEquals(List.of(TreeType.DARK_OAK), planned);
        assertEquals(Material.OAK_LOG, world.getBlockAt(21, 11, 21).getType());
    }

    @Test
    void treeCrossingTheClaimBorderIsRefusedAndTheSaplingRestored() {
        Block edge = sapling(30, 8, Material.BIRCH_SAPLING); // feuillage jusqu'en x = 32 : hors du claim

        assertEquals(1, simulator.simulate(edge, CLAIM, ONE_DAY), "seul le stade change");

        assertEquals(Material.BIRCH_SAPLING, edge.getType());
        assertEquals(1, stage(edge));
        assertEquals(Material.AIR, world.getBlockAt(30, 14, 8).getType());
        assertEquals(Material.AIR, world.getBlockAt(31, 14, 8).getType(), "rien de posé, même dans le claim");
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(30, 10, 8).getType());
    }

    @Test
    void treeNeverReplacesAConstruction() {
        Block oak = sapling(8, 8, Material.OAK_SAPLING);
        world.getBlockAt(9, 14, 9).setType(Material.CHEST);

        simulator.simulate(oak, CLAIM, ONE_DAY);

        assertEquals(Material.OAK_SAPLING, oak.getType());
        assertEquals(Material.CHEST, world.getBlockAt(9, 14, 9).getType());
        assertEquals(Material.AIR, world.getBlockAt(8, 13, 8).getType());
    }

    @Test
    void vegetationAndLeavesAreReplacedLikeVanilla() {
        Block oak = sapling(8, 8, Material.OAK_SAPLING);
        world.getBlockAt(9, 14, 9).setType(Material.SHORT_GRASS);
        world.getBlockAt(7, 15, 7).setType(Material.BIRCH_LEAVES);

        simulator.simulate(oak, CLAIM, ONE_DAY);

        assertEquals(Material.OAK_LOG, oak.getType());
        assertEquals(Material.OAK_LEAVES, world.getBlockAt(9, 14, 9).getType());
    }

    @Test
    void cancelledStructureGrowEventKeepsTheSapling() {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void cancel(StructureGrowEvent event) {
                event.setCancelled(true);
            }
        }, plugin);
        Block oak = sapling(8, 8, Material.OAK_SAPLING);

        simulator.simulate(oak, CLAIM, ONE_DAY);

        assertEquals(Material.OAK_SAPLING, oak.getType());
        assertEquals(Material.AIR, world.getBlockAt(8, 12, 8).getType());
        assertEquals(TreeGrowthSimulator.MAX_TREE_ATTEMPTS, planned.size(), "tentatives plafonnées");
    }

    @Test
    void unloadedNeighbourChunkMeansNoGenerationAtAll() {
        Block oak = sapling(17, 8, Material.OAK_SAPLING); // chunk (1, 0) ; 17 − 16 = 1 → chunk 0
        world.getChunkAt(0, 0).unload();

        simulator.simulate(oak, CLAIM, ONE_DAY);

        assertTrue(planned.isEmpty(), "le générateur aurait lu un chunk non chargé");
        assertEquals(Material.OAK_SAPLING, oak.getType());
    }

    @Test
    void darkSaplingNeverGrowsAndBlockLightCountsAllDay() {
        Block dark = sapling(8, 8, Material.OAK_SAPLING);
        ((BlockMock) world.getBlockAt(8, 12, 8)).setLightFromSky((byte) 8);
        assertEquals(0, simulator.simulate(dark, CLAIM, ONE_DAY));
        assertEquals(0, stage(dark));

        BlockMock torchLit = (BlockMock) world.getBlockAt(8, 12, 8);
        torchLit.setLightFromBlocks((byte) 9);
        assertEquals(1.0, TreeGrowthSimulator.lightShare(torchLit));
        double sunlight = TreeGrowthSimulator.lightShare(sapling(20, 20, Material.OAK_SAPLING)
                .getRelative(0, 1, 0));
        assertTrue(sunlight > 0.5 && sunlight < 0.8, "ciel 15 : jour seulement — " + sunlight);
    }

    @Test
    void oneSuccessOnlyAdvancesTheStage() {
        Block oak = sapling(8, 8, Material.OAK_SAPLING);
        ((BlockMock) world.getBlockAt(8, 12, 8)).setLightFromBlocks((byte) 15);

        // 7 random ticks × 1/7 = exactement un succès : stade 0 → 1, pas encore d'arbre.
        assertEquals(1, simulator.simulate(oak, CLAIM, 7.0));
        assertEquals(1, stage(oak));
        assertTrue(planned.isEmpty());

        assertTrue(simulator.simulate(oak, CLAIM, 7.0) > 1, "succès suivant : l'arbre");
        assertEquals(Material.OAK_LOG, oak.getType());
    }

    @Test
    void repeatedCatchUpNeverGrowsTheSameTreeTwice() {
        Block oak = sapling(8, 8, Material.OAK_SAPLING);
        simulator.simulate(oak, CLAIM, ONE_DAY);
        int calls = planned.size();

        assertEquals(0, simulator.simulate(world.getBlockAt(8, 11, 8), CLAIM, ONE_DAY));
        assertEquals(calls, planned.size());
        assertFalse(PlantGrowthSimulator.PLANTS.contains(oak.getType()), "un tronc n'est plus une plante");
    }

    @Test
    void replaceablePolicy() {
        assertTrue(TreeGrowthSimulator.replaceable(Material.AIR, Material.OAK_LOG));
        assertTrue(TreeGrowthSimulator.replaceable(Material.SHORT_GRASS, Material.OAK_LEAVES));
        assertTrue(TreeGrowthSimulator.replaceable(Material.GRASS_BLOCK, Material.PODZOL));
        assertTrue(TreeGrowthSimulator.replaceable(Material.MUD, Material.MUDDY_MANGROVE_ROOTS));
        assertFalse(TreeGrowthSimulator.replaceable(Material.OAK_PLANKS, Material.OAK_LEAVES));
        assertFalse(TreeGrowthSimulator.replaceable(Material.FARMLAND, Material.OAK_LOG));
        assertFalse(TreeGrowthSimulator.replaceable(Material.MUD, Material.OAK_LOG));
        assertFalse(TreeGrowthSimulator.replaceable(Material.WHEAT, Material.OAK_LEAVES));
    }

    @Test
    void hangingPropaguleOnlyAgesAndGroundPropaguleBecomesAMangrove() {
        BlockMock hanging = (BlockMock) world.getBlockAt(5, 20, 5);
        hanging.setType(Material.MANGROVE_PROPAGULE);
        MangrovePropagule data = (MangrovePropagule) hanging.getBlockData();
        data.setHanging(true);
        hanging.setBlockData(data);

        assertEquals(1, simulator.simulate(hanging, CLAIM, ONE_DAY));
        assertEquals(data.getMaximumAge(), ((MangrovePropagule) hanging.getBlockData()).getAge());
        assertTrue(planned.isEmpty(), "une propagule suspendue ne devient jamais un arbre");

        Block ground = sapling(20, 20, Material.MANGROVE_PROPAGULE);
        simulator.simulate(ground, CLAIM, ONE_DAY);
        assertTrue(Set.of(TreeType.MANGROVE, TreeType.TALL_MANGROVE).contains(planned.get(0)));
        assertEquals(Material.OAK_LOG, ground.getType());
    }
}
