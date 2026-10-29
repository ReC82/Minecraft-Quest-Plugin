package com.lodygames.rpgquest.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.ActivityLogRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.mob.MobSpawnSettingsStore;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.progression.listener.CombatXpListener;
import com.lodygames.rpgquest.settings.GameSettings;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #57 — le listener unique des événements : ce qui est journalisé, et surtout ce qui ne
 * l'est jamais (créatif, mode bypass admin, mob de spawner, victime joueur, rejeu).
 */
class ActivityListenerTest {

    private static final long TIMEOUT = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private DatabaseManager database;
    private AdminBypassMode bypass;
    private ActivityListener listener;
    private PlayerMock killer;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);

        ZoneRegistry zones = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        SpecialMobRegistry mobRegistry = new SpecialMobRegistry(tempDir.resolve("mobs"), plugin.getSLF4JLogger());
        mobRegistry.reload();
        SpecialMobService mobs = new SpecialMobService(plugin, mobRegistry, zones,
                new com.lodygames.rpgquest.item.YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger()),
                plugin.getSLF4JLogger(), new MobSpawnSettingsStore(tempDir.resolve("mobs"), plugin.getSLF4JLogger()), world -> true);
        bypass = new AdminBypassMode(() -> GameSettings.DEFAULTS);
        listener = new ActivityListener(plugin, new ActivityLogRepository(database), mobs, bypass, plugin.getSLF4JLogger());

        killer = server.addPlayer();
        killer.setGameMode(GameMode.SURVIVAL);
        tracker = new com.lodygames.rpgquest.progression.PlacedBlockTracker(plugin,
                new com.lodygames.rpgquest.database.PlacedBlockRepository(database), plugin.getSLF4JLogger());
        tracker.start();
        listener.usePlacedBlocks(tracker);
    }

    private com.lodygames.rpgquest.progression.PlacedBlockTracker tracker;

    /** Casse complète : capture avant l'XP de minage, effacement de la marque (comme MiningXpListener), confirmation. */
    private void breakBlock(org.bukkit.block.Block block, boolean cancelled) {
        org.bukkit.event.block.BlockBreakEvent event = new org.bukkit.event.block.BlockBreakEvent(block, killer);
        listener.beforeBreak(event);
        tracker.clearPlacement(block);
        event.setCancelled(cancelled);
        listener.onBreak(event);
    }

    @AfterEach
    void tearDown() {
        tracker.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private LivingEntity zombieKilledBy(org.bukkit.entity.Player player) {
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        zombie.setKiller(player);
        return zombie;
    }

    private void die(LivingEntity victim) {
        listener.onDeath(new EntityDeathEvent(victim,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), List.of()));
    }

    /** Les écritures passent par l'exécuteur mono-thread de la base : une tâche vide les attend toutes. */
    private List<String> journal() throws Exception {
        return database.execute(connection -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT action, target, player_uuid FROM stat_activity_log ORDER BY id");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(resultSet.getString("action") + ":" + resultSet.getString("target"));
                }
            }
            return rows;
        }).get(TIMEOUT, TimeUnit.SECONDS);
    }

    @Test
    void aSurvivalKillIsJournaledOnceWithItsEntityType() throws Exception {
        LivingEntity zombie = zombieKilledBy(killer);

        die(zombie);
        die(zombie); // événement rejoué : même entité, même clé

        assertEquals(List.of("ENTITY_KILL:ZOMBIE"), journal());
    }

    @Test
    void creativeAndSpectatorKillsNeverCount() throws Exception {
        killer.setGameMode(GameMode.CREATIVE);
        die(zombieKilledBy(killer));
        killer.setGameMode(GameMode.SPECTATOR);
        die(zombieKilledBy(killer));

        assertEquals(List.of(), journal());
    }

    @Test
    void theAdminBypassModeExcludesTheAdminFromCompetitions() throws Exception {
        killer.setOp(true);
        assertEquals(AdminBypassMode.Outcome.ENABLED, bypass.enable(killer));

        die(zombieKilledBy(killer));

        assertEquals(List.of(), journal());
    }

    @Test
    void spawnerMobsAndPlayerVictimsNeverCount() throws Exception {
        LivingEntity farmed = zombieKilledBy(killer);
        farmed.getPersistentDataContainer().set(CombatXpListener.spawnerTagKey(plugin), PersistentDataType.BYTE, (byte) 1);
        die(farmed);
        PlayerMock victim = server.addPlayer();
        victim.setKiller(killer);
        die(victim);

        assertEquals(List.of(), journal());
    }

    private void breed(org.bukkit.entity.LivingEntity breeder) {
        LivingEntity mother = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.COW);
        LivingEntity father = (LivingEntity) world.spawnEntity(new Location(world, 1, 64, 0), EntityType.COW);
        LivingEntity child = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 1), EntityType.COW);
        EntityBreedEvent event = new EntityBreedEvent(child, mother, father, breeder,
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.WHEAT), 1);
        listener.onBreed(event);
        listener.onBreed(event); // événement rejoué : même petit, même clé
    }

    @Test
    void anAnimalBredByASurvivalPlayerIsJournaledOnceWithItsType() throws Exception {
        breed(killer);

        assertEquals(List.of("ANIMAL_BRED:COW"), journal());
    }

    @Test
    void breedingWithoutAPlayerOrInCreativeOrBypassNeverCounts() throws Exception {
        breed(null);
        killer.setGameMode(GameMode.CREATIVE);
        breed(killer);
        killer.setGameMode(GameMode.SURVIVAL);
        killer.setOp(true);
        assertEquals(AdminBypassMode.Outcome.ENABLED, bypass.enable(killer));
        breed(killer);

        assertEquals(List.of(), journal());
    }

    @Test
    void aNaturalBlockIsJournaledButNeverAPlayerPlacedOne() throws Exception {
        org.bukkit.block.Block natural = world.getBlockAt(0, 64, 0);
        natural.setType(org.bukkit.Material.IRON_ORE);
        breakBlock(natural, false);

        org.bukkit.block.Block placed = world.getBlockAt(1, 64, 0);
        placed.setType(org.bukkit.Material.STONE);
        tracker.onPlace(new org.bukkit.event.block.BlockPlaceEvent(placed, placed.getState(), world.getBlockAt(1, 63, 0),
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE), killer, true,
                org.bukkit.inventory.EquipmentSlot.HAND));
        breakBlock(placed, false);

        assertEquals(List.of("BLOCK_BREAK:IRON_ORE"), journal());
    }

    @Test
    void aRefusedOrCreativeBreakAndAnUnripeCropNeverCount() throws Exception {
        org.bukkit.block.Block block = world.getBlockAt(0, 64, 0);
        block.setType(org.bukkit.Material.STONE);
        breakBlock(block, true);
        killer.setGameMode(GameMode.CREATIVE);
        breakBlock(block, false);
        killer.setGameMode(GameMode.SURVIVAL);
        org.bukkit.block.Block wheat = world.getBlockAt(2, 64, 0);
        wheat.setType(org.bukkit.Material.WHEAT);
        breakBlock(wheat, false);

        assertEquals(List.of(), journal());
    }

    @Test
    void aBreakSeenOnlyAfterCancellationIsIgnored() throws Exception {
        org.bukkit.block.Block block = world.getBlockAt(0, 64, 0);
        block.setType(org.bukkit.Material.STONE);
        // Une protection a annulé avant HIGH : beforeBreak n'est jamais appelé (ignoreCancelled).
        org.bukkit.event.block.BlockBreakEvent event = new org.bukkit.event.block.BlockBreakEvent(block, killer);
        event.setCancelled(true);
        listener.onBreak(event);

        assertEquals(List.of(), journal());
    }

    @Test
    void aQuestCompletionIsJournaledOnceAfterPersistenceAndNeverInCreative() throws Exception {
        org.bukkit.NamespacedKey quest = new org.bukkit.NamespacedKey("rpgquest", "crystal_hunt");
        Runnable persisted = listener.questCompleting(killer, quest, "token-1");
        assertEquals(List.of(), journal(), "rien n'est écrit avant l'enregistrement de la complétion");
        persisted.run();
        persisted.run();
        killer.setGameMode(GameMode.CREATIVE);
        assertEquals(null, listener.questCompleting(killer, quest, "token-2"));

        assertEquals(List.of("QUEST_COMPLETION:rpgquest:crystal_hunt"), journal());
    }
}
