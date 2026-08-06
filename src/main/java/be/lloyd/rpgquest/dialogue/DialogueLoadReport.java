package be.lloyd.rpgquest.dialogue;

import be.lloyd.rpgquest.dialogue.model.DialogueDefinition;
import java.util.List;

public record DialogueLoadReport(List<DialogueDefinition> loaded, List<DialogueLoadIssue> issues) {

    public DialogueLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
