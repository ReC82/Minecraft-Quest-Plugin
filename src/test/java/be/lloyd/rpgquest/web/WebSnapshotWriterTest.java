package be.lloyd.rpgquest.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.config.WebExportConfig;
import be.lloyd.rpgquest.database.DatabaseManager;
import be.lloyd.rpgquest.database.ProgressionRepository;
import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import be.lloyd.rpgquest.progression.model.SkillType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Vérifie que la génération du snapshot (mission étape 21, point 4 : jamais
 * de blocage du thread principal) reste un no-op tant que
 * {@code web-export.enabled} est faux, produit un fichier JSON valide une
 * fois activé, et que l'arrêt du service ne lève jamais d'exception.
 */
class WebSnapshotWriterTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private ProgressionRepository progressionRepository;
    private YamlCustomItemRegistry customItemRegistry;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progressionRepository = new ProgressionRepository(database);

        // Pas d'appel à start() : aucun exemple bundlé nécessaire pour ce test.
        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.reload();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private WebExportConfig config(boolean enabled) {
        return new WebExportConfig(enabled, "web-export", 5, true, 5, List.of(SkillType.GLOBAL),
                List.of(new WebExportConfig.Announcement("Café ☕ 日本語 🎮", "corps")));
    }

    private Path snapshotFile() {
        return tempDir.resolve("web-export").resolve("snapshot.json");
    }

    @Test
    void disabledNeverWritesAFile() throws Exception {
        WebSnapshotWriter writer = new WebSnapshotWriter(
                plugin, tempDir, progressionRepository, customItemRegistry, () -> config(false), plugin.getSLF4JLogger());
        writer.start();
        writer.triggerNow();
        Thread.sleep(300);
        writer.stop();

        assertFalse(Files.exists(snapshotFile()));
    }

    @Test
    void enabledWritesAValidSnapshotContainingExpectedKeysAndUnicode() throws Exception {
        WebSnapshotWriter writer = new WebSnapshotWriter(
                plugin, tempDir, progressionRepository, customItemRegistry, () -> config(true), plugin.getSLF4JLogger());
        writer.start();
        writer.triggerNow();
        String content = awaitFile(snapshotFile());
        writer.stop();

        assertTrue(content.contains("\"generatedAt\""));
        assertTrue(content.contains("\"server\""));
        assertTrue(content.contains("\"online\":true"));
        assertTrue(content.contains("\"leaderboards\""));
        assertTrue(content.contains("\"GLOBAL\""));
        assertTrue(content.contains("\"catalog\""));
        assertTrue(content.contains("\"announcements\""));
        assertTrue(content.contains("Café ☕ 日本語 🎮"));
    }

    @Test
    void stopCancelsSchedulingAndShutsDownCleanly() {
        WebSnapshotWriter writer = new WebSnapshotWriter(
                plugin, tempDir, progressionRepository, customItemRegistry, () -> config(true), plugin.getSLF4JLogger());
        writer.start();
        assertDoesNotThrow(writer::stop);
    }

    private String awaitFile(Path target) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!Files.exists(target) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(target), "snapshot.json n'a jamais été écrit");
        return Files.readString(target, StandardCharsets.UTF_8);
    }
}
