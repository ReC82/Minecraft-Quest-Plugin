package com.lodygames.rpgquest.economy;

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
 */
public interface QuestRewardPayer {

    /**
     * Crédite une fois, et une seule, la récompense monétaire d'une occurrence de complétion.
     *
     * @param playerId joueur à créditer
     * @param questId  identifiant de la quête ({@code rpgquest:ma_quete})
     * @param grantId  identifiant <strong>stable</strong> de cette occasion de paiement : rejouer le
     *                 même {@code grantId} ne doit jamais créditer deux fois
     * @param amount   montant strictement positif
     */
    CompletableFuture<QuestRewardReceipt> payQuestReward(UUID playerId, String questId, String grantId, long amount);

    /**
     * Implémentation inerte : ne paie jamais et le dit ({@link QuestRewardReceipt.Status#FAILED}).
     * Sert aux montages où l'économie n'est pas disponible — une récompense monétaire y est alors
     * visiblement non créditée, ce qui est préférable à un succès silencieux et faux.
     */
    static QuestRewardPayer unavailable() {
        return (playerId, questId, grantId, amount) ->
                CompletableFuture.completedFuture(QuestRewardReceipt.failed());
    }
}
