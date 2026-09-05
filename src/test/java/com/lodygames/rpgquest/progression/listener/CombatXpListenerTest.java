package com.lodygames.rpgquest.progression.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.DisplayMode;
import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Couvre le test automatique étape 19 « mob de spawner » (mission point 7, anti-farm). */
class CombatXpListenerTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private DatabaseManager database;
    private ProgressionService progression;
    private CombatXpListener listener;
    private PlayerMock killer;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ProgressionRepository repository = new ProgressionRepository(database);
        PlayerProfileRepository profiles = new PlayerProfileRepository(database);

        ProgressionConfig config = new ProgressionConfig(
                100L, 1.15, 100, 0.0, 1000, DisplayMode.OFF, true, 50, 15, 5, 4, 10, 100);
        progression = new ProgressionService(plugin, repository, () -> config, plugin.getSLF4JLogger());
        progression.start();

        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        SpecialMobRegistry mobRegistry = new SpecialMobRegistry(tempDir.resolve("mobs"), plugin.getSLF4JLogger());
        mobRegistry.reload(); // pas de start() : aucun exemple bundlé nécessaire ici.
        SpecialMobService mobService = new SpecialMobService(
                plugin, mobRegistry, zoneRegistry, new com.lodygames.rpgquest.item.YamlCustomItemRegistry(
                        tempDir.resolve("items"), plugin.getSLF4JLogger()), plugin.getSLF4JLogger());

        listener = new CombatXpListener(plugin, progression, mobService, () -> config);

        killer = server.addPlayer();
        killer.setLevel(0);
        profiles.findOrCreate(killer.getUniqueId(), killer.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progression.loadForPlayer(killer.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        progression.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private LivingEntity spawnZombie() {
        return (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
    }

    private EntityDeathEvent deathEvent(LivingEntity victim) {
        return new EntityDeathEvent(victim,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), java.util.List.of());
    }

    /**
     * L'octroi d'XP déclenché par le listener est asynchrone (exécuteur JDBC dédié) : soumettre une
     * tâche factice après coup et l'attendre garantit, grâce au FIFO d'un exécuteur mono-thread, que
     * l'octroi précédent est terminé (même patron que {@code DatabaseManager#initialize} le documente).
     */
    private void awaitDatabaseIdle() throws Exception {
        database.execute(connection -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void killingANaturallySpawnedMobGrantsCombatXp() throws Exception {
        LivingEntity zombie = spawnZombie();
        zombie.setKiller(killer);

        listener.onDeath(deathEvent(zombie));
        awaitDatabaseIdle();

        assertTrue(progression.totalXp(killer.getUniqueId(), SkillType.COMBAT) > 0,
                "un mob spawné naturellement (aucun tag spawner posé) doit récompenser le tueur");
    }

    @Test
    void killingASpawnerSpawnedMobGrantsNoCombatXp() throws Exception {
        LivingEntity zombie = spawnZombie();
        CreatureSpawnEvent spawnEvent = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.SPAWNER);
        listener.onSpawn(spawnEvent); // pose le tag PDC "spawner", comme le ferait le vrai événement de spawn.

        zombie.setKiller(killer);
        listener.onDeath(deathEvent(zombie));
        awaitDatabaseIdle();

        assertEquals(0L, progression.totalXp(killer.getUniqueId(), SkillType.COMBAT),
                "un mob issu d'un spawner ne doit jamais récompenser le tueur (anti-farm)");
    }

    @Test
    void deathWithoutAPlayerKillerGrantsNoCombatXp() throws Exception {
        LivingEntity zombie = spawnZombie();
        // Aucun killer défini (mort environnementale/naturelle).

        listener.onDeath(deathEvent(zombie));
        awaitDatabaseIdle();

        assertEquals(0L, progression.totalXp(killer.getUniqueId(), SkillType.COMBAT));
    }
}
