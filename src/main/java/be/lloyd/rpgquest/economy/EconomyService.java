package be.lloyd.rpgquest.economy;

import be.lloyd.rpgquest.database.WalletRepository;
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
public final class EconomyService {

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

    /** Outil admin : fixe le solde exact, jamais négatif. */
    public CompletableFuture<Void> adminSet(UUID playerId, long amount, String context) {
        return wallets.setBalance(playerId, amount, TransactionType.ADMIN_SET.name(), context);
    }
}
