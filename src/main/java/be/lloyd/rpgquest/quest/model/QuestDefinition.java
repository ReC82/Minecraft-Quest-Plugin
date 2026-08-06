package be.lloyd.rpgquest.quest.model;

import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;

public record QuestDefinition(
        NamespacedKey id,
        LocalizedText title,
        LocalizedText description,
        String category,
        boolean repeatable,
        List<NamespacedKey> prerequisites,
        List<QuestStep> steps,
        List<QuestReward> rewards,
        Map<String, String> variables
) {

    public QuestDefinition {
        prerequisites = List.copyOf(prerequisites);
        steps = List.copyOf(steps);
        rewards = List.copyOf(rewards);
        variables = Map.copyOf(variables);

        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category ne peut pas être vide.");
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("la quête « " + id + "» doit avoir au moins une étape.");
        }
    }
}
