package com.lodygames.rpgquest.economy;

import com.lodygames.rpgquest.database.WalletRepository;
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
     * Récompense monétaire de quête (issue #16), créditée <strong>au plus une fois par occasion</strong>.
     *
     * <p>Le {@code context} écrit au journal des transactions porte la quête <em>et</em> l'occasion
     * ({@code quest:<id>#<grantId>}) : des mois plus tard, une ligne de journal reste rattachable à
     * une complétion précise, et non à un vague « gain de jeu ». Une erreur de persistance laisse
     * le futur <strong>en échec</strong> plutôt que de fabriquer un reçu : rien n'est crédité, rien
     * n'est réservé, et l'appelant doit traduire cela en
     * {@link QuestRewardReceipt.Status#FAILED} après avoir journalisé la cause.</p>
     */
    @Override
    public CompletableFuture<QuestRewardReceipt> payQuestReward(UUID playerId, String questId, String grantId,
                                                                long amount) {
        return wallets.creditQuestReward(playerId, questId, grantId, amount,
                        TransactionType.QUEST_REWARD.name(), "quest:" + questId + "#" + grantId)
                .thenApply(grant -> new QuestRewardReceipt(
                        grant.credited() ? QuestRewardReceipt.Status.CREDITED
                                : QuestRewardReceipt.Status.ALREADY_CREDITED,
                        grant.amount(), grant.balanceAfter(), grant.occurrence()));
    }

    /** Outil admin : fixe le solde exact, jamais négatif. */
    public CompletableFuture<Void> adminSet(UUID playerId, long amount, String context) {
        return wallets.setBalance(playerId, amount, TransactionType.ADMIN_SET.name(), context);
    }
}
