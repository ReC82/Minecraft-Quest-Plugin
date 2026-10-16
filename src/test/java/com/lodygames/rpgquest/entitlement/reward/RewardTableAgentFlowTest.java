package com.lodygames.rpgquest.entitlement.reward;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.RewardEntitlementRepository;
import com.lodygames.rpgquest.database.StatisticsRepository;
import com.lodygames.rpgquest.ops.ServerLogBuffer;
import com.lodygames.rpgquest.ops.ServerOpsService;
import com.lodygames.rpgquest.web.agent.AgentAction;
import com.lodygames.rpgquest.web.agent.AgentActionExecutor;
import com.lodygames.rpgquest.web.agent.AgentActionOutcome;
import com.lodygames.rpgquest.web.agent.BukkitAgentActions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #177 tranche 2 — chemin réel PlugAdmin → agent : paramètres tels que le panel les envoie,
 * {@link AgentActionExecutor}, {@link BukkitAgentActions}, service, fichier, rechargement.
 */
class RewardTableAgentFlowTest {

    private static final String CATALOG = """
            # catalogue de test
            enabled: true
            rewards:
              gem: {label: "Gemme rare", items: [{item: DIAMOND, amount: 2}]}
              book: {label: "Livre", items: [{item: ENCHANTED_BOOK, enchantments: {sharpness: 3}}]}
            """;

    @TempDir
    Path tempDir;
    private DatabaseManager database;
    private RewardEntitlementService service;
    private AgentActionExecutor executor;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(10, TimeUnit.SECONDS);
        file = tempDir.resolve("entitlements.yml");
        Files.writeString(file, CATALOG);
        service = new RewardEntitlementService(null, file, new RewardEntitlementRepository(database),
                () -> null, key -> false, new StatisticsRepository(database), null,
                (line, log) -> Optional.empty(), NOPLogger.NOP_LOGGER, Clock.systemUTC(), Runnable::run);
        service.reloadNow();
        BukkitAgentActions actions = new BukkitAgentActions(plugin, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, () -> "wild",
                () -> new com.lodygames.rpgquest.config.HubConfig("world_hub"),
                new ServerOpsService(plugin, new ServerLogBuffer(50), java.util.Optional::empty),
                null, null, java.util.Optional::empty, null, null, null, null, null, null);
        actions.setRewardEntitlements(service);
        executor = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private AgentActionOutcome run(String type, Map<String, String> params) throws Exception {
        return executor.execute(new AgentAction("t-" + type, type, params)).get(10, TimeUnit.SECONDS);
    }

    @Test
    void createDuplicateToggleAndReadBackThroughTheAgent() throws Exception {
        AgentActionOutcome created = run("reward.table.save", Map.of("table_id", "coffre_du_marais",
                "label", "Coffre du marais", "mode", "single", "enabled", "true", "create", "true",
                "entries", "gem=gem@30;book=book@12.5"));
        assertEquals(AgentActionOutcome.SUCCESS, created.status(), created.message());
        RewardTable table = service.catalog().findTable("coffre_du_marais").orElseThrow();
        assertEquals(List.of(new RewardTable.Entry("gem", "gem", 3000), new RewardTable.Entry("book", "book", 1250)),
                table.entries());
        assertTrue(Files.readString(file).contains("# catalogue de test"), "commentaire conservé");

        assertEquals(AgentActionOutcome.SUCCESS, run("reward.table.duplicate",
                Map.of("table_id", "coffre_du_marais", "new_id", "coffre_v2")).status());
        assertEquals(AgentActionOutcome.SUCCESS, run("reward.table.toggle",
                Map.of("table_id", "coffre_v2", "enabled", "true")).status());
        assertTrue(service.catalog().findTable("coffre_v2").orElseThrow().enabled());

        AgentActionOutcome status = run("reward.catalog.status", Map.of());
        assertEquals(AgentActionOutcome.SUCCESS, status.status());
        assertEquals(Boolean.TRUE, status.details().get("fileExists"));
        assertEquals(Boolean.TRUE, status.details().get("valid"));
        List<?> tables = (List<?>) status.details().get("tables");
        assertEquals(2, tables.size());
        Map<?, ?> first = (Map<?, ?>) ((List<?>) ((Map<?, ?>) tables.get(0)).get("entries")).get(1);
        assertEquals("12.5", first.get("chanceValue"));
        assertEquals("12,5 %", first.get("chance"));
        List<?> rewards = (List<?>) status.details().get("rewards");
        assertEquals("ENCHANTED_BOOK (sharpness 3)", ((Map<?, ?>) rewards.get(1)).get("summary"));
    }

    @Test
    void aTableTheServerRefusesIsNeverWritten() throws Exception {
        byte[] before = Files.readAllBytes(file);
        AgentActionOutcome over = run("reward.table.save", Map.of("table_id", "trop", "label", "Trop",
                "mode", "single", "enabled", "true", "create", "true", "entries", "gem=gem@70;book=book@40"));
        AgentActionOutcome unknown = run("reward.table.save", Map.of("table_id", "inconnue", "label", "Inconnue",
                "mode", "single", "enabled", "true", "create", "true", "entries", "x=pas_au_catalogue@5"));
        assertEquals(AgentActionOutcome.FAILED, over.status());
        assertEquals("INVALID", over.details().get("code"));
        assertEquals("INVALID", unknown.details().get("code"));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void withoutCatalogFileNothingIsCreated() throws Exception {
        Files.delete(file);
        service.reloadNow();
        AgentActionOutcome outcome = run("reward.table.save", Map.of("table_id", "t", "label", "T", "mode", "single",
                "enabled", "true", "create", "true", "entries", "gem=gem@5"));
        assertEquals("NO_CATALOG", outcome.details().get("code"));
        assertTrue(Files.notExists(file));
        assertEquals(Boolean.FALSE, run("reward.catalog.status", Map.of()).details().get("fileExists"));
    }
}
