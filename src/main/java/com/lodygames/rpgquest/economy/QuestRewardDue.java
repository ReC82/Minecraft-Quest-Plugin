package com.lodygames.rpgquest.economy;

/**
 * Une récompense monétaire de quête <strong>encore due</strong> (issue #16, second lot).
 *
 * <p>Tout ce qu'il faut pour la reprendre sans rien inventer : le joueur, la quête, l'occurrence de
 * complétion, le montant <em>figé à la complétion</em>, et son état réel (nombre de tentatives,
 * dernier motif d'échec).</p>
 *
 * <p>{@code grantId} est l'<strong>identité de paiement initiale</strong> : une reprise la réutilise
 * telle quelle. C'est ce qui empêche une reprise de payer une seconde fois — jamais un nouvel
 * identifiant.</p>
 *
 * @param attempts  nombre d'échecs déjà enregistrés sur cette dette ({@code 0} = jamais tentée en
 *                  échec)
 * @param lastError dernier motif d'échec, ou {@code null} si aucun
 */
public record QuestRewardDue(String grantId, String questId, int occurrence, int rewardIndex,
                             long amount, int attempts, String lastError) {
}
