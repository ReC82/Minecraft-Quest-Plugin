package com.lodygames.rpgquest.quest.model;

import java.util.regex.Pattern;

/**
 * Récompense « Tirage » (issue #177) : tire la table {@code tableId} de {@code entitlements.yml} une
 * fois par complétion. Le tirage est figé avec la complétion (même transaction) et chaque gain devient
 * un droit à récompense remis au plus une fois ; il se combine librement avec les récompenses garanties.
 */
public record TableReward(String tableId) implements QuestReward {

    /** Même forme que l'identifiant d'une table du catalogue. */
    public static final Pattern TABLE_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    public TableReward {
        if (tableId == null || !TABLE_ID.matcher(tableId).matches()) {
            throw new IllegalArgumentException("identifiant de table invalide : " + tableId);
        }
    }

    @Override
    public RewardType type() {
        return RewardType.TABLE;
    }
}
