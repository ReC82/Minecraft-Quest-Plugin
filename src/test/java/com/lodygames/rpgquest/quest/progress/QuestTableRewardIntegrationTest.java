package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.RewardEntitlementRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.entitlement.reward.RewardSource;
import com.lodygames.rpgquest.entitlement.reward.RewardTable;
import com.lodygames.rpgquest.entitlement.reward.RewardTables;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #177 — récompense de quête « Tirage » : vraie complétion, vraie base SQLite. Le tirage est
 * écrit dans la transaction de complétion, sous forme de droits à récompense {@code QUEST} ; la
 * remise elle-même est celle de #422 (couverte par ses propres tests).
 */
class QuestTableRewardIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey SURE = new NamespacedKey("rpgquest", "tc177_sure");
    private static final NamespacedKey BOTH = new NamespacedKey("rpgquest", "tc177_both");
    private static final NamespacedKey NOWHERE = new NamespacedKey("rpgquest", "tc177_nowhere");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private RewardEntitlementRepository entitlements;
    private QuestProgressEngine engine;
    private final List<UUID> deliverSoon = new ArrayList<>();

    private final Map<String, RewardTable> tables = Map.of(
            "sure", new RewardTable("sure", "Coffre sûr", RewardTable.Mode.SINGLE, true,
                    List.of(new RewardTable.Entry("gem", "gem_reward", RewardTable.FULL))),
            "both", new RewardTable("both", "Double coffre", RewardTable.Mode.INDEPENDENT, true,
                    List.of(new RewardTable.Entry("a", "gem_reward", RewardTable.FULL),
                            new RewardTable.Entry("b", "book_reward", RewardTable.FULL))));

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        entitlements = new RewardEntitlementRepository(database);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("sure.yml"), questYaml(SURE, true, "sure"));
        Files.writeString(questsDir.resolve("both.yml"), questYaml(BOTH, false, "both"));
        Files.writeString(questsDir.resolve("nowhere.yml"), questYaml(NOWHERE, false, "missing"));
        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        engine = new QuestProgressEngine(plugin, questEngine, new QuestProgressRepository(database),
                new PlayerVariableRepository(database), messagesService, npcIdentityService,
                new EconomyService(new WalletRepository(database)));
        engine.setRewardTables(new RewardTables() {
            @Override
            public Optional<RewardTable> table(String id) {
                return Optional.ofNullable(tables.get(id));
            }

            @Override
            public String label(String rewardKey) {
                return "gem_reward".equals(rewardKey) ? "Gemme rare" : rewardKey;
            }

            @Override
            public void deliverSoon(List<UUID> players) {
                deliverSoon.addAll(players);
            }
        });
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void theDrawIsWrittenWithTheCompletionAndAnnounced() throws Exception {
        PlayerMock player = addPlayer();
        complete(player, SURE);
        List<RewardEntitlement> pending = awaitPending(player, 1);

        RewardEntitlement win = pending.get(0);
        assertEquals(RewardSource.QUEST, win.source());
        assertEquals(SURE.toString(), win.sourceRef());
        assertEquals("gem_reward", win.rewardKey());
        assertTrue(win.idempotencyKey().startsWith("quest:"), win.idempotencyKey());
        assertTrue(win.idempotencyKey().endsWith(":sure.gem:" + player.getUniqueId()), win.idempotencyKey());
        assertTrue(win.label().contains("table sure v"), win.label());
        assertTrue(drain(player).stream().anyMatch(m -> m.contains("Gemme rare") && m.contains("Coffre sûr")));
        // La garantie (objet) est toujours remise à côté du tirage.
        assertTrue(player.getInventory().contains(Material.BREAD));
        awaitDeliverSoon(player);
    }

    @Test
    void eachRepetitionOfARepeatableQuestIsItsOwnDraw() throws Exception {
        PlayerMock player = addPlayer();
        complete(player, SURE);
        awaitPending(player, 1);
        complete(player, SURE);
        List<RewardEntitlement> pending = awaitPending(player, 2);
        assertTrue(!pending.get(0).idempotencyKey().equals(pending.get(1).idempotencyKey()));
    }

    @Test
    void independentChancesCanGiveSeveralRewards() throws Exception {
        PlayerMock player = addPlayer();
        complete(player, BOTH);
        List<RewardEntitlement> pending = awaitPending(player, 2);
        assertEquals(List.of("book_reward", "gem_reward"),
                pending.stream().map(RewardEntitlement::rewardKey).sorted().toList());
    }

    @Test
    void anUnknownTableDrawsNothingAndSaysSo() throws Exception {
        PlayerMock player = addPlayer();
        complete(player, NOWHERE);
        String message = awaitMessageContaining(player, "indisponible");
        assertTrue(message.contains("missing"), message);
        assertEquals(0, entitlements.pendingFor(List.of(player.getUniqueId()), 10)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    // ---- utilitaires ---------------------------------------------------------------------------

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private void complete(PlayerMock player, NamespacedKey quest) throws Exception {
        engine.accept(player, quest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
    }

    private List<RewardEntitlement> awaitPending(PlayerMock player, int expected) throws Exception {
        List<RewardEntitlement> pending = List.of();
        for (int i = 0; i < 200; i++) {
            pending = entitlements.pendingFor(List.of(player.getUniqueId()), 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (pending.size() >= expected) {
                break;
            }
            server.getScheduler().performTicks(2);
            Thread.sleep(10);
        }
        assertEquals(expected, pending.size());
        return pending;
    }

    private void awaitDeliverSoon(PlayerMock player) throws Exception {
        for (int i = 0; i < 200 && !deliverSoon.contains(player.getUniqueId()); i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(10);
        }
        assertTrue(deliverSoon.contains(player.getUniqueId()));
    }

    private static List<String> drain(PlayerMock player) {
        List<String> seen = new ArrayList<>();
        String message;
        while ((message = player.nextMessage()) != null) {
            seen.add(message);
        }
        return seen;
    }

    private String awaitMessageContaining(PlayerMock player, String needle) throws Exception {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String message;
            while ((message = player.nextMessage()) != null) {
                seen.add(message);
                if (message.contains(needle)) {
                    return message;
                }
            }
            server.getScheduler().performTicks(2);
            Thread.sleep(10);
        }
        throw new AssertionError("aucun message contenant « " + needle + " » ; vus : " + seen);
    }

    private static String questYaml(NamespacedKey id, boolean repeatable, String table) {
        return """
                id: %s
                title: "Quête du tirage"
                description: "Description"
                category: test
                repeatable: %s
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: ITEM
                    material: BREAD
                    amount: 1
                  - type: TABLE
                    table: %s
                """.formatted(id, repeatable, table);
    }
}
