package be.lloyd.rpgquest.quest.model;

public sealed interface QuestObjective
        permits BreakBlockObjective, PlaceBlockObjective, KillEntityObjective,
                CollectItemObjective, CraftItemObjective, TalkToNpcObjective, ReachLocationObjective {

    ObjectiveType type();
}
