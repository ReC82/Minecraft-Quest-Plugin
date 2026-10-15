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
                plugin.getSLF4JLogger(), new MobSpawnSettingsStore(tempDir.resolve("mobs"), plugin.getSLF4JLogger()));
        bypass = new AdminBypassMode(() -> GameSettings.DEFAULTS);
        listener = new ActivityListener(plugin, new ActivityLogRepository(database), mobs, bypass, plugin.getSLF4JLogger());

        killer = server.addPlayer();
        killer.setGameMode(GameMode.SURVIVAL);
    }

    @AfterEach
    void tearDown() {
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
}
