package com.lodygames.rpgquest.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.DisplayMode;
import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.progression.model.SkillType;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Issue #183 — la récompense de quête EXPERIENCE devient de l'XP RPG, une seule fois par complétion. */
class QuestExperienceRewardsTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private ProgressionService progression;
    private ProgressionRepository repository;
    private PlayerMock player;
    private final AtomicBoolean keepVanillaXp = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        ServerMock server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new ProgressionRepository(database);
        progression = new ProgressionService(plugin, repository, this::config, plugin.getSLF4JLogger());
        progression.start();

        player = server.addPlayer();
        player.setLevel(0);
        new PlayerProfileRepository(database).findOrCreate(player.getUniqueId(), player.getName())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progression.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        progression.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private ProgressionConfig config() {
        return new ProgressionConfig(100L, 1.15, 100, 0.0, 1000, DisplayMode.OFF, keepVanillaXp.get(),
                50, 15, 5, 4, 10, 100, 0);
    }

    private void awaitDatabaseIdle() throws Exception {
        database.execute(connection -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void experienceRewardBecomesRpgXpOnceAndSurvivesReload() throws Exception {
        QuestExperienceRewards rewards = new QuestExperienceRewards(progression, this::config);

        assertTrue(rewards.grant(player, 40, "completion-1#0"), "le message de remise doit annoncer de l'XP RPG");
        rewards.grant(player, 40, "completion-1#0");
        awaitDatabaseIdle();

        assertEquals(40, progression.totalXp(player.getUniqueId(), SkillType.GLOBAL),
                "une remise rejouée avec le même jeton ne paie pas deux fois");
        assertEquals(0, player.getLevel(), "aucun niveau Minecraft gagné");

        // Reconnexion / redémarrage : relue depuis la base, sans perte ni double gain.
        progression.unloadForPlayer(player.getUniqueId());
        progression.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(40, progression.totalXp(player.getUniqueId(), SkillType.GLOBAL));
    }

    @Test
    void keepVanillaXpRestoresTheVanillaReward() throws Exception {
        keepVanillaXp.set(true);
        QuestExperienceRewards rewards = new QuestExperienceRewards(progression, this::config);

        assertFalse(rewards.grant(player, 40, "completion-2#0"));
        awaitDatabaseIdle();

        assertEquals(40, player.getTotalExperience());
        assertEquals(0, progression.totalXp(player.getUniqueId(), SkillType.GLOBAL));
    }
}
