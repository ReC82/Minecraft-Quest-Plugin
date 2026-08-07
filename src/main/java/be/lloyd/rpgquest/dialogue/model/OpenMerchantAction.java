package be.lloyd.rpgquest.dialogue.model;

import org.bukkit.NamespacedKey;

/** Ferme le dialogue et ouvre la vitrine du marchand {@code merchantId} (voir {@code economy.merchant}). */
public record OpenMerchantAction(NamespacedKey merchantId) implements DialogueAction {

    @Override
    public ActionType type() {
        return ActionType.OPEN_MERCHANT;
    }
}
