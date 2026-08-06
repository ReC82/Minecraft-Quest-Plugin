package be.lloyd.rpgquest.quest.model;

import org.bukkit.entity.EntityType;

public record KillEntityObjective(EntityType entity, int amount) implements QuestObjective {

    public KillEntityObjective {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.KILL_ENTITY;
    }
}
