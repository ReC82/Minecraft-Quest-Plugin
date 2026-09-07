package com.lodygames.rpgquest.dialogue.model;

/**
 * Inverse le résultat d'une autre condition : vrai quand {@link #inner()} est faux, et
 * inversement. Déclenchée par {@code negate: true} sur n'importe quelle condition dans le YAML
 * (voir {@code dialogue.DialogueDefinitionParser}).
 *
 * <p>Raison d'être : le moteur ne fournit que des conditions <em>positives</em> ({@link
 * VariableEqualsCondition}, {@link QuestStateCondition}…) et l'égalité de variable renvoie faux
 * quand la variable est absente — impossible sans négation d'exprimer « le déblocage n'a
 * <strong>pas</strong> eu lieu » (ex. dialogue de Jo : orienter un joueur dont {@code
 * CLAIM_TIER_1} n'est ni {@code "true"} ni même défini). Un enrobage générique plutôt qu'une
 * {@code VARIABLE_NOT_EQUALS} dédiée : réutilisable pour tout type de condition, présent et à
 * venir, sans nouvelle entrée dans {@link ConditionType}.</p>
 *
 * <p>La double négation est refusée à la construction : {@code negate: true} enrobe une seule
 * fois, jamais un {@link NegatedCondition} lui-même.</p>
 */
public record NegatedCondition(DialogueCondition inner) implements DialogueCondition {

    public NegatedCondition {
        if (inner == null) {
            throw new IllegalArgumentException("inner ne peut pas être nul.");
        }
        if (inner instanceof NegatedCondition) {
            throw new IllegalArgumentException("double négation interdite (negate ne s'applique qu'une fois).");
        }
    }

    /** Le type de la condition enrobée — {@code negate} ne change pas la <em>nature</em> de la condition, seulement son verdict. */
    @Override
    public ConditionType type() {
        return inner.type();
    }
}
