package com.lodygames.rpgquest.quest.model;

public sealed interface QuestReward permits ExperienceReward, ItemReward, VariableReward, CommandReward, MoneyReward {

    RewardType type();
}
