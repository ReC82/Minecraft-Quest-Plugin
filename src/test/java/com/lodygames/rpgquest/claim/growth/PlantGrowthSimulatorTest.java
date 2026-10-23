package com.lodygames.rpgquest.claim.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bamboo;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** Issue #776 : barème de rattrapage, une plante à la fois (voir {@link PlantGrowthSimulator}). */
class PlantGrowthSimulatorTest {

    /** 24 h de jeu au randomTickSpeed vanilla 3 : bien assez pour atteindre tous les plafonds. */
    private static final double ONE_DAY = PlantGrowthSimulator.expectedRandomTicks(24 * 72_000L, 3);
    private static final Claim CLAIM = new Claim("farm", UUID.randomUUID(), "claims",
            0, 0, 0, 31, 100, 31, Set.of(), ClaimFlags.defaults());

    private ServerMock server;
    private WorldMock world;
    private Plugin plugin;
    private PlantGrowthSimulator simulator;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = new WorldMock(Material.AIR, 0);
        world.setName("claims");
        server.addWorld(world);
        simulator = new PlantGrowthSimulator(new Random(42), server.getPluginManager());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block place(int x, int y, int z, Material type) {
        BlockMock block = (BlockMock) world.getBlockAt(x, y, z);
        block.setType(type);
        return block;
    }

    private void light(Block block, int level) {
        ((BlockMock) block).setLightFromSky((byte) level);
    }

    private Block cropOnFarmland(int x, int z, Material crop, boolean wet) {
        Block soil = place(x, 10, z, Material.FARMLAND);
        Farmland data = (Farmland) soil.getBlockData();
        data.setMoisture(wet ? data.getMaximumMoisture() : 0);
        soil.setBlockData(data);
        Block plant = place(x, 11, z, crop);
        light(plant, 15);
        return plant;
    }

    private static int age(Block block) {
        return ((Ageable) block.getBlockData()).getAge();
    }

    @Test
    void everyCropReachesItsMaximumAgeAfterOneDay() {
        for (Material crop : new Material[] {Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS}) {
            Block plant = cropOnFarmland(2, 2, crop, true);
            assertEquals(1, simulator.simulate(plant, CLAIM, ONE_DAY), crop + " doit pousser");
            assertEquals(((Ageable) plant.getBlockData()).getMaximumAge(), age(plant), crop + " plafonnée à l'âge max");
            assertEquals(Material.FARMLAND, plant.getRelative(BlockFace.DOWN).getType());
        }
    }

    @Test
    void shortAbsenceGivesVanillaAverageAndWetFarmlandGrowsFaster() {
        // 10 min de jeu = 8,79 random ticks ; terre humide isolée : f = 4 → 1/7 ; sèche : f = 2 → 1/13.
        double tenMinutes = PlantGrowthSimulator.expectedRandomTicks(12_000L, 3);
        int wetTotal = 0;
        int dryTotal = 0;
        for (int i = 0; i < 10; i++) {
            Block wet = cropOnFarmland(2 + 3 * i, 2, Material.WHEAT, true);
            Block dry = cropOnFarmland(2 + 3 * i, 8, Material.WHEAT, false);
            simulator.simulate(wet, CLAIM, tenMinutes);
            simulator.simulate(dry, CLAIM, tenMinutes);
            assertTrue(age(wet) <= 2 && age(dry) <= 1, "jamais plus que la moyenne arrondie au-dessus");
            wetTotal += age(wet);
            dryTotal += age(dry);
        }
        assertTrue(wetTotal > dryTotal, "terre humide plus rapide que sèche : " + wetTotal + " / " + dryTotal);
    }

    @Test
    void cropsNeedLightAndNothingGrowsOutsideTheClaim() {
        Block dark = cropOnFarmland(2, 2, Material.WHEAT, true);
        light(dark, 8);
        assertEquals(0, simulator.simulate(dark, CLAIM, ONE_DAY));
        assertEquals(0, age(dark));

        Block outside = cropOnFarmland(40, 2, Material.WHEAT, true);
        assertEquals(0, simulator.simulate(outside, CLAIM, ONE_DAY));
        assertEquals(0, age(outside));
    }

    @Test
    void cancelledGrowEventLeavesThePlantUntouched() {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void cancel(BlockGrowEvent event) {
                event.setCancelled(true);
            }
        }, plugin);
        Block wheat = cropOnFarmland(2, 2, Material.WHEAT, true);
        Block stem = cropOnFarmland(5, 5, Material.PUMPKIN_STEM, true);
        Block cane = cane(10, 10);

        assertEquals(0, simulator.simulate(wheat, CLAIM, ONE_DAY));
        assertEquals(0, simulator.simulate(stem, CLAIM, ONE_DAY));
        simulator.simulate(cane, CLAIM, ONE_DAY);
        assertEquals(0, age(wheat));
        assertEquals(0, age(stem));
        assertEquals(Material.AIR, cane.getRelative(BlockFace.UP).getType(), "aucune pose sans accord de l'événement");
    }

    @Test
    void matureStemGrowsOneFruitOnSoilAndAttaches() {
        Block stem = cropOnFarmland(5, 5, Material.MELON_STEM, true);
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            place(5 + face.getModX(), 10, 5 + face.getModZ(), Material.DIRT);
        }

        simulator.simulate(stem, CLAIM, ONE_DAY);

        assertEquals(Material.ATTACHED_MELON_STEM, stem.getType());
        BlockFace facing = ((Directional) stem.getBlockData()).getFacing();
        assertEquals(Material.MELON, stem.getRelative(facing).getType());
        int fruits = 0;
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            fruits += stem.getRelative(face).getType() == Material.MELON ? 1 : 0;
        }
        assertEquals(1, fruits, "un seul fruit par tige, jamais une récolte infinie");
    }

    @Test
    void stemNeverPlacesAFruitOnUnsuitableGroundOrOutsideTheClaim() {
        // Tige au bord du claim (x = 31) : seules les faces sans sol ou hors claim sont disponibles.
        Block stem = cropOnFarmland(31, 5, Material.PUMPKIN_STEM, true);
        place(30, 10, 5, Material.STONE);
        place(31, 10, 4, Material.GLASS);
        place(31, 10, 6, Material.GLASS);
        place(32, 10, 5, Material.DIRT); // hors claim

        simulator.simulate(stem, CLAIM, ONE_DAY);

        assertEquals(Material.PUMPKIN_STEM, stem.getType());
        assertEquals(7, age(stem), "la tige mûrit quand même");
        assertEquals(Material.AIR, world.getBlockAt(32, 11, 5).getType());
        assertEquals(Material.STONE, world.getBlockAt(30, 10, 5).getType(), "aucun bloc non végétal modifié");
    }

    private Block cane(int x, int z) {
        place(x, 10, z, Material.SAND);
        place(x + 1, 10, z, Material.WATER);
        Block cane = place(x, 11, z, Material.SUGAR_CANE);
        light(cane, 15);
        return cane;
    }

    @Test
    void sugarCaneAndCactusStopAtThreeBlocks() {
        Block cane = cane(10, 10);
        simulator.simulate(cane, CLAIM, ONE_DAY);
        assertEquals(Material.SUGAR_CANE, world.getBlockAt(10, 13, 10).getType());
        assertEquals(Material.AIR, world.getBlockAt(10, 14, 10).getType(), "colonne plafonnée à 3");

        place(20, 10, 20, Material.SAND);
        Block cactus = place(20, 11, 20, Material.CACTUS);
        simulator.simulate(cactus, CLAIM, ONE_DAY);
        assertEquals(Material.CACTUS, world.getBlockAt(20, 13, 20).getType());
        assertEquals(Material.AIR, world.getBlockAt(20, 14, 20).getType());
    }

    @Test
    void cactusNeverGrowsNextToASolidBlock() {
        place(20, 10, 20, Material.SAND);
        Block cactus = place(20, 11, 20, Material.CACTUS);
        place(21, 12, 20, Material.STONE);

        simulator.simulate(cactus, CLAIM, ONE_DAY);

        assertEquals(Material.AIR, world.getBlockAt(20, 12, 20).getType());
        assertEquals(15, age(cactus), "en attente, comme en jeu");
    }

    @Test
    void bambooGrowsFromSaplingUpToSixteenWithVanillaLeaves() {
        place(25, 10, 25, Material.DIRT);
        Block sapling = place(25, 11, 25, Material.BAMBOO_SAPLING);
        for (int y = 12; y < 40; y++) {
            light(world.getBlockAt(25, y, 25), 15);
        }
        simulator.simulate(sapling, CLAIM, PlantGrowthSimulator.expectedRandomTicks(30 * 72_000L, 3));

        int height = 0;
        while (world.getBlockAt(25, 11 + height, 25).getType() == Material.BAMBOO) {
            height++;
        }
        assertTrue(height >= 2 && height <= PlantGrowthSimulator.BAMBOO_MAX_HEIGHT, "hauteur : " + height);
        Bamboo top = (Bamboo) world.getBlockAt(25, 10 + height, 25).getBlockData();
        assertTrue(top.getLeaves() != Bamboo.Leaves.NONE, "le sommet porte des feuilles");
    }

    @Test
    void wartBerriesAndCocoaReachTheirMaximumAge() {
        place(3, 10, 3, Material.SOUL_SAND);
        Block wart = place(3, 11, 3, Material.NETHER_WART);
        place(6, 10, 6, Material.GRASS_BLOCK);
        Block berries = place(6, 11, 6, Material.SWEET_BERRY_BUSH);
        light(world.getBlockAt(6, 12, 6), 15);
        place(9, 11, 9, Material.JUNGLE_LOG);
        Block cocoa = place(9, 11, 10, Material.COCOA);

        simulator.simulate(wart, CLAIM, ONE_DAY);
        simulator.simulate(berries, CLAIM, ONE_DAY);
        simulator.simulate(cocoa, CLAIM, ONE_DAY);

        assertEquals(3, age(wart));
        assertEquals(3, age(berries));
        assertEquals(2, age(cocoa));
        assertEquals(Material.JUNGLE_LOG, world.getBlockAt(9, 11, 9).getType());
    }

    @Test
    void neighbourInAnUnloadedChunkIsNeverRead() {
        Block edge = place(15, 11, 2, Material.WHEAT);
        world.getChunkAt(1, 0).unload();

        assertEquals(null, PlantGrowthSimulator.relative(edge, 1, 0, 0));
        assertTrue(PlantGrowthSimulator.relative(edge, -1, 0, 0) != null);
    }
}
