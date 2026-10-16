package com.lodygames.rpgquest.entitlement.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.RewardEntitlementRepository;
import com.lodygames.rpgquest.database.StatisticsRepository;
import com.lodygames.rpgquest.entitlement.EntitlementService;
import com.lodygames.rpgquest.stats.EventRanking;
import com.lodygames.rpgquest.stats.EventReward;
import com.lodygames.rpgquest.stats.EventRules;
import com.lodygames.rpgquest.stats.StatPeriod;
import com.lodygames.rpgquest.stats.StatScope;
import com.lodygames.rpgquest.stats.StatisticsDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #422 — remise des droits à récompense (§5) et déclaration depuis l'archive (§6.1) : vraie
 * base SQLite, vrais inventaires MockBukkit, remise sécurisée commune #318.
 */
class RewardEntitlementServiceTest {

    private static final long TIMEOUT = 10;
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final String CATALOG = """
            enabled: true
            rewards:
              diamonds:
                label: "Trois diamants"
                items: [{item: DIAMOND, amount: 3}]
                perk: {key: title_zombie, tier: GOLD}
              mystery:
                label: "Objet inconnu"
                items: [{item: "rpgquest:does_not_exist"}]
            """;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private PlayerMock player;
    private DatabaseManager database;
    private RewardEntitlementRepository repository;
    private StatisticsRepository statisticsRepository;
    private final FakePerks perks = new FakePerks();
    private StatisticsDefinition statistics = StatisticsDefinition.EMPTY;
    private final Set<String> archived = ConcurrentHashMap.newKeySet();
    private Executor mainThread = Runnable::run;
    private RewardEntitlementService service;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        player = server.addPlayer();
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        repository = new RewardEntitlementRepository(database);
        statisticsRepository = new StatisticsRepository(database);
        service = newService(CATALOG);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private RewardEntitlementService newService(String catalog) throws Exception {
        Path file = tempDir.resolve("entitlements-" + UUID.randomUUID() + ".yml");
        Files.writeString(file, catalog);
        RewardEntitlementService created = new RewardEntitlementService(null, file, repository, () -> statistics,
                archived::contains, statisticsRepository, perks, (line, log) -> line.custom() ? Optional.empty()
                        : Optional.of(new ItemStack(Material.valueOf(line.itemId()), line.amount())),
                NOPLogger.NOP_LOGGER, Clock.fixed(NOW, ZoneOffset.UTC), task -> mainThread.execute(task));
        created.reloadIfChanged();
        return created;
    }

    private <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT, TimeUnit.SECONDS);
    }

    private RewardEntitlement declare(String key, String reward) throws Exception {
        get(repository.declare(new RewardEntitlement.Declaration(key, player.getUniqueId(), RewardSource.ADMIN,
                "test", reward, "label"), NOW));
        return get(repository.find(key)).orElseThrow();
    }

    private RewardEntitlementStatus status(String key) throws Exception {
        return get(repository.find(key)).orElseThrow().status();
    }

    private void fillInventory() {
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
    }

    @Test
    void deliversOnceThenNeverAgain() throws Exception {
        RewardEntitlement pending = declare("admin:a", "diamonds");

        assertEquals(RewardEntitlementService.Attempt.DELIVERED, get(service.attempt(pending)));
        assertTrue(player.getInventory().contains(Material.DIAMOND, 3));
        assertEquals(RewardEntitlementStatus.DELIVERED, status("admin:a"));
        assertEquals(Optional.of("GOLD"), get(perks.currentTier(player.getUniqueId(), "title_zombie")));

        // Ligne périmée relue avant la remise (deuxième session, autre tick) : rien de plus.
        assertEquals(RewardEntitlementService.Attempt.ALREADY_TAKEN, get(service.attempt(pending)));
        assertEquals(3, player.getInventory().all(Material.DIAMOND).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
        assertEquals(1, perks.grants);
    }

    @Test
    void fullInventoryKeepsTheEntitlementPendingUntilThereIsRoom() throws Exception {
        RewardEntitlement pending = declare("admin:a", "diamonds");
        fillInventory();

        assertEquals(RewardEntitlementService.Attempt.INVENTORY_FULL, get(service.attempt(pending)));
        assertEquals(RewardEntitlementStatus.PENDING, status("admin:a"));
        assertFalse(player.getInventory().contains(Material.DIAMOND));
        assertEquals(0, perks.grants, "rien d'accordé tant que la remise n'a pas lieu");

        player.getInventory().setItem(0, null);
        assertEquals(RewardEntitlementService.Attempt.DELIVERED, get(service.attempt(pending)));
        assertTrue(player.getInventory().contains(Material.DIAMOND, 3));
    }

    @Test
    void disconnectionAfterTheClaimReleasesTheEntitlement() throws Exception {
        RewardEntitlement pending = declare("admin:a", "diamonds");
        mainThread = task -> {
            player.disconnect();
            task.run();
        };

        assertEquals(RewardEntitlementService.Attempt.RELEASED, get(service.attempt(pending)));
        RewardEntitlement released = get(repository.find("admin:a")).orElseThrow();
        assertEquals(RewardEntitlementStatus.PENDING, released.status());
        assertEquals(1, released.attempts());
        assertEquals(0, perks.grants);
    }

    @Test
    void revokedOrOfflineOrUnknownIsNeverDelivered() throws Exception {
        RewardEntitlement revoked = declare("admin:revoked", "diamonds");
        assertTrue(get(service.revoke("admin:revoked", "erreur")));
        assertEquals(RewardEntitlementService.Attempt.ALREADY_TAKEN, get(service.attempt(revoked)));

        RewardEntitlement unknown = declare("admin:unknown", "not_in_catalog");
        assertEquals(RewardEntitlementService.Attempt.UNKNOWN_REWARD, get(service.attempt(unknown)));
        RewardEntitlement traced = get(repository.find("admin:unknown")).orElseThrow();
        assertEquals(RewardEntitlementStatus.PENDING, traced.status());
        assertTrue(traced.lastError().startsWith(RewardEntitlementService.UNKNOWN_REWARD));
        assertEquals(RewardEntitlementService.Attempt.UNKNOWN_REWARD, get(service.attempt(traced)));
        assertEquals(1, get(repository.find("admin:unknown")).orElseThrow().attempts(), "erreur écrite une fois");

        RewardEntitlement mystery = declare("admin:mystery", "mystery");
        assertEquals(RewardEntitlementService.Attempt.UNKNOWN_REWARD, get(service.attempt(mystery)));

        RewardEntitlement offline = declare("admin:offline", "diamonds");
        player.disconnect();
        assertEquals(RewardEntitlementService.Attempt.OFFLINE, get(service.attempt(offline)));
        assertFalse(player.getInventory().contains(Material.DIAMOND));
    }

    @Test
    void disabledCatalogDeliversAndDeclaresNothing() throws Exception {
        RewardEntitlementService disabled = newService(CATALOG.replace("enabled: true", "enabled: false"));
        RewardEntitlement pending = declare("admin:a", "diamonds");
        assertEquals(RewardEntitlementService.Attempt.DISABLED, get(disabled.attempt(pending)));
        assertThrows(Exception.class, () -> get(disabled.declareAdmin(player.getUniqueId(), "diamonds", "ref")));
        assertEquals(RewardEntitlementStatus.PENDING, status("admin:a"));
    }

    @Test
    void bossDeclarationsAreIdempotentAndRefusedWhenDisabled() throws Exception {
        String key = "boss:00000000-0000-0000-0000-0000000000b0:diamonds:" + player.getUniqueId();
        RewardEntitlement.Declaration declaration = new RewardEntitlement.Declaration(key, player.getUniqueId(),
                RewardSource.BOSS, "rpgquest:ogre@x", "diamonds", "Trois diamants — Ogre");
        assertEquals(1, get(service.declareAll(List.of(declaration))));
        assertEquals(0, get(service.declareAll(List.of(declaration))), "seconde mort constatée : aucun doublon");
        assertEquals(RewardSource.BOSS, get(repository.find(key)).orElseThrow().source());

        RewardEntitlementService disabled = newService(CATALOG.replace("enabled: true", "enabled: false"));
        assertThrows(Exception.class, () -> get(disabled.declareAll(List.of(declaration))));
    }

    @Test
    void aDisabledRewardIsNeverPromisedButAPendingOneIsStillDelivered() throws Exception {
        RewardEntitlement pending = declare("admin:a", "diamonds");
        RewardEntitlementService off = newService(CATALOG.replace("    perk: {key: title_zombie, tier: GOLD}\n",
                "    perk: {key: title_zombie, tier: GOLD}\n    enabled: false\n"));
        assertFalse(off.catalog().find("diamonds").orElseThrow().enabled());
        assertFalse(off.rewardEnabled("diamonds"));
        assertThrows(Exception.class, () -> get(off.declareAdmin(player.getUniqueId(), "diamonds", "x")));
        RewardEntitlement.Declaration boss = new RewardEntitlement.Declaration("boss:b:diamonds:" + player.getUniqueId(),
                player.getUniqueId(), RewardSource.BOSS, "rpgquest:ogre@x", "diamonds", "Trois diamants");
        assertEquals(0, get(off.declareAll(List.of(boss))));
        assertTrue(get(repository.find(boss.idempotencyKey())).isEmpty());
        assertEquals(RewardEntitlementService.Attempt.DELIVERED, get(off.attempt(pending)),
                "un droit déjà ouvert reste remis");
    }

    @Test
    void adminDeclarationIsIdempotentPerReference() throws Exception {
        RewardEntitlementService.DeclareOutcome first = get(service.declareAdmin(player.getUniqueId(), "diamonds", "concours-1"));
        RewardEntitlementService.DeclareOutcome again = get(service.declareAdmin(player.getUniqueId(), "diamonds", "concours-1"));
        assertTrue(first.created());
        assertFalse(again.created());
        assertEquals("admin:concours-1:" + player.getUniqueId(), first.idempotencyKey());
        assertEquals("Trois diamants", get(repository.find(first.idempotencyKey())).orElseThrow().label());
        assertThrows(Exception.class, () -> get(service.declareAdmin(player.getUniqueId(), "absent", "x")));
        assertThrows(Exception.class, () -> get(service.declareAdmin(player.getUniqueId(), "diamonds", "a b")));
    }

    @Test
    void archivedEventOpensEntitlementsOnceAndOnlyWhenArchived() throws Exception {
        UUID other = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        StatPeriod event = new StatPeriod("zombie_weekend", StatScope.EVENT, "Zombie", Instant.parse("2026-11-07T18:00:00Z"),
                Instant.parse("2026-11-09T22:00:00Z"), List.of("ENTITY_KILL"), StatPeriod.State.PLANNED,
                new EventRules(EventRanking.HIGHEST_TOTAL, Map.of(), 0, List.of(
                        new EventReward(EventReward.Kind.RANK, 1, "diamonds", "Rang 1"))));
        statistics = new StatisticsDefinition(List.of(event), Map.of(), StatisticsDefinition.DEFAULT_EXCLUDED_XP_REASONS);
        freeze("zombie_weekend", Map.of(player.getUniqueId(), 12L, other, 7L));

        get(service.declareFromArchivedEvents());
        assertEquals(0, get(repository.list(null, null, 10)).size(), "pas encore archivé : rien");

        archived.add("zombie_weekend");
        get(service.declareFromArchivedEvents());
        get(newService(CATALOG).declareFromArchivedEvents()); // redémarrage : relecture sans doublon
        List<RewardEntitlementRepository.Listed> rows = get(repository.list(null, null, 10));
        assertEquals(1, rows.size());
        RewardEntitlement winner = rows.get(0).entitlement();
        assertEquals("event:zombie_weekend:rank:1:diamonds:" + player.getUniqueId(), winner.idempotencyKey());
        assertEquals(RewardSource.EVENT, winner.source());
        assertEquals("Rang 1", winner.label());

        assertEquals(RewardEntitlementService.Attempt.DELIVERED, get(service.attempt(winner)));
    }

    @Test
    void cancelledEventOpensNothing() throws Exception {
        StatPeriod event = new StatPeriod("cancelled", StatScope.EVENT, "Annulé", Instant.parse("2026-11-07T18:00:00Z"),
                Instant.parse("2026-11-09T22:00:00Z"), List.of("ENTITY_KILL"), StatPeriod.State.CANCELLED,
                new EventRules(EventRanking.HIGHEST_TOTAL, Map.of(), 0, List.of(
                        new EventReward(EventReward.Kind.RANK, 1, "diamonds", "Rang 1"))));
        statistics = new StatisticsDefinition(List.of(event), Map.of(), StatisticsDefinition.DEFAULT_EXCLUDED_XP_REASONS);
        freeze("cancelled", Map.of(player.getUniqueId(), 12L));
        archived.add("cancelled");
        get(service.declareFromArchivedEvents());
        assertEquals(0, get(repository.list(null, null, 10)).size());
    }

    /** Archive figée écrite directement (le calcul de l'archive est couvert par EventEngineRepositoryTest). */
    private void freeze(String periodId, Map<UUID, Long> values) throws Exception {
        get(database.execute(connection -> {
            for (Map.Entry<UUID, Long> entry : values.entrySet()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO stat_period_results (period_id, metric, player_uuid, stat_value) VALUES (?, ?, ?, ?)")) {
                    statement.setString(1, periodId);
                    statement.setString(2, "ENTITY_KILL");
                    statement.setString(3, entry.getKey().toString());
                    statement.setLong(4, entry.getValue());
                    statement.executeUpdate();
                }
            }
            return null;
        }));
    }

    /** Avantages en mémoire : compte les octrois réellement écrits. */
    private static final class FakePerks implements EntitlementService {
        private final Map<String, String> tiers = new ConcurrentHashMap<>();
        private volatile int grants;

        @Override
        public CompletableFuture<Optional<String>> currentTier(UUID playerId, String entitlementKey) {
            return CompletableFuture.completedFuture(Optional.ofNullable(tiers.get(playerId + "/" + entitlementKey)));
        }

        @Override
        public CompletableFuture<Void> grant(UUID playerId, String entitlementKey, String tier, String reason) {
            tiers.put(playerId + "/" + entitlementKey, tier);
            grants++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> revoke(UUID playerId, String entitlementKey, String reason) {
            tiers.remove(playerId + "/" + entitlementKey);
            return CompletableFuture.completedFuture(null);
        }
    }
}
