package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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

    // ---- Journal des transactions (issue #140) ---------------------------------------------

    @Test
    void historyIsEmptyForAnUntouchedWallet() throws Exception {
        UUID player = createPlayer("Steve");

        assertTrue(wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void everyCreditAndDebitLeavesASignedLedgerLine() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.credit(player, 100, "ADMIN_GRANT", "don initial").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        wallets.debit(player, 30, "MERCHANT_BUY", "achat pain").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<WalletRepository.LedgerEntry> history =
                wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Les plus récentes d'abord, et le MONTANT EST SIGNÉ : négatif pour un débit. Le journal
        // décrit le mouvement, il ne le réinterprète pas.
        assertEquals(2, history.size());
        assertEquals("MERCHANT_BUY", history.get(0).type());
        assertEquals(-30L, history.get(0).amount());
        assertEquals("achat pain", history.get(0).context());
        assertEquals("ADMIN_GRANT", history.get(1).type());
        assertEquals(100L, history.get(1).amount());
    }

    @Test
    void aRefusedDebitLeavesNoLedgerLineAtAll() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.credit(player, 10, "ADMIN_GRANT", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        boolean applied = wallets.debit(player, 50, "MERCHANT_BUY", "trop cher")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Un débit refusé ne doit laisser AUCUNE trace : sinon le journal raconterait une
        // transaction qui n'a pas eu lieu.
        assertFalse(applied);
        assertEquals(1, wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
        assertEquals(10L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void historyIsBoundedAndNeverReturnsEverything() throws Exception {
        UUID player = createPlayer("Steve");
        for (int i = 0; i < 12; i++) {
            wallets.credit(player, 1, "TEST", "op " + i).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertEquals(5, wallets.history(player, 5).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
        // Bornes dures : une demande absurde ne devient pas un transfert de masse.
        assertEquals(1, wallets.history(player, 0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
        assertEquals(12, wallets.history(player, 10_000).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void historyIsPerPlayerAndNeverLeaksAnotherWallet() throws Exception {
        UUID steve = createPlayer("Steve");
        UUID alex = createPlayer("Alex");
        wallets.credit(steve, 100, "ADMIN_GRANT", "pour Steve").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, wallets.history(steve, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
        assertTrue(wallets.history(alex, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void theLedgerSurvivesAReopeningOfTheDatabase() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.credit(player, 70, "ADMIN_GRANT", "avant fermeture").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        database.shutdown();
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        wallets = new WalletRepository(database);

        // Ni perte ni duplication après un redémarrage : c'est l'exigence explicite du ticket.
        assertEquals(70L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        List<WalletRepository.LedgerEntry> history =
                wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, history.size());
        assertEquals("avant fermeture", history.get(0).context());
    }
}
