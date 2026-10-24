package com.lodygames.rpgquest.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.WebExportConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.player.PublicProfilePolicy;
import com.lodygames.rpgquest.progression.model.SkillType;
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
import org.mockbukkit.mockbukkit.entity.PlayerMock;

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
                List.of(new WebExportConfig.Announcement("Café ☕ 日本語 🎮", "corps")),
                PublicProfilePolicy.Visibility.PRIVATE);
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
    void connectedPlayersListOnlyNamesPlayersWhoMadeTheirProfilePublic() throws Exception {
        PlayerMock shy = server.addPlayer("Timide");
        PlayerMock proud = server.addPlayer("Fier");
        PlayerVariableRepository variables = new PlayerVariableRepository(database);
        new PlayerProfileRepository(database).findOrCreate(proud.getUniqueId(), "Fier").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        variables.set(proud.getUniqueId(), PublicProfilePolicy.HIDDEN_KEY, PublicProfilePolicy.PUBLIC_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        WebSnapshotWriter writer = new WebSnapshotWriter(
                plugin, tempDir, progressionRepository, customItemRegistry, () -> config(true), plugin.getSLF4JLogger());
        writer.setVariables(variables);
        writer.start();
        writer.triggerNow();
        String content = awaitFile(snapshotFile());
        writer.stop();

        assertTrue(content.contains("\"players\":[\"Fier\"]"), content);
        assertFalse(content.contains(shy.getName()), "un joueur sans accord n'est jamais nommé");
    }

    @Test
    void withoutPreferencesNoConnectedPlayerIsNamed() throws Exception {
        server.addPlayer("Anonyme");
        WebSnapshotWriter writer = new WebSnapshotWriter(
                plugin, tempDir, progressionRepository, customItemRegistry, () -> config(true), plugin.getSLF4JLogger());
        writer.start();
        writer.triggerNow();
        String content = awaitFile(snapshotFile());
        writer.stop();

        assertTrue(content.contains("\"players\":[]"), content);
    }

    @Test
    void stopCancelsSchedulingAndShutsDownCleanly() {
        WebSnapshotWriter writer = new WebSnapshotWriter(
                plugin, tempDir, progressionRepository, customItemRegistry, () -> config(true), plugin.getSLF4JLogger());
        writer.start();
        assertDoesNotThrow(writer::stop);
    }

    @Test
    void eachSnapshotIsPushedNumberedAndShutdownStaysLocal() throws Exception {
        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer portal = portal(received, new java.util.concurrent.atomic.AtomicInteger(200));
        try {
            WebSnapshotWriter writer = new WebSnapshotWriter(
                    plugin, tempDir, progressionRepository, customItemRegistry, () -> config(true), plugin.getSLF4JLogger());
            writer.setPortalPush(pushClient(portal));
            writer.start();
            writer.triggerNow();
            awaitRequests(received, 1);
            assertEquals(1, received.size(), "instantané envoyé au portail");
            assertEquals(Files.readString(snapshotFile(), StandardCharsets.UTF_8), received.get(0),
                    "le portail reçoit exactement le fichier local");
            assertTrue(received.get(0).contains("\"sequence\":1"), received.get(0));
            assertTrue(received.get(0).matches("(?s).*\"bootId\":\"boot-[0-9a-f]{24}\".*"), received.get(0));
            assertTrue(received.get(0).contains("\"export\":{\"intervalSeconds\":"), received.get(0));

            awaitIdle(writer);
            writer.triggerNow();
            awaitRequests(received, 2);
            assertTrue(received.get(1).contains("\"sequence\":2"), "numéro croissant : " + received.get(1));

            writer.stop();

            assertEquals(2, received.size(), "l'arrêt est annoncé par le relevé d'état, pas par un instantané vide");
            assertTrue(Files.readString(snapshotFile()).contains("\"online\":false"), "arrêt écrit localement");
        } finally {
            portal.stop(0);
        }
    }

    @Test
    void transientPortalErrorIsRetriedWithTheSameBodyButARefusalIsNot() throws Exception {
        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger status = new java.util.concurrent.atomic.AtomicInteger(503);
        com.sun.net.httpserver.HttpServer portal = portal(received, status);
        try {
            WebSnapshotWriter writer = new WebSnapshotWriter(
                    plugin, tempDir, progressionRepository, customItemRegistry, () -> config(true), plugin.getSLF4JLogger());
            writer.setRetryDelaysMillis(10, 10);
            com.lodygames.rpgquest.web.push.SnapshotPushClient client = pushClient(portal);

            assertEquals(com.lodygames.rpgquest.web.push.SnapshotPushClient.Outcome.UNAVAILABLE,
                    writer.pushWithRetries(client, "{\"sequence\":7}"));
            assertEquals(3, received.size(), "un envoi + deux nouveaux essais");
            assertTrue(received.stream().allMatch("{\"sequence\":7}"::equals), "corps identique : doublon côté portail");

            received.clear();
            status.set(401);
            assertEquals(com.lodygames.rpgquest.web.push.SnapshotPushClient.Outcome.REJECTED,
                    writer.pushWithRetries(client, "{}"));
            assertEquals(1, received.size(), "jeton refusé : aucun nouvel essai");
        } finally {
            portal.stop(0);
        }
    }

    private static com.sun.net.httpserver.HttpServer portal(List<String> received,
                                                           java.util.concurrent.atomic.AtomicInteger status)
            throws java.io.IOException {
        com.sun.net.httpserver.HttpServer portal =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        portal.createContext("/api/snapshot", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        portal.start();
        return portal;
    }

    private com.lodygames.rpgquest.web.push.SnapshotPushClient pushClient(com.sun.net.httpserver.HttpServer portal) {
        return new com.lodygames.rpgquest.web.push.SnapshotPushClient(
                new com.lodygames.rpgquest.web.push.SnapshotPushConfig(true,
                        "http://127.0.0.1:" + portal.getAddress().getPort(), "jeton", 1000, 3000),
                plugin.getSLF4JLogger());
    }

    private static void awaitIdle(WebSnapshotWriter writer) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (writer.isWriting() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private static void awaitRequests(List<String> received, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (received.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void shutdownWithoutAnyExportThisRunAnnouncesNothing() throws Exception {
        WebSnapshotWriter writer = new WebSnapshotWriter(
                plugin, tempDir, progressionRepository, customItemRegistry, () -> config(true), plugin.getSLF4JLogger());
        writer.start();
        writer.stop();

        assertFalse(Files.exists(snapshotFile()));
    }

    @Test
    void shutdownSnapshotCarriesNoPlayerData() {
        String json = Json.write(WebSnapshotWriter.shutdownSnapshot(20, java.time.Instant.parse("2026-10-16T00:00:00Z")));

        assertTrue(json.contains("\"online\":false"));
        assertTrue(json.contains("\"schemaVersion\":" + WebSnapshotWriter.SCHEMA_VERSION));
        assertTrue(json.contains("\"players\":[]"));
        assertFalse(json.contains("profiles"));
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
