package com.lodygames.rpgquest.dialogue.model;

/**
 * Vrai si le joueur possède déjà un claim principal (voir {@code claim.ClaimService#mainClaimOf}) —
 * strict opposé de {@link NoMainClaimCondition}, même source de vérité (jamais une variable dupliquée
 * qui pourrait diverger de l'état réel du système Claim). Utilisé par le dialogue de Jo pour ne
 * proposer « Me rendre sur ma propriété » qu'une fois un claim réellement posé.
 */
public record HasMainClaimCondition() implements DialogueCondition {

    @Override
    public ConditionType type() {
        return ConditionType.HAS_MAIN_CLAIM;
    }
}
