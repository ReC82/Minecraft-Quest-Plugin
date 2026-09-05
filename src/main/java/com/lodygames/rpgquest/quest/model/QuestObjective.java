package com.lodygames.rpgquest.quest.model;

public sealed interface QuestObjective
        permits BreakBlockObjective, PlaceBlockObjective, KillEntityObjective,
                CollectItemObjective, CraftItemObjective, TalkToNpcObjective, ReachLocationObjective {

    ObjectiveType type();

    /** Quantité requise pour considérer cet objectif comme rempli (1 pour les objectifs binaires). */
    static int requiredAmount(QuestObjective objective) {
        return switch (objective) {
            case BreakBlockObjective o -> o.amount();
            case PlaceBlockObjective o -> o.amount();
            case KillEntityObjective o -> o.amount();
            case CollectItemObjective o -> o.amount();
            case CraftItemObjective o -> o.amount();
            case TalkToNpcObjective o -> 1;
            case ReachLocationObjective o -> 1;
        };
    }

    /** Description humaine courte (français), utilisée par les commandes et l'UI du journal de quêtes. */
    static String describe(QuestObjective objective) {
        return switch (objective) {
            case BreakBlockObjective o -> "Casser " + o.material();
            case PlaceBlockObjective o -> "Placer " + o.material();
            case KillEntityObjective o -> "Tuer " + o.entity();
            case CollectItemObjective o -> "Collecter " + o.material();
            case CraftItemObjective o -> "Fabriquer " + o.material();
            case TalkToNpcObjective o -> "Parler à " + o.npcId();
            case ReachLocationObjective o -> "Se rendre dans " + o.world();
        };
    }
}
