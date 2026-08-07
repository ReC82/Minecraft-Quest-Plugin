package com.lodygames.rpgquest.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.DisplayMode;
import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.progression.model.AwardOutcome;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.progression.model.XpGrantResult;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class ProgressionServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private ProgressionRepository repository;
    private PlayerProfileRepository profiles;
    private AtomicLong clock;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new ProgressionRepository(database);
        profiles = new PlayerProfileRepository(database);

        clock = new AtomicLong(0L);
    }

    /** player_skills/xp_grants référencent player_profiles (clé étrangère, PRAGMA foreign_keys=ON). */
    private UUID newPlayer() throws Exception {
        UUID uuid = UUID.randomUUID();
        profiles.findOrCreate(uuid, "player-" + uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return uuid;
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private ProgressionConfig config(int maxLevel, double globalMirrorRatio, int maxGrantsPerMinute) {
        return new ProgressionConfig(
                100L, 1.15, maxLevel, globalMirrorRatio, maxGrantsPerMinute, DisplayMode.OFF, true, 50, 15, 5, 4, 10, 100);
    }

    private ProgressionService newService(ProgressionConfig cfg) {
        return new ProgressionService(plugin, repository, () -> cfg, plugin.getSLF4JLogger(), clock::get);
    }

    // ---- XP négative refusée ---------------------------------------------------------------

    @Test
    void negativeOrZeroAmountIsRejectedWithoutTouchingTheDatabase() throws Exception {
        ProgressionService service = newService(config(100, 0.5, 1000));
        UUID player = newPlayer();

        XpGrantResult negative = service.awardXp(player, SkillType.COMBAT, -5L, "test", "event-1")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        XpGrantResult zero = service.awardXp(player, SkillType.COMBAT, 0L, "test", "event-2")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(AwardOutcome.REJECTED_INVALID_AMOUNT, negative.outcome());
        assertEquals(AwardOutcome.REJECTED_INVALID_AMOUNT, zero.outcome());
        assertEquals(0L, service.totalXp(player, SkillType.COMBAT));
    }

    // ---- événement dupliqué ------------------------------------------------------------------

    @Test
    void awardingTheSameEventIdTwiceOnlyGrantsOnce() throws Exception {
        ProgressionService service = newService(config(100, 0.0, 1000));
        UUID player = newPlayer();

        XpGrantResult first = service.awardXp(player, SkillType.MINING, 50L, "mining", "same-event")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        XpGrantResult second = service.awardXp(player, SkillType.MINING, 50L, "mining", "same-event")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(AwardOutcome.GRANTED, first.outcome());
        assertEquals(AwardOutcome.DUPLICATE, second.outcome());
        assertEquals(50L, service.totalXp(player, SkillType.MINING), "la seconde tentative ne doit rien ajouter");
    }

    // ---- montée de plusieurs niveaux -----------------------------------------------------

    @Test
    void aLargeGrantCanCrossMultipleLevelsAtOnce() throws Exception {
        ProgressionService service = newService(config(10, 0.0, 1000));
        UUID player = newPlayer();

        XpGrantResult result = service.awardXp(player, SkillType.COMBAT, 1000L, "test", "big-grant")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(AwardOutcome.GRANTED, result.outcome());
        assertTrue(result.leveledUp());
        assertTrue(result.levelsGained() > 1, "un octroi massif doit franchir plusieurs niveaux d'un coup");
        assertEquals(1, result.previousLevel());
    }

    // ---- valeurs maximales --------------------------------------------------------------------

    @Test
    void xpIsClampedAtTheMaxLevelThreshold() throws Exception {
        ProgressionService service = newService(config(3, 0.0, 1000)); // maxTotalXp = 100 + 115 = 215
        UUID player = newPlayer();

        XpGrantResult result = service.awardXp(player, SkillType.COMBAT, 10_000L, "test", "overflow")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(215L, result.newTotalXp(), "l'XP totale ne doit jamais dépasser le seuil du niveau maximal");
        assertEquals(3, result.newLevel());

        // Un octroi supplémentaire (id d'événement différent) ne doit plus rien ajouter non plus.
        XpGrantResult again = service.awardXp(player, SkillType.COMBAT, 10_000L, "test", "overflow-2")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(215L, again.newTotalXp());
        assertEquals(0L, again.amountGranted());
    }

    // ---- reconnexion --------------------------------------------------------------------------

    @Test
    void xpSurvivesUnloadAndReloadLikeADisconnectReconnectCycle() throws Exception {
        ProgressionService service = newService(config(100, 0.0, 1000));
        UUID player = newPlayer();

        service.awardXp(player, SkillType.FISHING, 42L, "test", "before-disconnect")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(42L, service.totalXp(player, SkillType.FISHING));

        service.unloadForPlayer(player); // simule une déconnexion
        assertEquals(0L, service.totalXp(player, SkillType.FISHING), "sans cache, la lecture retombe sur la valeur par défaut");

        service.loadForPlayer(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // simule une reconnexion
        assertEquals(42L, service.totalXp(player, SkillType.FISHING), "l'XP persistée doit être restaurée après reconnexion");
    }

    // ---- mirroir GLOBAL -----------------------------------------------------------------------

    @Test
    void awardingASpecificSkillMirrorsAPortionToGlobal() throws Exception {
        ProgressionService service = newService(config(100, 0.5, 1000));
        UUID player = newPlayer();

        service.awardXp(player, SkillType.COMBAT, 100L, "test", "combat-event")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(100L, service.totalXp(player, SkillType.COMBAT));
        assertEquals(50L, service.totalXp(player, SkillType.GLOBAL), "50% de mirroir configuré");
    }

    @Test
    void awardingGlobalDirectlyNeverMirrorsAgain() throws Exception {
        ProgressionService service = newService(config(100, 0.5, 1000));
        UUID player = newPlayer();

        service.awardXp(player, SkillType.GLOBAL, 100L, "test", "global-event")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(100L, service.totalXp(player, SkillType.GLOBAL));
    }

    // ---- anti-farm (répétition excessive) ------------------------------------------------

    @Test
    void tooManyGrantsWithinTheWindowAreThrottled() throws Exception {
        ProgressionService service = newService(config(100, 0.0, 2)); // 2 octrois/minute max
        UUID player = newPlayer();

        XpGrantResult first = service.awardXp(player, SkillType.MINING, 1L, "t", "e1").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        XpGrantResult second = service.awardXp(player, SkillType.MINING, 1L, "t", "e2").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        XpGrantResult third = service.awardXp(player, SkillType.MINING, 1L, "t", "e3").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(AwardOutcome.GRANTED, first.outcome());
        assertEquals(AwardOutcome.GRANTED, second.outcome());
        assertEquals(AwardOutcome.THROTTLED, third.outcome());

        clock.addAndGet(61_000L); // fenêtre suivante
        XpGrantResult afterWindow = service.awardXp(player, SkillType.MINING, 1L, "t", "e4").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AwardOutcome.GRANTED, afterWindow.outcome());
    }

    // ---- hook de déblocage --------------------------------------------------------------------

    @Test
    void hasLevelReflectsTheCurrentLevelThreshold() throws Exception {
        ProgressionService service = newService(config(100, 0.0, 1000));
        UUID player = newPlayer();

        assertTrue(service.hasLevel(player, SkillType.COMBAT, 1), "niveau 1 par défaut, sans aucune XP");
        assertTrue(!service.hasLevel(player, SkillType.COMBAT, 2));

        service.awardXp(player, SkillType.COMBAT, 500L, "test", "level-up").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(service.hasLevel(player, SkillType.COMBAT, service.level(player, SkillType.COMBAT)));
    }
}
