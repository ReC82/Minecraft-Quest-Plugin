package com.lodygames.rpgquest.mob.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Issue #471 — règle « surface uniquement » ({@link SurfaceSpot}) et recherche par défaut
 * ({@link CommunityBossService#targetedChunkLoads()}) : chargement ponctuel, sans génération, borné,
 * jamais retenu. Terrain construit bloc par bloc sur un monde MockBukkit (sol d'herbe à y = 70).
 */
class CommunityBossPlacementTest {

    /** Monde dont le chargement asynchrone est observable (MockBukkit ne l'implémente pas). */
    static final class AsyncWorld extends WorldMock {
        final List<long[]> asyncLoads = new ArrayList<>();
        int retained;
        boolean generated = true;

        @Override
        public boolean addPluginChunkTicket(int x, int z, org.bukkit.plugin.Plugin plugin) {
            retained++;
            return true;
        }

        @Override
        public void setChunkForceLoaded(int x, int z, boolean forced) {
            retained++;
        }

        AsyncWorld() {
            super(Material.GRASS_BLOCK, 70);
        }

        @Override
        public CompletableFuture<Chunk> getChunkAtAsync(int x, int z, boolean gen, boolean urgent) {
            asyncLoads.add(new long[] {x, z, gen ? 1 : 0});
            return CompletableFuture.completedFuture(generated ? getChunkAt(x, z) : null);
        }
    }

    private ServerMock server;
    private AsyncWorld world;
    private int ground;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = new AsyncWorld();
        world.setName("wild");
        server.addWorld(world);
        ground = world.getHighestBlockYAt(8, 8);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void set(int x, int y, int z, Material type) {
        world.getBlockAt(x, y, z).setType(type);
    }

    // ---- SurfaceSpot ---------------------------------------------------------------------------

    @Test
    void flatNaturalGroundUnderOpenSkyIsAccepted() {
        Optional<Location> spot = SurfaceSpot.find(world, 8, 8);
        assertTrue(spot.isPresent());
        assertEquals(ground + 1, spot.get().getBlockY());
    }

    @Test
    void columnsOnAChunkEdgeAreRefusedSoNoNeighbourChunkIsEverRead() {
        assertFalse(SurfaceSpot.find(world, 0, 8).isPresent());
        assertFalse(SurfaceSpot.find(world, 15, 8).isPresent());
        assertFalse(SurfaceSpot.find(world, 8, 31).isPresent());
    }

    @Test
    void roofsLeavesIcePowderSnowAndWaterAreNeverASurface() {
        for (Material top : List.of(Material.OAK_PLANKS, Material.OAK_LEAVES, Material.OAK_LOG, Material.ICE,
                Material.POWDER_SNOW, Material.WATER, Material.LAVA, Material.COBBLESTONE)) {
            set(8, ground, 8, top);
            assertFalse(SurfaceSpot.find(world, 8, 8).isPresent(), top.name());
        }
        set(8, ground, 8, Material.SNOW_BLOCK);
        assertTrue(SurfaceSpot.find(world, 8, 8).isPresent(), "neige tassée des montagnes : sol naturel");
    }

    @Test
    void underARoofTheHighestBlockIsTheRoofSoACaveOrHouseIsNeverChosen() {
        set(8, ground + 4, 8, Material.OAK_PLANKS); // plafond : le plus haut bloc devient le toit
        assertFalse(SurfaceSpot.find(world, 8, 8).isPresent());
    }

    @Test
    void cliffEdgeAndRiverBankAreRefused() {
        set(9, ground + 3, 9, Material.STONE);
        set(9, ground + 2, 9, Material.STONE);
        set(9, ground + 1, 9, Material.STONE);
        assertFalse(SurfaceSpot.find(world, 8, 8).isPresent(), "voisin 3 blocs plus haut : falaise");

        set(9, ground + 3, 9, Material.AIR);
        set(9, ground + 2, 9, Material.AIR);
        set(9, ground + 1, 9, Material.AIR);
        set(7, ground, 8, Material.WATER);
        assertFalse(SurfaceSpot.find(world, 8, 8).isPresent(), "berge");
    }

    @Test
    void notEnoughHeadroomIsRefused() {
        set(8, ground + 3, 8, Material.OAK_LEAVES); // devient le plus haut bloc : rejet comme feuillage
        assertFalse(SurfaceSpot.find(world, 8, 8).isPresent());
    }

    @Test
    void groundBelowSeaLevelIsRefused() {
        WorldMock low = new WorldMock(Material.GRASS_BLOCK, 40);
        low.setName("low");
        server.addWorld(low);
        assertFalse(SurfaceSpot.find(low, 8, 8).isPresent(), "fond de cuvette/ravin sous le niveau de la mer");
    }

    // ---- targetedChunkLoads ----------------------------------------------------------------------

    @Test
    void unloadedChunksAreLoadedOnDemandAsynchronouslyWithoutGenerationAndNeverKept() {
        Location waypoint = new Location(world, 1000.5, ground + 1, 1000.5);
        Optional<Location> spot = CommunityBossService.targetedChunkLoads()
                .find(world, waypoint, l -> true, new SplittableRandom(7), true).join();

        assertTrue(spot.isPresent(), "un emplacement est trouvé alors qu'aucun joueur n'est là");
        assertFalse(world.asyncLoads.isEmpty(), "chargement ponctuel asynchrone");
        assertTrue(world.asyncLoads.stream().allMatch(l -> l[2] == 0), "jamais de génération de terrain neuf");
        double distance = Math.hypot(spot.get().getX() - waypoint.getX(), spot.get().getZ() - waypoint.getZ());
        assertTrue(distance >= CommunityBossService.MIN_DISTANCE_FROM_PLAYER - 1
                && distance <= CommunityBossService.MAX_DISTANCE_FROM_PLAYER + 1, "distance " + distance);
        assertEquals(0, world.retained, "aucun ticket ni chargement forcé permanent");
    }

    @Test
    void chunkLoadsAreBoundedAndUngeneratedChunksAreSkipped() {
        world.generated = false; // chunks jamais générés : écartés, rien n'est créé
        Optional<Location> spot = CommunityBossService.targetedChunkLoads()
                .find(world, new Location(world, 5000.5, 70, 5000.5), l -> true, new SplittableRandom(3), true)
                .join();
        assertTrue(spot.isEmpty());
        assertTrue(world.asyncLoads.size() <= CommunityBossService.MAX_CHUNK_LOADS_PER_ANCHOR,
                "au plus " + CommunityBossService.MAX_CHUNK_LOADS_PER_ANCHOR + " chargements : "
                        + world.asyncLoads.size());
    }

    @Test
    void placementRulesStillApplyAfterTheChunkIsLoaded() {
        Optional<Location> spot = CommunityBossService.targetedChunkLoads()
                .find(world, new Location(world, 0.5, 70, 0.5), l -> false, new SplittableRandom(5), true).join();
        assertTrue(spot.isEmpty(), "zone protégée / ville / claim refusés par les règles de placement");
    }

    @Test
    void withoutSurfaceOnlyTheOriginalRuleIsUsed() {
        set(8, ground, 8, Material.OAK_PLANKS); // plancher : refusé en surface stricte…
        world.getChunkAt(0, 0);
        assertFalse(CommunityBossService.evaluate(world, 8, 8, true, l -> true).isPresent());
        assertTrue(CommunityBossService.evaluate(world, 8, 8, false, l -> true).isPresent(),
                "… accepté par la règle d'origine (plus haut bloc praticable)");
    }
}
