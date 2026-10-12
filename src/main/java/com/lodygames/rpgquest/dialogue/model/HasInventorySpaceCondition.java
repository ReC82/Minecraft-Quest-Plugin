package com.lodygames.rpgquest.dialogue.model;

/**
 * Vraie si le joueur a au moins {@code slots} emplacements LIBRES dans son inventaire principal
 * (issue #85).
 *
 * <p>Sert à ne proposer une remise d'objet non empilable — l'Acte de propriété de Jo — que si elle
 * peut réellement aboutir : sinon l'objet partirait au sol, et le joueur croirait le dialogue cassé.
 * Comme toute condition de choix, elle est réévaluée au clic.</p>
 */
public record HasInventorySpaceCondition(int slots) implements DialogueCondition {

    public static final int MAX_SLOTS = 36;

    public HasInventorySpaceCondition {
        if (slots < 1 || slots > MAX_SLOTS) {
            throw new IllegalArgumentException("slots doit être entre 1 et " + MAX_SLOTS + ".");
        }
    }

    @Override
    public ConditionType type() {
        return ConditionType.HAS_INVENTORY_SPACE;
    }
}
