package com.lodygames.rpgquest.economy.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.economy.merchant.YamlMerchantRegistry;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #336 — chemin réel PlugAdmin → agent : paramètres tels que le panel les envoie,
 * {@link AgentActionExecutor}, {@link BukkitAgentActions}, éditeur, fichier, rechargement.
 */
class PriceCatalogAgentFlowTest {

    @TempDir
    Path tempDir;
    private PriceCatalogService service;
    private AgentActionExecutor executor;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        file = tempDir.resolve("prices.yml");
        service = new PriceCatalogService(file, NOPLogger.NOP_LOGGER);
        service.start();
        Path merchants = Files.createDirectories(tempDir.resolve("merchants"));
        Files.writeString(merchants.resolve("atelier.yml"), """
                id: rpgquest:atelier
                title: "<gold>Atelier</gold>"
                offers:
                  - price-ref: pain
                  - price-ref: fantome
                  - direction: SELL_TO_PLAYER
                    material: TORCH
                    quantity: 8
                    price: 5
                """);
        YamlMerchantRegistry registry = new YamlMerchantRegistry(merchants, NOPLogger.NOP_LOGGER);
        registry.reload();
        BukkitAgentActions actions = new BukkitAgentActions(plugin, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, () -> "wild",
                () -> new com.lodygames.rpgquest.config.HubConfig("world_hub"),
                new ServerOpsService(plugin, new ServerLogBuffer(50), java.util.Optional::empty),
                null, null, java.util.Optional::empty, null, null, null, null, null, null);
        actions.setPriceCatalog(service, registry);
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

    private static Map<String, String> bread(String coins) {
        Map<String, String> params = new HashMap<>(Map.of("price_key", "pain", "label", "Pain", "type",
                "SELL_TO_PLAYER", "item", "BREAD", "amount", "4", "demo", "true", "enabled", "true", "create", "true"));
        if (coins != null) {
            params.put("coins", coins);
        }
        return params;
    }

    @Test
    @SuppressWarnings("unchecked")
    void createReadBackDuplicateAndToggleThroughTheAgent() throws Exception {
        AgentActionOutcome before = run("price.catalog.status", Map.of());
        assertEquals(AgentActionOutcome.SUCCESS, before.status(), before.message());
        assertEquals(false, before.details().get("fileExists"));

        AgentActionOutcome created = run("price.save", bread("3"));
        assertEquals(AgentActionOutcome.SUCCESS, created.status(), created.message());
        assertTrue(Files.exists(file));

        AgentActionOutcome craft = run("price.save", Map.of("price_key", "torche_fabriquee", "label", "Torches",
                "type", "CRAFT", "item", "TORCH", "amount", "4", "coins", "2", "ingredients", "STICK@1;COAL@1",
                "enabled", "true", "create", "true"));
        assertEquals(AgentActionOutcome.SUCCESS, craft.status(), craft.message());

        AgentActionOutcome status = run("price.catalog.status", Map.of());
        List<Map<String, Object>> prices = (List<Map<String, Object>>) status.details().get("prices");
        assertEquals(2, prices.size());
        Map<String, Object> pain = prices.get(0);
        assertEquals(3L, pain.get("coins"));
        assertEquals(true, pain.get("demo"));
        assertTrue(((List<String>) pain.get("usages")).get(0).contains("rpgquest:atelier"));
        assertEquals(2, ((List<?>) prices.get(1).get("ingredients")).size());
        Map<String, Object> merchant = ((List<Map<String, Object>>) status.details().get("merchants")).get(0);
        assertEquals("Atelier", merchant.get("title"));
        assertEquals(1, merchant.get("inlineOffers"));
        assertEquals(List.of("fantome"), merchant.get("unavailableRefs"));

        AgentActionOutcome copy = run("price.duplicate", Map.of("price_key", "pain", "new_key", "pain_hiver"));
        assertEquals(AgentActionOutcome.SUCCESS, copy.status(), copy.message());
        assertFalse(service.catalog().prices().get("pain_hiver").enabled());

        AgentActionOutcome off = run("price.toggle", Map.of("price_key", "pain", "enabled", "false"));
        assertEquals(AgentActionOutcome.SUCCESS, off.status(), off.message());
        assertTrue(off.message().contains("rpgquest:atelier"), "la désactivation cite le marchand touché");
        assertTrue(service.catalog().usable("pain").isEmpty());
    }

    @Test
    void refusesMissingCoinsUnknownCustomItemsAndLoopsWithoutWriting() throws Exception {
        AgentActionOutcome noCoins = run("price.save", bread(null));
        assertEquals(AgentActionOutcome.REJECTED, noCoins.status());
        assertTrue(noCoins.message().contains("aucun prix par défaut"), noCoins.message());

        Map<String, String> custom = bread("3");
        custom.put("item", "rpgquest:inconnu");
        AgentActionOutcome unknown = run("price.save", custom);
        assertFalse(AgentActionOutcome.SUCCESS.equals(unknown.status()));
        assertTrue(unknown.message().contains("inconnu"), unknown.message());
        assertFalse(Files.exists(file), "rien n'a été écrit");

        run("price.save", bread("4"));
        AgentActionOutcome loop = run("price.save", Map.of("price_key", "rachat_pain", "label", "Rachat",
                "type", "BUY_FROM_PLAYER", "item", "BREAD", "amount", "1", "coins", "2", "enabled", "true",
                "create", "true"));
        assertFalse(AgentActionOutcome.SUCCESS.equals(loop.status()));
        assertTrue(loop.message().contains("Boucle d'enrichissement"), loop.message());
        assertFalse(service.catalog().prices().containsKey("rachat_pain"));
    }
}
