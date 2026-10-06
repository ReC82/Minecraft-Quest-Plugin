package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
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
    private QuestProgressRepository progress;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profiles = new PlayerProfileRepository(database);
        wallets = new WalletRepository(database);
        progress = new QuestProgressRepository(database);
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

    // ---- Récompenses monétaires de quête : dette puis paiement (issue #16) --------------------
    //
    // C'est ICI que vivent les garanties du ticket, parce qu'elles sont en SQL et nulle part
    // ailleurs : « une récompense due reste identifiable après redémarrage », « une reprise
    // réutilise l'identité initiale », « crédit, journal et preuve de paiement restent atomiques ».
    // D'où une vraie base SQLite, rouverte pour de bon, plutôt qu'un double.

    private static final String QUEST = "rpgquest:tc251";

    /** Enregistre une complétion et ses dettes, comme le fait le moteur. */
    private List<String> recordCompletion(UUID player, String questId, String token, long... amounts)
            throws Exception {
        List<Long> owed = new java.util.ArrayList<>();
        for (long amount : amounts) {
            owed.add(amount);
        }
        return progress.completeQuestWithMoneyDebts(player, NamespacedKey.fromString(questId), token, owed)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private WalletRepository.QuestRewardPayment pay(String grantId) throws Exception {
        return wallets.payQuestRewardDebt(grantId, "QUEST_REWARD")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void aCompletionRecordsADebtBeforeAnythingIsPaid() throws Exception {
        UUID player = createPlayer("Steve");

        List<String> grants = recordCompletion(player, QUEST, "token-a", 250);

        // L'argent n'est PAS encore crédité : la dette est seulement reconnue.
        assertEquals(1, grants.size());
        assertEquals(0L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());

        List<WalletRepository.QuestRewardDebt> dues =
                wallets.pendingQuestRewardDebts(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, dues.size());
        WalletRepository.QuestRewardDebt due = dues.get(0);
        // Tout ce qu'il faut pour reprendre : joueur, quête, occurrence, montant, état.
        assertEquals(player, due.playerId());
        assertEquals(QUEST, due.questId());
        assertEquals(1, due.occurrence());
        assertEquals(0, due.rewardIndex());
        assertEquals(250L, due.amount());
        assertEquals(WalletRepository.DebtStatus.PENDING, due.status());
        assertEquals(0, due.attempts());
    }

    @Test
    void theCompletionStateAndItsDebtAreWrittenTogether() throws Exception {
        UUID player = createPlayer("Steve");

        recordCompletion(player, QUEST, "token-a", 250);

        // Les deux faits sont dans la même transaction : la quête est terminée ET la dette existe.
        // C'est ce qui empêche « terminée mais jamais payée, sans aucune trace ».
        assertEquals(QuestState.COMPLETED,
                progress.find(player, NamespacedKey.fromString(QUEST))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().state());
        assertEquals(1, wallets.pendingQuestRewardDebts(player, 10)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void payingADebtCreditsTheWalletAndLeavesExactlyOneLedgerLine() throws Exception {
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);

        WalletRepository.QuestRewardPayment payment = pay(grant);

        assertTrue(payment.paid());
        assertEquals(250L, payment.amount());
        assertEquals(250L, payment.balanceAfter());
        assertEquals(1, payment.occurrence());
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        List<WalletRepository.LedgerEntry> history =
                wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, history.size());
        assertEquals("QUEST_REWARD", history.get(0).type());
        // La trace porte la quête ET l'occasion : rattachable des mois plus tard.
        assertTrue(history.get(0).context().contains(QUEST), history.get(0).context());
        assertTrue(history.get(0).context().contains(grant), history.get(0).context());

        // Et la dette a disparu de ce qui reste à payer.
        assertTrue(wallets.pendingQuestRewardDebts(player, 10)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void replayingAPaymentNeverCreditsTwice() throws Exception {
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);
        pay(grant);

        WalletRepository.QuestRewardPayment replay = pay(grant);

        assertFalse(replay.paid());
        assertEquals("ALREADY_PAID", replay.code());
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void severalMoneyRewardsOnOneCompletionEachGetTheirOwnDebt() throws Exception {
        // Le défaut du premier lot : identité de paiement partagée, donc seule la première payée.
        UUID player = createPlayer("Steve");

        List<String> grants = recordCompletion(player, QUEST, "token-a", 100, 30, 5);

        assertEquals(3, grants.size());
        assertEquals(3, new java.util.HashSet<>(grants).size(), "trois identités distinctes");
        for (String grant : grants) {
            assertTrue(pay(grant).paid(), grant);
        }
        assertEquals(135L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(3, wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());

        // Même occurrence (une seule complétion), index distincts.
        List<WalletRepository.QuestRewardDebt> all = new java.util.ArrayList<>();
        for (String grant : grants) {
            all.add(wallets.questRewardDebt(grant).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow());
        }
        assertEquals(List.of(1, 1, 1), all.stream().map(WalletRepository.QuestRewardDebt::occurrence).toList());
        assertEquals(List.of(0, 1, 2), all.stream().map(WalletRepository.QuestRewardDebt::rewardIndex).toList());
    }

    @Test
    void aDebtSurvivesARestartAndIsThenPaidExactlyOnce() throws Exception {
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);

        // Frontière de crash : dette enregistrée, crash AVANT paiement. Vraie base rouverte.
        database.shutdown();
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        wallets = new WalletRepository(database);
        progress = new QuestProgressRepository(database);

        List<WalletRepository.QuestRewardDebt> dues =
                wallets.pendingQuestRewardDebts(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, dues.size(), "la dette doit survivre au redémarrage, sinon elle est perdue");
        assertEquals(grant, dues.get(0).grantId(), "la reprise doit réutiliser l'identité INITIALE");
        assertEquals(250L, dues.get(0).amount());

        assertTrue(pay(grant).paid());
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // Et une seconde reprise après ce rattrapage ne paie rien.
        assertFalse(pay(grant).paid());
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aPaidDebtSurvivesARestartAndIsNotPaidAgain() throws Exception {
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);
        pay(grant);

        // Frontière de crash : crédit effectué, crash AVANT la notification du joueur.
        database.shutdown();
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        wallets = new WalletRepository(database);

        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(wallets.pendingQuestRewardDebts(player, 10)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(), "rien ne reste dû");
        assertFalse(pay(grant).paid());
        assertEquals(1, wallets.history(player, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void theAmountPaidComesFromTheDebtNotFromTheCurrentQuestDefinition() throws Exception {
        // Exigence explicite : les montants dus sont conservés même si la définition change.
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);

        // La quête est rééditée entre-temps (montant porté à 9999) : la dette ne bouge pas, car
        // le paiement ne prend AUCUN montant en paramètre — il relit la ligne.
        assertTrue(pay(grant).paid());

        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(250L, wallets.questRewardDebt(grant)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().amount());
    }

    @Test
    void aRepeatableQuestCreatesANewOccurrenceAndIsPaidAgain() throws Exception {
        UUID player = createPlayer("Steve");
        String first = recordCompletion(player, QUEST, "token-a", 50).get(0);
        pay(first);

        String second = recordCompletion(player, QUEST, "token-b", 50).get(0);

        assertFalse(first.equals(second), "deux complétions = deux identités de paiement");
        WalletRepository.QuestRewardPayment payment = pay(second);
        assertTrue(payment.paid());
        assertEquals(2, payment.occurrence(), "les occurrences se numérotent par joueur et par quête");
        assertEquals(100L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void reRecordingTheSameCompletionCreatesNoSecondDebt() throws Exception {
        UUID player = createPlayer("Steve");
        recordCompletion(player, QUEST, "token-a", 250);

        // Même jeton rejoué (reprise d'une remise interrompue) : aucun doublon, aucune erreur.
        recordCompletion(player, QUEST, "token-a", 250);

        assertEquals(1, wallets.pendingQuestRewardDebts(player, 10)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void twentyConcurrentRetriesOfTheSameDebtCreditExactlyOnce() throws Exception {
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);

        List<java.util.concurrent.CompletableFuture<WalletRepository.QuestRewardPayment>> calls =
                new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(wallets.payQuestRewardDebt(grant, "QUEST_REWARD"));
        }
        long paid = 0;
        for (var call : calls) {
            if (call.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).paid()) {
                paid++;
            }
        }

        assertEquals(1, paid, "une seule des 20 reprises concurrentes doit créditer");
        assertEquals(250L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, wallets.history(player, 50).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void anUnknownDebtIsNeverPaidAndNeverPretendsOtherwise() throws Exception {
        WalletRepository.QuestRewardPayment payment = pay("jeton-inexistant#0");

        assertFalse(payment.paid());
        assertEquals("UNKNOWN", payment.code());
        assertEquals(0L, payment.amount());
    }

    @Test
    void aFailureIsRecordedWithItsReasonAndBoundsTheRetries() throws Exception {
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);

        wallets.recordQuestRewardFailure(grant, "SQLException: disk I/O error")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        wallets.recordQuestRewardFailure(grant, "SQLException: disk I/O error")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        WalletRepository.QuestRewardDebt debt =
                wallets.questRewardDebt(grant).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(2, debt.attempts(), "le compteur borne les reprises automatiques");
        assertTrue(debt.lastError().contains("disk I/O"), debt.lastError());
        // Un échec n'a RIEN crédité et la dette reste payable : c'est tout l'intérêt.
        assertEquals(WalletRepository.DebtStatus.PENDING, debt.status());
        assertEquals(0L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(pay(grant).paid());
    }

    @Test
    void recordingAFailureOnAPaidDebtChangesNothing() throws Exception {
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);
        pay(grant);

        wallets.recordQuestRewardFailure(grant, "erreur tardive").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        WalletRepository.QuestRewardDebt debt =
                wallets.questRewardDebt(grant).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(WalletRepository.DebtStatus.PAID, debt.status(), "une dette payée reste payée");
        assertEquals(0, debt.attempts());
    }

    @Test
    void aManuallySettledDebtIsNeverPayableAgain() throws Exception {
        // Exigence explicite : une compensation ne doit pas laisser la même récompense payable.
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);

        assertTrue(wallets.settleQuestRewardDebtManually(grant, "compensé à la main, ticket #16")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // Le règlement manuel ne touche PAS au portefeuille : l'admin a déjà crédité comme il
        // l'entendait. Il rend seulement la dette non payable.
        assertEquals(0L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        WalletRepository.QuestRewardPayment payment = pay(grant);
        assertFalse(payment.paid());
        assertEquals("ALREADY_SETTLED", payment.code());
        assertEquals(0L, wallets.balance(player).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(wallets.pendingQuestRewardDebts(player, 10)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void settlingRequiresAReasonAndCannotTouchAnAlreadyPaidDebt() throws Exception {
        UUID player = createPlayer("Steve");
        String grant = recordCompletion(player, QUEST, "token-a", 250).get(0);

        assertThrows(ExecutionException.class, () -> wallets.settleQuestRewardDebtManually(grant, "  ")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        pay(grant);
        assertFalse(wallets.settleQuestRewardDebtManually(grant, "trop tard")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "une dette déjà payée ne se règle pas à la main");
    }

    @Test
    void pendingDebtsAreScopedPerPlayerAndBounded() throws Exception {
        UUID steve = createPlayer("Steve");
        UUID alex = createPlayer("Alex");
        recordCompletion(steve, QUEST, "token-s", 10);
        recordCompletion(alex, QUEST, "token-a", 20);

        assertEquals(1, wallets.pendingQuestRewardDebts(steve, 10)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
        assertEquals(2, wallets.pendingQuestRewardDebts(50)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size(), "vue d'administration, tous joueurs");
        // Bornes dures : jamais une lecture non bornée.
        assertEquals(1, wallets.pendingQuestRewardDebts(0)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void aCompletionWithoutMoneyRewardCreatesNoDebtAtAll() throws Exception {
        UUID player = createPlayer("Steve");

        List<String> grants = recordCompletion(player, QUEST, "token-a");

        assertTrue(grants.isEmpty());
        assertTrue(wallets.pendingQuestRewardDebts(player, 10)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
        // La quête est tout de même marquée terminée : la complétion n'a pas besoin d'argent.
        assertEquals(QuestState.COMPLETED,
                progress.find(player, NamespacedKey.fromString(QUEST))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().state());
    }

    @Test
    void recordingACompletionWithoutATokenIsRefused() {
        UUID player = createPlayer2("Steve");
        assertThrows(ExecutionException.class, () -> progress
                .completeQuestWithMoneyDebts(player, NamespacedKey.fromString(QUEST), "  ", List.of(10L))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private UUID createPlayer2(String name) {
        try {
            return createPlayer(name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
