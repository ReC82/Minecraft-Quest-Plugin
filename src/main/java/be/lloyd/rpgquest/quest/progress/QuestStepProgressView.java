package be.lloyd.rpgquest.quest.progress;

import java.util.List;

public record QuestStepProgressView(String stepId, List<ObjectiveProgressView> objectives) {
}
