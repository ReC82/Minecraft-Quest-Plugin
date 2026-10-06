package com.lodygames.rpgquest.economy;

/**
 * Ce qui s'est <strong>réellement</strong> passé quand une quête a tenté de payer sa récompense
 * monétaire (issue #16).
 *
 * <p>Le type existe pour une seule raison : empêcher d'annoncer au joueur un gain qui n'a pas eu
 * lieu. {@link Status#CREDITED} est le seul état qui autorise un message de réussite ;
 * {@link Status#ALREADY_CREDITED} dit que cette occurrence avait déjà été payée (donc : ne rien
 * annoncer, et surtout ne pas repayer) ; {@link Status#FAILED} dit que rien n'a été crédité, ce qui
 * doit se voir honnêtement plutôt que de disparaître.</p>
 *
 * @param status       issue réelle, jamais déduite
 * @param amount       montant de l'occurrence (0 si {@code FAILED})
 * @param balanceAfter solde après l'opération, relu en base — {@code 0} si {@code FAILED}
 * @param occurrence   numéro de complétion payée pour ce joueur et cette quête (1 = la première)
 */
public record QuestRewardReceipt(Status status, long amount, long balanceAfter, int occurrence) {

    public enum Status {
        /** Le crédit vient d'avoir lieu : c'est le seul cas où un gain peut être annoncé. */
        CREDITED,
        /** Cette occurrence était déjà payée : rien n'a bougé, et c'est le comportement voulu. */
        ALREADY_CREDITED,
        /** Rien n'a été crédité (erreur de persistance). Ne jamais présenter cela comme un succès. */
        FAILED
    }

    public static QuestRewardReceipt failed() {
        return new QuestRewardReceipt(Status.FAILED, 0L, 0L, 0);
    }

    public boolean credited() {
        return status == Status.CREDITED;
    }
}
