package be.lloyd.rpgquest.dialogue.model;

import org.bukkit.Material;

public record GiveItemAction(Material material, int amount) implements DialogueAction {

    public GiveItemAction {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public ActionType type() {
        return ActionType.GIVE_ITEM;
    }
}
