package com.lodygames.rpgquest.claim.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.config.OfflineGrowthConfig;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.slf4j.LoggerFactory;

/**
 * Issue #776 : cycle déchargement → rechargement d'un chunk de claim (voir {@link OfflineGrowthService}) —
 * marque dans le PDC, idempotence, plafond, mondes et claims ignorés, étalement et coût.
 */
class OfflineGrowthServiceTest {

    private static final long ONE_HOUR = 72_000L;

    private ServerMock server;
    private Plugin plugin;
    private WorldMock claims;
    private WorldMock other;
    /** Claim couvrant exactement le chunk (0, 0). */
    private final Claim farm = new Claim("farm", UUID.randomUUID(), "claims", 0, 0, 0, 15, 100, 15, Set.of(),
            ClaimFlags.defaults());
    private OfflineGrowthConfig growth = OfflineGrowthConfig.DEFAULTS;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        claims = world("claims");
        other = world("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private WorldMock world(String name) {
        WorldMock world = new WorldMock(Material.AIR, 0);
        world.setName(name);
        server.addWorld(world);
        return world;
    }

    private OfflineGrowthService service() {
        ClaimConfig config = new ClaimConfig(64, 384, 3, 16, "claims", true, 64, 2000, 100, growth);
        return new OfflineGrowthService(plugin, () -> config,
                world -> world.equals("claims") ? List.of(farm) : List.of(),
                new PlantGrowthSimulator(new Random(7), server.getPluginManager()),
                LoggerFactory.getLogger(OfflineGrowthServiceTest.class));
    }

    private Block wheat(WorldMock world, int x, int z) {
        Block soil = world.getBlockAt(x, 10, z);
        soil.setType(Material.FARMLAND);
        Farmland data = (Farmland) soil.getBlockData();
        data.setMoisture(data.getMaximumMoisture());
        soil.setBlockData(data);
        BlockMock plant = (BlockMock) world.getBlockAt(x, 11, z);
        plant.setType(Material.WHEAT);
        plant.setLightFromSky((byte) 15);
        return plant;
    }

    private static int age(Block block) {
        return ((Ageable) block.getBlockData()).getAge();
    }

    private void unloadAndReload(OfflineGrowthService service, WorldMock world, long absence) {
        Chunk chunk = world.getChunkAt(0, 0);
        service.onChunkUnload(new ChunkUnloadEvent(chunk));
        world.setGameTime(world.getGameTime() + absence);
        service.onChunkLoad(new ChunkLoadEvent(chunk, false));
    }

    private void drain(OfflineGrowthService service) {
        for (int tick = 0; tick < 100 && service.pendingChunks() > 0; tick++) {
            service.processBatch();
        }
    }

    @Test
    void cropsCatchUpOnReloadAndTheMarkIsConsumedOnce() {
        OfflineGrowthService service = service();
        Block plant = wheat(claims, 3, 3);

        unloadAndReload(service, claims, 24 * ONE_HOUR);
        assertNull(claims.getChunkAt(0, 0).getPersistentDataContainer()
                .get(new NamespacedKey(plugin, OfflineGrowthService.KEY_NAME), PersistentDataType.LONG));
        drain(service);
        assertEquals(7, age(plant));

        // Second chargement sans nouveau déchargement : rien à rattraper.
        plant.setBlockData(Material.WHEAT.createBlockData());
        service.onChunkLoad(new ChunkLoadEvent(claims.getChunkAt(0, 0), false));
        assertEquals(0, service.pendingChunks());
        assertEquals(0, age(plant));
    }

    @Test
    void markSurvivesARestartAndIsAppliedOnlyOnce() {
        Block plant = wheat(claims, 3, 3);
        Chunk chunk = claims.getChunkAt(0, 0);
        service().onChunkUnload(new ChunkUnloadEvent(chunk));
        claims.setGameTime(claims.getGameTime() + 24 * ONE_HOUR);

        OfflineGrowthService afterRestart = service();
        afterRestart.onChunkLoad(new ChunkLoadEvent(chunk, false));
        afterRestart.onChunkLoad(new ChunkLoadEvent(chunk, false));
        assertEquals(1, afterRestart.pendingChunks(), "un seul rattrapage pour une seule marque");
        drain(afterRestart);
        assertEquals(7, age(plant));
    }

    @Test
    void absenceIsCappedByMaxCatchUpMinutes() {
        growth = new OfflineGrowthConfig(true, List.of(), 10, 256);
        OfflineGrowthService service = service();
        Block plant = wheat(claims, 3, 3);

        unloadAndReload(service, claims, 30 * 24 * ONE_HOUR);
        drain(service);

        // 10 min = 8,79 random ticks ; champ isolé humide 1/7 → 1,26 étape en moyenne, jamais plus de 2.
        assertTrue(age(plant) <= 2, "âge " + age(plant) + " : la durée doit être plafonnée");
    }

    @Test
    void otherWorldsDisabledConfigAndChunksWithoutClaimAreIgnored() {
        Block elsewhere = wheat(other, 3, 3);
        OfflineGrowthService service = service();
        unloadAndReload(service, other, 24 * ONE_HOUR);
        assertFalse(other.getChunkAt(0, 0).getPersistentDataContainer()
                .has(new NamespacedKey(plugin, OfflineGrowthService.KEY_NAME)), "aucune marque hors des mondes concernés");

        Chunk noClaim = claims.getChunkAt(5, 5);
        service.onChunkUnload(new ChunkUnloadEvent(noClaim));
        assertFalse(noClaim.getPersistentDataContainer().has(new NamespacedKey(plugin, OfflineGrowthService.KEY_NAME)));
        drain(service);
        assertEquals(0, age(elsewhere));

        growth = new OfflineGrowthConfig(false, List.of(), 1440, 256);
        Block plant = wheat(claims, 3, 3);
        unloadAndReload(service(), claims, 24 * ONE_HOUR);
        assertEquals(0, age(plant), "désactivé : aucune croissance");
    }

    @Test
    void explicitWorldListReplacesTheClaimsWorld() {
        growth = new OfflineGrowthConfig(true, List.of("world"), 1440, 256);
        Block plant = wheat(claims, 3, 3);
        OfflineGrowthService service = service();
        unloadAndReload(service, claims, 24 * ONE_HOUR);
        drain(service);
        assertEquals(0, age(plant), "claims absent de la liste explicite");
    }

    @Test
    void randomTickSpeedZeroFreezesGrowthLikeVanilla() {
        claims.setGameRule(org.bukkit.GameRules.RANDOM_TICK_SPEED, 0);
        Block plant = wheat(claims, 3, 3);
        OfflineGrowthService service = service();
        unloadAndReload(service, claims, 24 * ONE_HOUR);
        drain(service);
        assertEquals(0, age(plant));
    }

    @Test
    void chunkUnloadedBeforeItsTurnIsDroppedWithoutDoubleGrowth() {
        Block plant = wheat(claims, 3, 3);
        OfflineGrowthService service = service();
        unloadAndReload(service, claims, 24 * ONE_HOUR);
        claims.getChunkAt(0, 0).unload();
        service.processBatch();
        assertEquals(0, service.pendingChunks());
        assertEquals(0, age(plant));
    }

    @Test
    void fullChunkFieldIsSpreadOverTicksWithMeasuredCostAndNoChunkLoaded() {
        growth = new OfflineGrowthConfig(true, List.of(), 1440, 64);
        OfflineGrowthService service = service();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                wheat(claims, x, z);
            }
        }
        // Un bloc non végétal au milieu du champ ne doit jamais changer.
        claims.getBlockAt(8, 11, 8).setType(Material.STONE);
        unloadAndReload(service, claims, 24 * ONE_HOUR);
        int loadedBefore = claims.getLoadedChunks().length; // le chunk (0, 0), rechargé par le « joueur »
        int ticks = 0;
        while (service.pendingChunks() > 0) {
            service.processBatch();
            ticks++;
        }

        assertTrue(ticks >= 4, "255 plantes à 64 par tick : au moins 4 ticks, mesuré " + ticks);
        assertEquals(Material.STONE, claims.getBlockAt(8, 11, 8).getType());
        assertEquals(7, age(claims.getBlockAt(0, 11, 0)));
        assertEquals(7, age(claims.getBlockAt(15, 11, 15)));
        assertEquals(loadedBefore, claims.getLoadedChunks().length, "aucun chunk chargé par le mécanisme");
        OfflineGrowthService.Stats stats = service.stats();
        assertEquals(1, stats.chunks());
        assertEquals(255, stats.plants());
        System.out.printf("[#776] coût mesuré (MockBukkit) : %d plantes, %d blocs, %d µs pour le chunk%n",
                stats.plants(), stats.blocksChanged(), stats.nanos() / 1000);
    }
}
