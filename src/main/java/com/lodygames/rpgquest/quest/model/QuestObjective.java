package com.lodygames.rpgquest.quest.model;

import java.util.Optional;
import java.util.function.Function;

public sealed interface QuestObjective
        permits BreakBlockObjective, PlaceBlockObjective, KillEntityObjective,
                CollectItemObjective, CraftItemObjective, TalkToNpcObjective, ReachLocationObjective,
                DeliverItemToNpcObjective, SmeltItemObjective, DiscoverWaypointObjective,
                DiscoverCityObjective, KillQuestBossObjective {

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
            case DeliverItemToNpcObjective o -> o.amount();
            case SmeltItemObjective o -> o.amount();
            case DiscoverWaypointObjective o -> o.amount();
            case DiscoverCityObjective o -> o.amount();
            case KillQuestBossObjective o -> 1;
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
            case DeliverItemToNpcObjective o -> "Remettre " + o.material() + " à " + o.npcId();
            case SmeltItemObjective o -> "Cuire " + o.material();
            case DiscoverWaypointObjective o -> "Découvrir " + o.amount() + " waypoint(s)";
            case DiscoverCityObjective o -> o.specific()
                    ? "Découvrir " + com.lodygames.rpgquest.city.CityNames.of(o.city())
                    : "Découvrir " + o.amount() + " ville(s)";
            case KillQuestBossObjective o -> "Vaincre le boss " + com.lodygames.rpgquest.mob.MobNames.of(o.mob());
        };
    }

    /**
     * Issue #180 — comme {@link #describe(QuestObjective)}, mais nomme le PNJ par son <strong>nom
     * affiché</strong> quand {@code npcNames} le connaît : « Parler à Lily » plutôt que l'identifiant
     * logique « lily » dans la bossbar de la quête suivie et {@code /quest}. Sans nom connu, le texte
     * historique reste inchangé.
     */
    static String describe(QuestObjective objective, Function<String, Optional<String>> npcNames) {
        return switch (objective) {
            case TalkToNpcObjective o -> npcNames.apply(o.npcId())
                    .map(name -> "Parler à " + name)
                    .orElseGet(() -> describe(objective));
            case DeliverItemToNpcObjective o -> npcNames.apply(o.npcId())
                    .map(name -> "Remettre " + o.material() + " à " + name)
                    .orElseGet(() -> describe(objective));
            default -> describe(objective);
        };
    }
}
