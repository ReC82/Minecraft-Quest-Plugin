package com.lodygames.rpgquest.quest.model;

import java.time.Duration;
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
        String giver,
        /**
         * Délai minimal entre une complétion et la ré-acceptation d'une quête {@code repeatable}
         * (issue #13 — anti-farm des quêtes secondaires renouvelables). {@link Duration#ZERO} =
         * aucun délai ; sans effet sur une quête non répétable. Champ YAML optionnel {@code cooldown:}.
         */
        Duration cooldown,
        /**
         * Issue #15 — {@link QuestPeriod#DAILY} / {@link QuestPeriod#WEEKLY} pour un modèle de quête
         * périodique, {@code null} pour une quête ordinaire. Champ YAML optionnel {@code period:}.
         */
        QuestPeriod period
) {

    /** Constructeur sans période (toute quête ordinaire). */
    public QuestDefinition(NamespacedKey id, LocalizedText title, LocalizedText description, String category,
                           Material icon, boolean repeatable, boolean secret, List<NamespacedKey> prerequisites,
                           List<QuestStep> steps, List<QuestReward> rewards, Map<String, String> variables,
                           String giver, Duration cooldown) {
        this(id, title, description, category, icon, repeatable, secret, prerequisites, steps, rewards, variables,
                giver, cooldown, null);
    }

    /** Constructeur historique, sans délai de ré-acceptation. */
    public QuestDefinition(NamespacedKey id, LocalizedText title, LocalizedText description, String category,
                           Material icon, boolean repeatable, boolean secret, List<NamespacedKey> prerequisites,
                           List<QuestStep> steps, List<QuestReward> rewards, Map<String, String> variables,
                           String giver) {
        this(id, title, description, category, icon, repeatable, secret, prerequisites, steps, rewards, variables,
                giver, Duration.ZERO, null);
    }

    /** {@code true} pour un modèle de quête quotidienne ou hebdomadaire (issue #15). */
    public boolean isPeriodic() {
        return period != null;
    }

    public QuestDefinition {
        cooldown = cooldown == null ? Duration.ZERO : cooldown;
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown ne peut pas être négatif.");
        }
        if (period != null && !repeatable) {
            throw new IllegalArgumentException("une quête périodique (period) est forcément répétable.");
        }
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
