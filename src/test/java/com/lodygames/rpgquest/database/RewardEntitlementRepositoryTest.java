package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.entitlement.reward.RewardEntitlementStatus;
import com.lodygames.rpgquest.entitlement.reward.RewardSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #422 — droits à récompense : idempotence et transitions conditionnelles, vraie base SQLite. */
class RewardEntitlementRepositoryTest {

    private static final long TIMEOUT = 10;
    private static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private RewardEntitlementRepository repository;

    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000b0b");

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        repository = new RewardEntitlementRepository(database);
        new PlayerProfileRepository(database).findOrCreate(alice, "Alice").get(TIMEOUT, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static RewardEntitlement.Declaration declaration(String key, UUID player) {
        return new RewardEntitlement.Declaration(key, player, RewardSource.ADMIN, "test", "diamonds", "3 diamants");
    }

    private <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT, TimeUnit.SECONDS);
    }

    @Test
    void declaringTheSameKeyTwiceCreatesOneEntitlement() throws Exception {
        assertTrue(get(repository.declare(declaration("admin:a:" + alice, alice), T0)));
        assertFalse(get(repository.declare(declaration("admin:a:" + alice, alice), T0.plusSeconds(5))));
        assertEquals(1, get(repository.declareAll(List.of(declaration("admin:a:" + alice, alice),
                declaration("admin:b:" + alice, alice)), T0)));

        RewardEntitlement stored = get(repository.find("admin:a:" + alice)).orElseThrow();
        assertEquals(RewardEntitlementStatus.PENDING, stored.status());
        assertEquals(T0, stored.createdAt());
        assertEquals("3 diamants", stored.label());
        assertEquals(0, stored.attempts());
    }

    @Test
    void claimSucceedsOnlyOnceEvenWhenConcurrent() throws Exception {
        String key = "admin:a:" + alice;
        get(repository.declare(declaration(key, alice), T0));
        List<CompletableFuture<Boolean>> claims = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            claims.add(repository.claim(key, T0.plusSeconds(i)));
        }
        long winners = 0;
        for (CompletableFuture<Boolean> claim : claims) {
            winners += get(claim) ? 1 : 0;
        }
        assertEquals(1, winners);
        RewardEntitlement delivered = get(repository.find(key)).orElseThrow();
        assertEquals(RewardEntitlementStatus.DELIVERED, delivered.status());
        assertEquals(T0, delivered.deliveredAt());
    }

    @Test
    void releaseReturnsAClaimedEntitlementToPendingAndCountsTheAttempt() throws Exception {
        String key = "admin:a:" + alice;
        get(repository.declare(declaration(key, alice), T0));
        assertFalse(get(repository.release(key, "rien à compenser", T0)), "pas encore pris");
        assertTrue(get(repository.claim(key, T0)));
        assertTrue(get(repository.release(key, "joueur déconnecté", T0.plusSeconds(1))));

        RewardEntitlement pending = get(repository.find(key)).orElseThrow();
        assertEquals(RewardEntitlementStatus.PENDING, pending.status());
        assertNull(pending.deliveredAt());
        assertEquals(1, pending.attempts());
        assertEquals("joueur déconnecté", pending.lastError());
        assertTrue(get(repository.claim(key, T0.plusSeconds(2))), "de nouveau remettable");
        assertNull(get(repository.find(key)).orElseThrow().lastError());
    }

    @Test
    void onlyAPendingEntitlementCanBeRevokedAndARevokedOneIsNeverClaimed() throws Exception {
        get(repository.declare(declaration("admin:a:" + alice, alice), T0));
        get(repository.declare(declaration("admin:b:" + alice, alice), T0));
        assertTrue(get(repository.revoke("admin:a:" + alice, "erreur de saisie", T0)));
        assertFalse(get(repository.revoke("admin:a:" + alice, "deux fois", T0)));
        assertFalse(get(repository.claim("admin:a:" + alice, T0)));

        assertTrue(get(repository.claim("admin:b:" + alice, T0)));
        assertFalse(get(repository.revoke("admin:b:" + alice, "trop tard", T0)), "un droit remis ne se révoque pas");
        assertFalse(get(repository.revoke("inconnu", "absent", T0)));

        RewardEntitlement revoked = get(repository.find("admin:a:" + alice)).orElseThrow();
        assertEquals(RewardEntitlementStatus.REVOKED, revoked.status());
        assertEquals("erreur de saisie", revoked.revokeReason());
    }

    @Test
    void failureIsTracedWithoutChangingTheStatus() throws Exception {
        String key = "admin:a:" + alice;
        get(repository.declare(declaration(key, alice), T0));
        assertTrue(get(repository.recordFailure(key, "x".repeat(900), T0)));
        RewardEntitlement stored = get(repository.find(key)).orElseThrow();
        assertEquals(RewardEntitlementStatus.PENDING, stored.status());
        assertEquals(1, stored.attempts());
        assertEquals(RewardEntitlementRepository.MAX_TEXT, stored.lastError().length());
    }

    @Test
    void pendingForAndListFilter() throws Exception {
        get(repository.declare(declaration("admin:a:" + alice, alice), T0));
        get(repository.declare(declaration("admin:b:" + alice, alice), T0.plusSeconds(1)));
        get(repository.declare(declaration("admin:a:" + bob, bob), T0.plusSeconds(2)));
        get(repository.claim("admin:b:" + alice, T0.plusSeconds(3)));

        assertEquals(List.of("admin:a:" + alice), get(repository.pendingFor(List.of(alice), 10)).stream()
                .map(RewardEntitlement::idempotencyKey).toList());
        assertEquals(2, get(repository.pendingFor(List.of(alice, bob), 10)).size());
        assertEquals(List.of(), get(repository.pendingFor(List.of(), 10)));

        List<RewardEntitlementRepository.Listed> all = get(repository.list(null, null, 10));
        assertEquals("admin:b:" + alice, all.get(0).entitlement().idempotencyKey(), "dernier modifié en tête");
        assertEquals("Alice", all.get(0).playerName());
        assertEquals(Set.of("admin:a:" + bob), get(repository.list(bob, null, 10)).stream()
                .map(listed -> listed.entitlement().idempotencyKey()).collect(java.util.stream.Collectors.toSet()));
        assertNull(get(repository.list(bob, null, 10)).get(0).playerName(), "profil absent : pas de pseudo");
        assertEquals(1, get(repository.list(null, RewardEntitlementStatus.DELIVERED, 10)).size());
        assertEquals(1, get(repository.list(null, null, 1)).size());
    }

    @Test
    void declarationRefusesAnInvalidKey() {
        assertThrows(IllegalArgumentException.class, () -> declaration("espace interdit", alice));
        assertThrows(IllegalArgumentException.class,
                () -> declaration("k".repeat(RewardEntitlement.MAX_KEY_LENGTH + 1), alice));
    }
}
