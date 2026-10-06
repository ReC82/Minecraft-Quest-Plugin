package com.lodygames.rpgquest.economy;

import com.lodygames.rpgquest.database.WalletRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Couche métier au-dessus de {@link WalletRepository} : traduit les demandes
 * (paiement entre joueurs, débit/crédit de marchand, réglage admin) en
 * appels typés, sans exposer directement les chaînes {@code type}/{@code
 * context} brutes de la base aux appelants (commandes, marchands). Classe
 * simple sans cycle de vie propre (pas de {@code PluginService}) — même
 * conception que {@code PlayerProfileService}.
 */
public final class EconomyService implements QuestRewardPayer {

    private final WalletRepository wallets;

    public EconomyService(WalletRepository wallets) {
        this.wallets = wallets;
    }

    public CompletableFuture<Long> balance(UUID playerId) {
        return wallets.balance(playerId);
    }

    /** {@code true} si le débit a eu lieu (fonds suffisants), {@code false} sinon — rien n'est modifié dans ce cas. */
    public CompletableFuture<Boolean> debit(UUID playerId, long amount, TransactionType type, String context) {
        return wallets.debit(playerId, amount, type.name(), context);
    }

    public CompletableFuture<Void> credit(UUID playerId, long amount, TransactionType type, String context) {
        return wallets.credit(playerId, amount, type.name(), context);
    }

    /** Transfert atomique. Valide {@code amount}/{@code from != to} avant de toucher la base. */
    public CompletableFuture<PayOutcome> pay(UUID from, UUID to, long amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(PayOutcome.INVALID_AMOUNT);
        }
        if (from.equals(to)) {
            return CompletableFuture.completedFuture(PayOutcome.SAME_PLAYER);
        }
        return wallets.pay(from, to, amount, null)
                .thenApply(success -> success ? PayOutcome.PAID : PayOutcome.INSUFFICIENT_FUNDS);
    }

    /**
     * Paie une récompense monétaire de quête (issue #16), <strong>au plus une fois par occasion</strong>.
     *
     * <p>Le {@code context} écrit au journal des transactions porte la quête <em>et</em> l'occasion
     * ({@code quest:<id>#<grantId>}) et il est composé <strong>depuis la ligne de dette</strong> :
     * des mois plus tard, une ligne de journal reste rattachable à une complétion précise, et non à
     * un vague « gain de jeu ». Le montant vient lui aussi de la dette, jamais de l'appelant — une
     * quête rééditée ne change donc pas ce qui est dû. Une erreur de persistance laisse le futur <strong>en échec</strong> plutôt que de
     * fabriquer un reçu : rien n'est crédité, la dette reste payable, et l'appelant doit traduire
     * cela en {@link QuestRewardReceipt.Status#FAILED} après avoir journalisé la cause.</p>
     */
    @Override
    public CompletableFuture<QuestRewardReceipt> payQuestReward(UUID playerId, String grantId) {
        return wallets.payQuestRewardDebt(grantId, TransactionType.QUEST_REWARD.name())
                .thenApply(payment -> new QuestRewardReceipt(switch (payment.code()) {
                    case "PAID" -> QuestRewardReceipt.Status.CREDITED;
                    case "ALREADY_PAID", "ALREADY_SETTLED" -> QuestRewardReceipt.Status.ALREADY_CREDITED;
                    // Dette inconnue : ne JAMAIS annoncer un gain. C'est un échec, et il doit se voir.
                    default -> QuestRewardReceipt.Status.FAILED;
                }, payment.amount(), payment.balanceAfter(), payment.occurrence()));
    }

    @Override
    public CompletableFuture<List<QuestRewardDue>> pendingQuestRewards(UUID playerId, int limit) {
        return wallets.pendingQuestRewardDebts(playerId, limit).thenApply(EconomyService::toDue);
    }

    @Override
    public CompletableFuture<Void> recordQuestRewardFailure(String grantId, String error) {
        return wallets.recordQuestRewardFailure(grantId, error);
    }

    /** Vue d'administration : toutes les récompenses monétaires encore dues, tous joueurs confondus. */
    public CompletableFuture<List<QuestRewardDue>> allPendingQuestRewards(int limit) {
        return wallets.pendingQuestRewardDebts(limit).thenApply(EconomyService::toDue);
    }

    /**
     * Marque une dette comme réglée à la main (compensation administrative). Ne touche pas au
     * portefeuille : c'est l'administrateur qui a crédité comme il l'entendait, et cette opération
     * empêche la même récompense d'être payée une seconde fois par une reprise.
     */
    public CompletableFuture<Boolean> settleQuestRewardManually(String grantId, String reason) {
        return wallets.settleQuestRewardDebtManually(grantId, reason);
    }

    /** Dette précise, pour afficher son état réel ou vérifier qu'elle concerne bien un joueur donné. */
    public CompletableFuture<Optional<WalletRepository.QuestRewardDebt>> questRewardDebt(String grantId) {
        return wallets.questRewardDebt(grantId);
    }

    private static List<QuestRewardDue> toDue(List<WalletRepository.QuestRewardDebt> debts) {
        List<QuestRewardDue> out = new ArrayList<>();
        for (WalletRepository.QuestRewardDebt debt : debts) {
            out.add(new QuestRewardDue(debt.grantId(), debt.questId(), debt.occurrence(),
                    debt.rewardIndex(), debt.amount(), debt.attempts(), debt.lastError()));
        }
        return out;
    }

    /** Outil admin : fixe le solde exact, jamais négatif. */
    public CompletableFuture<Void> adminSet(UUID playerId, long amount, String context) {
        return wallets.setBalance(playerId, amount, TransactionType.ADMIN_SET.name(), context);
    }
}
