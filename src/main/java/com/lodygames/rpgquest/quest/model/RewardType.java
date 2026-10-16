package com.lodygames.rpgquest.quest.model;

public enum RewardType {
    EXPERIENCE,
    ITEM,
    VARIABLE,
    COMMAND,
    /** Crédite le portefeuille persistant du joueur (issue #16) — voir {@link MoneyReward}. */
    MONEY,
    /** Cœur(s) de vie maximum permanent(s), une fois par joueur et par clé (issue #166) — voir {@link HeartReward}. */
    HEART,
    /** Tirage d'une table de récompenses aléatoires de entitlements.yml (issue #177) — voir {@link TableReward}. */
    TABLE
}
