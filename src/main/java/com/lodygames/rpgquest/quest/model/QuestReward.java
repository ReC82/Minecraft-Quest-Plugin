package com.lodygames.rpgquest.quest.model;

public sealed interface QuestReward permits ExperienceReward, ItemReward, VariableReward, CommandReward {

    RewardType type();
}
