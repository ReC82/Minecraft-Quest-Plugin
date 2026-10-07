package com.lodygames.rpgquest.dialogue.model;

/**
 * Vraie quand il reste au moins un objet à remettre à ce PNJ pour une quête active du joueur
 * (issue #123) : c'est ce qui fait apparaître l'option « Donner les matériaux que j'ai » uniquement
 * quand elle a un sens, et jamais chez un PNJ qui n'attend rien.
 *
 * <p>Avec {@code negate: true} (voir {@link NegatedCondition}), la même condition exprime « ce PNJ
 * n'attend plus rien » — de quoi afficher un nœud de remerciement sans ajouter un second type de
 * condition.</p>
 *
 * <p>{@code npcId} optionnel, avec la même règle que {@link DeliverQuestItemsAction} : vide =
 * déduit de la clé du dialogue courant, ce qui garde la donnée générique.</p>
 */
public record PendingDeliveryCondition(String npcId) implements DialogueCondition {

    /** PNJ déduit du dialogue courant. */
    public PendingDeliveryCondition() {
        this(null);
    }

    public PendingDeliveryCondition {
        if (npcId != null && npcId.isBlank()) {
            npcId = null;
        }
    }

    @Override
    public ConditionType type() {
        return ConditionType.HAS_PENDING_DELIVERY;
    }
}
