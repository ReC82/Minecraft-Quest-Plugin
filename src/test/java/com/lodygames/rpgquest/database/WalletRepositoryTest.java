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

    // ---- Récompenses monétaires de quête (issue #16) ------------------------------------------
    //
    // C'est ICI que vit la garantie du ticket (« jamais créditée deux fois, ni perdue après une
    // réussite annoncée ») : elle est en SQL, pas dans le moteur de quêtes. D'où des tests sur une
    // vraie base SQLite plutôt que sur un double.

    @Test
    void aQuestRewardIsCreditedOnceAndRecordedInTheLedger() throws Exception {
        UUID player = createPlayer("Steve");

        WalletRepository.QuestRewardGrant grant = wallets
                .creditQuestReward(player, "rpgquest:tc250", "grant-1", 250, "QUEST_REWARD", "quest:rpgquest:tc250#grant-1")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(grant.credited());
        assertEquals(250L, grant.amount());
        assertEquals(250L, grant.balanceAfter());
        assertEquals(1, grant.occurrence());
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        List<WalletRepository.LedgerEntry> history =
                wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, history.size());
        assertEquals("QUEST_REWARD", history.get(0).type());
        // Traçable jusqu'à la quête ET à l'occasion : une ligne de journal reste rattachable des
        // mois plus tard, au lieu d'être un « gain de jeu » anonyme.
        assertEquals("quest:rpgquest:tc250#grant-1", history.get(0).context());
    }

    @Test
    void replayingTheSameGrantNeverCreditsTwice() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.creditQuestReward(player, "rpgquest:tc250", "grant-1", 250, "QUEST_REWARD", "ctx")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Le même grant_id rejoué : double-clic, retry, relecture d'un événement, peu importe.
        WalletRepository.QuestRewardGrant replay = wallets
                .creditQuestReward(player, "rpgquest:tc250", "grant-1", 250, "QUEST_REWARD", "ctx")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(replay.credited(), "une occasion déjà payée ne doit jamais recréditer");
        assertEquals(1, replay.occurrence());
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // Et surtout : pas de seconde ligne au journal, sinon l'audit mentirait.
        assertEquals(1, wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void replayingWithADifferentAmountStillPaysNothingAndReportsTheOriginalAmount() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.creditQuestReward(player, "rpgquest:tc250", "grant-1", 250, "QUEST_REWARD", "ctx")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // La quête a été rééditée entre-temps : le montant demandé aujourd'hui n'est plus celui
        // qui a été payé. On doit lire la réalité, pas la demande.
        WalletRepository.QuestRewardGrant replay = wallets
                .creditQuestReward(player, "rpgquest:tc250", "grant-1", 999, "QUEST_REWARD", "ctx")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(replay.credited());
        assertEquals(250L, replay.amount(), "le reçu doit porter le montant réellement crédité");
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aRepeatableQuestPaysAgainOnANewOccurrence() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.creditQuestReward(player, "rpgquest:daily", "grant-1", 50, "QUEST_REWARD", "ctx1")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        WalletRepository.QuestRewardGrant second = wallets
                .creditQuestReward(player, "rpgquest:daily", "grant-2", 50, "QUEST_REWARD", "ctx2")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(second.credited(), "une nouvelle complétion est une nouvelle occasion : elle se paie");
        assertEquals(2, second.occurrence(), "les occurrences se numérotent par joueur et par quête");
        assertEquals(100L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void occurrencesAreCountedPerQuestAndPerPlayer() throws Exception {
        UUID steve = createPlayer("Steve");
        UUID alex = createPlayer("Alex");
        wallets.creditQuestReward(steve, "rpgquest:a", "g1", 10, "QUEST_REWARD", "c")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Autre quête du même joueur, et même quête d'un autre joueur : deux premières fois.
        assertEquals(1, wallets.creditQuestReward(steve, "rpgquest:b", "g2", 10, "QUEST_REWARD", "c")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).occurrence());
        assertEquals(1, wallets.creditQuestReward(alex, "rpgquest:a", "g3", 10, "QUEST_REWARD", "c")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).occurrence());
    }

    @Test
    void aQuestRewardRefusesANonPositiveAmountAndAMissingGrantId() throws Exception {
        UUID player = createPlayer("Steve");
        assertThrows(ExecutionException.class, () -> wallets
                .creditQuestReward(player, "rpgquest:tc250", "g", 0, "QUEST_REWARD", "c")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> wallets
                .creditQuestReward(player, "rpgquest:tc250", "g", -5, "QUEST_REWARD", "c")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // Sans grant_id, l'idempotence n'existe plus : refuser est la seule réponse honnête.
        assertThrows(ExecutionException.class, () -> wallets
                .creditQuestReward(player, "rpgquest:tc250", "  ", 10, "QUEST_REWARD", "c")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aQuestRewardAddsToAnExistingBalanceWithoutReplacingIt() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.credit(player, 40, "ADMIN_GRANT", "solde de départ").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        WalletRepository.QuestRewardGrant grant = wallets
                .creditQuestReward(player, "rpgquest:tc250", "g1", 60, "QUEST_REWARD", "c")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(100L, grant.balanceAfter(), "le solde est relu dans la transaction, jamais estimé");
        assertEquals(100L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aQuestRewardAndItsClaimBothSurviveARestart() throws Exception {
        UUID player = createPlayer("Steve");
        wallets.creditQuestReward(player, "rpgquest:tc250", "grant-1", 250, "QUEST_REWARD", "ctx")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        database.shutdown();
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        wallets = new WalletRepository(database);

        // Ni perte (le solde est là) ni duplication (le rejeu ne paie pas) après redémarrage —
        // les deux moitiés de l'exigence, vérifiées ensemble parce qu'elles ne valent qu'ensemble.
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(wallets.creditQuestReward(player, "rpgquest:tc250", "grant-1", 250, "QUEST_REWARD", "ctx")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).credited());
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void manyConcurrentReplaysOfTheSameGrantCreditExactlyOnce() throws Exception {
        UUID player = createPlayer("Steve");

        // Vingt demandes identiques lancées sans attendre : c'est la forme d'un double-clic ou
        // d'un retry en rafale. L'exécuteur de base est séquentiel, mais ce qui est vérifié ici
        // c'est le RÉSULTAT, pas l'ordonnancement.
        List<java.util.concurrent.CompletableFuture<WalletRepository.QuestRewardGrant>> calls =
                new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(wallets.creditQuestReward(player, "rpgquest:tc250", "grant-1", 250,
                    "QUEST_REWARD", "ctx"));
        }
        long credited = 0;
        for (var call : calls) {
            if (call.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).credited()) {
                credited++;
            }
        }

        assertEquals(1, credited, "une seule des 20 demandes identiques doit créditer");
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, wallets.history(player, 50).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }
}
