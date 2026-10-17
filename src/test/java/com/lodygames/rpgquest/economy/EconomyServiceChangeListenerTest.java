package com.lodygames.rpgquest.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #184 — le HUD n'est prévenu que des mouvements réellement passés au portefeuille. */
class EconomyServiceChangeListenerTest {

    private static final long TIMEOUT_SECONDS = 5;

    private record Change(UUID playerId, long delta) {
    }

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private PlayerProfileRepository profiles;
    private EconomyService economy;
    private final List<Change> changes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profiles = new PlayerProfileRepository(database);
        economy = new EconomyService(new WalletRepository(database));
        economy.setChangeListener((playerId, delta) -> changes.add(new Change(playerId, delta)));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void creditAndDebitReportSignedDeltas() throws Exception {
        UUID steve = createPlayer("Steve");

        economy.credit(steve, 50, TransactionType.ADMIN_GRANT, "test").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        economy.debit(steve, 20, TransactionType.ADMIN_TAKE, "test").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(new Change(steve, 50), new Change(steve, -20)), changes);
    }

    @Test
    void refusedDebitAndRefusedPaymentReportNothing() throws Exception {
        UUID steve = createPlayer("Steve");
        UUID alex = createPlayer("Alex");

        assertFalse(economy.debit(steve, 20, TransactionType.ADMIN_TAKE, "test").get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(PayOutcome.INSUFFICIENT_FUNDS, economy.pay(steve, alex, 5).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(PayOutcome.INVALID_AMOUNT, economy.pay(steve, alex, 0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(List.of(), changes);
    }

    @Test
    void paymentReportsBothSidesAndAdminSetReportsZeroDelta() throws Exception {
        UUID steve = createPlayer("Steve");
        UUID alex = createPlayer("Alex");
        economy.adminSet(steve, 100, "test").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        changes.clear();

        assertEquals(PayOutcome.PAID, economy.pay(steve, alex, 30).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        economy.adminSet(alex, 7, "test").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(new Change(steve, -30), new Change(alex, 30), new Change(alex, 0)), changes);
    }

    @Test
    void aFailingListenerNeverBreaksTheTransaction() throws Exception {
        UUID steve = createPlayer("Steve");
        economy.setChangeListener((playerId, delta) -> {
            throw new IllegalStateException("affichage en panne");
        });

        economy.credit(steve, 40, TransactionType.ADMIN_GRANT, "test").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(40L, economy.balance(steve).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private UUID createPlayer(String name) throws Exception {
        UUID uuid = UUID.randomUUID();
        profiles.findOrCreate(uuid, name).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return uuid;
    }
}
