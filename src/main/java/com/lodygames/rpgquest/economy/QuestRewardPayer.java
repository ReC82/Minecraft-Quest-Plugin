package com.lodygames.rpgquest.economy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Port minimal utilisé par le moteur de quêtes pour payer une récompense monétaire (issue #16).
 *
 * <p>Ce n'est <strong>pas</strong> un second service économique : l'unique implémentation de
 * production est {@link EconomyService}, qui reste la seule porte d'entrée du portefeuille. Cette
 * interface n'existe que pour une raison de conception : le moteur de quêtes n'a pas à connaître le
 * portefeuille, les transactions ni la base — il a seulement besoin de savoir si une occasion a été
 * payée. Elle rend aussi testable le comportement du moteur (payer une fois, ne jamais annoncer un
 * gain non crédité) sans monter une base de données, tandis que la garantie d'idempotence
 * elle-même est vérifiée là où elle vit vraiment : en SQL.</p>
 *
 * <p><strong>Le montant n'est pas un paramètre de paiement.</strong> Il est figé en base au moment
 * de la complétion et relu au moment du crédit : une quête rééditée entre-temps ne peut donc ni
 * augmenter ni réduire une dette déjà née.</p>
 */
public interface QuestRewardPayer {

    /**
     * Paie la dette identifiée par {@code grantId}, et une seule fois.
     *
     * <p>Rejouer le même {@code grantId} ne crédite rien. Le reçu dit ce qui s'est réellement
     * passé, y compris l'échec — jamais un succès supposé.</p>
     */
    CompletableFuture<QuestRewardReceipt> payQuestReward(UUID playerId, String grantId);

    /**
     * Récompenses monétaires encore dues pour ce joueur, les plus anciennes d'abord.
     *
     * <p>C'est le point d'entrée de la <strong>reprise</strong> : après un crash ou une panne SQL,
     * une dette survit ici avec son joueur, sa quête, son occurrence, son montant et son état.</p>
     */
    CompletableFuture<List<QuestRewardDue>> pendingQuestRewards(UUID playerId, int limit);

    /**
     * Enregistre un échec de paiement (compteur de tentatives + motif), pour <strong>borner</strong>
     * les reprises automatiques et afficher un état réel au lieu de réessayer en silence.
     */
    CompletableFuture<Void> recordQuestRewardFailure(String grantId, String error);

    /**
     * Implémentation inerte : ne paie jamais, ne connaît aucune dette, et le dit
     * ({@link QuestRewardReceipt.Status#FAILED}). Sert aux montages où l'économie n'est pas
     * disponible — une récompense monétaire y est alors visiblement non créditée, ce qui est
     * préférable à un succès silencieux et faux.
     */
    static QuestRewardPayer unavailable() {
        return new QuestRewardPayer() {
            @Override
            public CompletableFuture<QuestRewardReceipt> payQuestReward(UUID playerId, String grantId) {
                return CompletableFuture.completedFuture(QuestRewardReceipt.failed());
            }

            @Override
            public CompletableFuture<List<QuestRewardDue>> pendingQuestRewards(UUID playerId, int limit) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<Void> recordQuestRewardFailure(String grantId, String error) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
