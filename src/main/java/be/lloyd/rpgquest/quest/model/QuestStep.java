package be.lloyd.rpgquest.quest.model;

import java.util.List;

public record QuestStep(String id, List<QuestObjective> objectives) {

    public QuestStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id de l'étape ne peut pas être vide.");
        }
        objectives = List.copyOf(objectives);
        if (objectives.isEmpty()) {
            throw new IllegalArgumentException("l'étape « " + id + "» doit avoir au moins un objectif.");
        }
    }
}
