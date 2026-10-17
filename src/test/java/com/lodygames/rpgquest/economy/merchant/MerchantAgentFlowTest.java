package com.lodygames.rpgquest.economy.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.economy.price.PriceCatalogService;
import com.lodygames.rpgquest.ops.ServerLogBuffer;
import com.lodygames.rpgquest.ops.ServerOpsService;
import com.lodygames.rpgquest.web.agent.AgentAction;
import com.lodygames.rpgquest.web.agent.AgentActionExecutor;
import com.lodygames.rpgquest.web.agent.AgentActionOutcome;
import com.lodygames.rpgquest.web.agent.BukkitAgentActions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #17 — chemin réel PlugAdmin → agent : paramètres tels que le panel les envoie,
 * {@link AgentActionExecutor}, {@link BukkitAgentActions}, éditeur, fichier, rechargement.
 */
class MerchantAgentFlowTest {

    @TempDir
    Path tempDir;
    private AgentActionExecutor executor;
    private YamlMerchantRegistry registry;
    private Path merchants;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        Path pricesFile = tempDir.resolve("prices.yml");
        Files.writeString(pricesFile, """
                prices:
                  pain:
                    label: Pain
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 2
                    coins: 5
                  galets:
                    label: Galets
                    type: BUY_FROM_PLAYER
                    item: COBBLESTONE
                    amount: 16
                    coins: 1
                """);
        PriceCatalogService prices = new PriceCatalogService(pricesFile, NOPLogger.NOP_LOGGER);
        prices.start();
        merchants = Files.createDirectories(tempDir.resolve("merchants"));
        registry = new YamlMerchantRegistry(merchants, NOPLogger.NOP_LOGGER);
        registry.reload();
        BukkitAgentActions actions = new BukkitAgentActions(plugin, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, () -> "wild",
                () -> new com.lodygames.rpgquest.config.HubConfig("world_hub"),
                new ServerOpsService(plugin, new ServerLogBuffer(50), java.util.Optional::empty),
                null, null, java.util.Optional::empty, null, null, null, null, null, null);
        actions.setPriceCatalog(prices, registry);
        executor = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private AgentActionOutcome run(String type, Map<String, String> params) throws Exception {
        return executor.execute(new AgentAction("t-" + type, type, params)).get(10, TimeUnit.SECONDS);
    }

    private static Map<String, String> counter(String type, String offers) {
        return new HashMap<>(Map.of("merchant_id", "comptoir", "title", "Comptoir de rachat", "catalog_type", type,
                "enabled", "true", "create", "true", "offers", offers));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createReadBackEditDuplicateToggleAndDeleteThroughTheAgent() throws Exception {
        AgentActionOutcome created = run("merchant.save", counter("BUY",
                "BUY_FROM_PLAYER|COBBLESTONE|64|6|||||\n||||galets||||\n"
                        + "BUY_FROM_PLAYER|ROTTEN_FLESH|8|1||rpgquest:first_steps|COMPLETED|5|rpgquest.vip"));
        assertEquals(AgentActionOutcome.SUCCESS, created.status(), created.message());
        assertTrue(Files.exists(merchants.resolve("comptoir.yml")));

        AgentActionOutcome status = run("merchant.catalog.status", Map.of());
        assertEquals(AgentActionOutcome.SUCCESS, status.status(), status.message());
        Map<String, Object> m = ((List<Map<String, Object>>) status.details().get("merchants")).get(0);
        assertEquals("rpgquest:comptoir", m.get("id"));
        assertEquals("BUY", m.get("type"));
        assertEquals(3, ((List<?>) m.get("offers")).size());
        assertEquals("galets", ((List<Map<String, Object>>) m.get("offers")).get(1).get("priceRef"));
        assertEquals(2, ((List<?>) status.details().get("prices")).size());
        String version = (String) m.get("version");

        Map<String, String> edit = counter("BUY", "BUY_FROM_PLAYER|COBBLESTONE|64|7|||||");
        edit.put("merchant_id", "rpgquest:comptoir");
        edit.put("create", "false");
        edit.put("expected_version", version);
        AgentActionOutcome updated = run("merchant.save", edit);
        assertEquals(AgentActionOutcome.SUCCESS, updated.status(), updated.message());
        assertEquals(7L, registry.find(new NamespacedKey("rpgquest", "comptoir")).orElseThrow().offers().get(0).price());

        AgentActionOutcome stale = run("merchant.save", edit);
        assertFalse(AgentActionOutcome.SUCCESS.equals(stale.status()), "version périmée : refus");

        AgentActionOutcome copy = run("merchant.duplicate", Map.of("merchant_id", "rpgquest:comptoir",
                "new_key", "comptoir_hiver"));
        assertEquals(AgentActionOutcome.SUCCESS, copy.status(), copy.message());
        assertFalse(registry.find(new NamespacedKey("rpgquest", "comptoir_hiver")).orElseThrow().enabled());

        AgentActionOutcome closed = run("merchant.toggle", Map.of("merchant_id", "rpgquest:comptoir",
                "enabled", "false"));
        assertEquals(AgentActionOutcome.SUCCESS, closed.status(), closed.message());
        assertFalse(registry.find(new NamespacedKey("rpgquest", "comptoir")).orElseThrow().enabled());

        AgentActionOutcome deleted = run("merchant.delete", Map.of("merchant_id", "rpgquest:comptoir_hiver"));
        assertEquals(AgentActionOutcome.SUCCESS, deleted.status(), deleted.message());
        assertFalse(Files.exists(merchants.resolve("comptoir_hiver.yml")));
    }

    @Test
    void refusesWrongPriceTypeUnknownPriceUnknownItemAndBadOffersWithoutWriting() throws Exception {
        for (Map<String, String> params : List.of(
                counter("BUY", "||||pain||||"),
                counter("BUY", "||||inconnu||||"),
                counter("SELL", "SELL_TO_PLAYER|rpgquest:inexistant|1|1|||||"),
                counter("SELL", "SELL_TO_PLAYER|NOT_A_MATERIAL|1|1|||||"),
                counter("BUY", "SELL_TO_PLAYER|BREAD|1|1|||||"),
                counter("SELL", "SELL_TO_PLAYER|BREAD|0|1|||||"),
                counter("SELL", "SELL_TO_PLAYER|BREAD|1||||||"),
                counter("SELL", ""))) {
            AgentActionOutcome outcome = run("merchant.save", params);
            assertFalse(AgentActionOutcome.SUCCESS.equals(outcome.status()), params + " → " + outcome.message());
        }
        assertFalse(Files.exists(merchants.resolve("comptoir.yml")));
    }
}
