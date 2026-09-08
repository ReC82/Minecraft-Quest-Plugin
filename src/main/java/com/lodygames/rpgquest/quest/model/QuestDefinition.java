package com.lodygames.rpgquest.quest.model;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

public record QuestDefinition(
        NamespacedKey id,
        LocalizedText title,
        LocalizedText description,
        String category,
        Material icon,
        boolean repeatable,
        boolean secret,
        List<NamespacedKey> prerequisites,
        List<QuestStep> steps,
        List<QuestReward> rewards,
        Map<String, String> variables,
        /**
         * PNJ donneur de la quête (id stable, cf. {@code /rpgadmin npc tag <id>}), ou {@code null}
         * si la quête ne déclare pas de {@code giver:} — champ optionnel, rétrocompatible avec les
         * YAML existants (#75).
         */
        String giver
) {

    public QuestDefinition {
        prerequisites = List.copyOf(prerequisites);
        steps = List.copyOf(steps);
        rewards = List.copyOf(rewards);
        variables = Map.copyOf(variables);

        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category ne peut pas être vide.");
        }
        if (icon == null) {
            throw new IllegalArgumentException("icon ne peut pas être nul.");
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("la quête « " + id + "» doit avoir au moins une étape.");
        }
    }
}
