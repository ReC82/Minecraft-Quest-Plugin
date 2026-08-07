package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalletRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private PlayerProfileRepository profiles;
    private WalletRepository wallets;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profiles = new PlayerProfileRepository(database);
        wallets = new WalletRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void balanceOfUntouchedWalletIsZero() throws Exception {
        UUID player = createPlayer("Steve");
        assertEquals(0L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void creditIncreasesBalanceAndBalanceCanBeReadBack() throws Exception {
        UUID player = createPlayer("Steve");

        wallets.credit(player, 100, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(100L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void debitSucceedsWhenBalanceIsSufficient() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.credit(player, 100, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        boolean success = wallets.debit(player, 40, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(success);
        assertEquals(60L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void debitFailsAndLeavesBalanceUnchangedWhenInsufficient() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.credit(player, 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        boolean success = wallets.debit(player, 40, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(success);
        assertEquals(10L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void debitOfUntouchedWalletFails() throws Exception {
        UUID player = createPlayer("Steve");

        boolean success = wallets.debit(player, 1, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(success);
    }

    @Test
    void negativeOrZeroAmountsAreRejectedForDebitAndCredit() throws Exception {
        UUID player = createPlayer("Steve");

        assertThrows(ExecutionException.class,
                () -> wallets.debit(player, 0, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,
                () -> wallets.debit(player, -5, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,
                () -> wallets.credit(player, 0, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,
                () -> wallets.credit(player, -5, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void payTransfersAtomicallyBetweenTwoWallets() throws Exception {
        UUID from = createPlayer("Steve");
        UUID to = createPlayer("Alex");
        wallets.credit(from, 100, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        boolean success = wallets.pay(from, to, 30, null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(success);
        assertEquals(70L, wallets.balance(from).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(30L, wallets.balance(to).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void payFailsAndChangesNothingWhenSenderHasInsufficientFunds() throws Exception {
        UUID from = createPlayer("Steve");
        UUID to = createPlayer("Alex");
        wallets.credit(from, 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        boolean success = wallets.pay(from, to, 30, null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(success);
        assertEquals(10L, wallets.balance(from).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0L, wallets.balance(to).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void setBalanceFixesAnExactAmount() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.credit(player, 100, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        wallets.setBalance(player, 5, "ADMIN_SET", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(5L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void setBalanceRejectsNegativeAmount() throws Exception {
        UUID player = createPlayer("Steve");
        assertThrows(ExecutionException.class,
                () -> wallets.setBalance(player, -1, "ADMIN_SET", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void concurrentDebitsAgainstTheSameWalletNeverOverdraw() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.credit(player, 100, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Simule un double-clic : deux débits soumis avant que le premier ne soit terminé.
        // Le thread base de données est mono-thread et FIFO, donc l'un des deux échouera toujours.
        var first = wallets.debit(player, 60, "TEST", null);
        var second = wallets.debit(player, 60, "TEST", null);

        boolean firstSuccess = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        boolean secondSuccess = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(firstSuccess ^ secondSuccess, "exactement un des deux débits doit réussir");
        assertEquals(40L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private UUID createPlayer(String name) throws Exception {
        UUID uuid = UUID.randomUUID();
        profiles.findOrCreate(uuid, name).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return uuid;
    }
}
