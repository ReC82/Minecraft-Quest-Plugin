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

/** Issue #532 — {@code required-level} lit le niveau RPG GLOBAL, ou vanilla si l'XP vanilla est conservée. */
class RequiredLevelRuleTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private ProgressionService progression;
    private PlayerMock player;
    private RequiredLevelRule rule;
    private final AtomicBoolean keepVanillaXp = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        ServerMock server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progression = new ProgressionService(plugin, new ProgressionRepository(database), this::config,
                plugin.getSLF4JLogger());
        progression.start();

        player = server.addPlayer();
        player.setLevel(0);
        new PlayerProfileRepository(database).findOrCreate(player.getUniqueId(), player.getName())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progression.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        rule = RequiredLevelRule.configured(progression, this::config);
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

    private void setRpgLevel(int level) throws Exception {
        progression.setTotalXp(player.getUniqueId(), SkillType.GLOBAL, progression.curve().totalXpForLevel(level))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void noRequirementAlwaysPasses() {
        assertTrue(rule.meets(player, null));
    }

    @Test
    void rpgLevelDecidesWhileVanillaLevelStaysAtZero() throws Exception {
        setRpgLevel(1);
        assertEquals(1, rule.levelOf(player));
        assertFalse(rule.meets(player, 2), "niveau RPG 1 < 2");

        setRpgLevel(2);
        assertTrue(rule.meets(player, 2), "niveau RPG 2 suffit malgré le niveau vanilla 0");
    }

    @Test
    void vanillaXpProgressionKeepsTheVanillaLevel() throws Exception {
        keepVanillaXp.set(true);
        setRpgLevel(5);
        player.setLevel(1);
        assertFalse(rule.meets(player, 2), "mode vanilla : le niveau RPG est ignoré");

        player.setLevel(2);
        assertTrue(rule.meets(player, 2));
    }

    @Test
    void unwiredDefaultIsTheVanillaLevel() {
        player.setLevel(3);
        assertEquals(3, RequiredLevelRule.VANILLA.levelOf(player));
    }
}
