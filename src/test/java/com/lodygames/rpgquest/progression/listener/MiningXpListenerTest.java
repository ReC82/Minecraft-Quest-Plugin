package com.lodygames.rpgquest.progression.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.DisplayMode;
import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlacedBlockRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.progression.PlacedBlockTracker;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Couvre le test automatique étape 19 « bloc placé » (mission point 7, anti-farm). */
class MiningXpListenerTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private DatabaseManager database;
    private ProgressionService progression;
    private PlacedBlockTracker tracker;
    private MiningXpListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ProgressionRepository progressionRepository = new ProgressionRepository(database);
        PlayerProfileRepository profiles = new PlayerProfileRepository(database);
        PlacedBlockRepository placedBlockRepository = new PlacedBlockRepository(database);

        ProgressionConfig config = new ProgressionConfig(
                100L, 1.15, 100, 0.0, 1000, DisplayMode.OFF, true, 50, 15, 5, 4, 10, 100);
        progression = new ProgressionService(plugin, progressionRepository, () -> config, plugin.getSLF4JLogger());
        progression.start();

        tracker = new PlacedBlockTracker(plugin, placedBlockRepository, plugin.getSLF4JLogger());
        tracker.start();

        listener = new MiningXpListener(progression, tracker, () -> config);

        player = server.addPlayer();
        profiles.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progression.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        tracker.stop();
        progression.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private void awaitDatabaseIdle() throws Exception {
        database.execute(connection -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void placeBlock(Block block) {
        tracker.onPlace(new BlockPlaceEvent(block, block.getState(), world.getBlockAt(0, 63, 0),
                new ItemStack(Material.STONE), player, true, org.bukkit.inventory.EquipmentSlot.HAND));
    }

    @Test
    void breakingANaturalBlockGrantsMiningXp() throws Exception {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);

        listener.onBreak(new BlockBreakEvent(block, player));
        awaitDatabaseIdle();

        assertTrue(progression.totalXp(player.getUniqueId(), SkillType.MINING) > 0,
                "un bloc naturel (jamais posé par un joueur) doit récompenser le mineur");
    }

    @Test
    void breakingAPlayerPlacedBlockGrantsNoMiningXp() throws Exception {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        placeBlock(block);

        listener.onBreak(new BlockBreakEvent(block, player));
        awaitDatabaseIdle();

        assertEquals(0L, progression.totalXp(player.getUniqueId(), SkillType.MINING),
                "un bloc posé par un joueur ne doit jamais récompenser le minage (anti-farm)");
    }

    @Test
    void aRepositionedBlockCanBeMinedAgainAfterBeingPlacedOnceMore() throws Exception {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);

        listener.onBreak(new BlockBreakEvent(block, player)); // naturel : récompensé.
        awaitDatabaseIdle();
        long afterFirstBreak = progression.totalXp(player.getUniqueId(), SkillType.MINING);
        assertTrue(afterFirstBreak > 0);

        // Le joueur pose puis re-casse un nouveau bloc à la même position : pas de récompense cette fois.
        block.setType(Material.STONE);
        placeBlock(block);
        listener.onBreak(new BlockBreakEvent(block, player));
        awaitDatabaseIdle();

        assertEquals(afterFirstBreak, progression.totalXp(player.getUniqueId(), SkillType.MINING),
                "reposer puis re-casser ne doit ajouter aucune XP supplémentaire");
    }
}
