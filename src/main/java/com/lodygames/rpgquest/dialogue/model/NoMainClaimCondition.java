package com.lodygames.rpgquest.dialogue.model;

/**
 * Vrai si le joueur ne possède encore aucun claim (voir {@code claim.ClaimService#claimsOwnedBy}) —
 * aucun paramètre : contrairement à {@link VariableEqualsCondition}, la source de vérité est
 * directement la liste des claims du joueur, jamais une variable dupliquée qui pourrait diverger de
 * l'état réel du système Claim.
 */
public record NoMainClaimCondition() implements DialogueCondition {

    @Override
    public ConditionType type() {
        return ConditionType.NO_MAIN_CLAIM;
    }
}
