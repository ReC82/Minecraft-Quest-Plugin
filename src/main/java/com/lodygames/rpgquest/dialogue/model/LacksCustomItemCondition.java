package com.lodygames.rpgquest.dialogue.model;

import org.bukkit.NamespacedKey;

/**
 * Vrai si le joueur ne possède <strong>aucun</strong> exemplaire de l'objet personnalisé
 * {@code itemId} (identifié via {@code item.YamlCustomItemRegistry#identify}, jamais par matériau
 * seul — contrairement à {@link HasItemCondition}, un objet vanilla du même matériau ne compte
 * jamais) — mission « ne pas permettre de farmer inutilement » (Pierre de retour, Acte de propriété
 * réutilisé comme visualiseur) : cache l'option de dialogue qui (re)donnerait l'objet tant que le
 * joueur en détient déjà un exemplaire.
 */
public record LacksCustomItemCondition(NamespacedKey itemId) implements DialogueCondition {

    public LacksCustomItemCondition {
        if (itemId == null) {
            throw new IllegalArgumentException("itemId est obligatoire.");
        }
    }

    @Override
    public ConditionType type() {
        return ConditionType.LACKS_CUSTOM_ITEM;
    }
}
