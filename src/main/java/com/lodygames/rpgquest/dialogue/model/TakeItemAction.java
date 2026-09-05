package com.lodygames.rpgquest.dialogue.model;

import org.bukkit.Material;

public record TakeItemAction(Material material, int amount) implements DialogueAction {

    public TakeItemAction {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public ActionType type() {
        return ActionType.TAKE_ITEM;
    }
}
