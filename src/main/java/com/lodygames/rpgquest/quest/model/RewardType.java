package com.lodygames.rpgquest.quest.model;

public enum RewardType {
    EXPERIENCE,
    ITEM,
    VARIABLE,
    COMMAND,
    /** Crédite le portefeuille persistant du joueur (issue #16) — voir {@link MoneyReward}. */
    MONEY
}
