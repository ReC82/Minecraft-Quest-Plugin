package com.lodygames.rpgquest.story;

import com.lodygames.rpgquest.story.model.StoryDefinition;
import java.util.List;

/** Résultat d'un chargement : les stories valides, et un problème par fichier/story rejeté. */
public record StoryLoadReport(List<StoryDefinition> loaded, List<StoryLoadIssue> issues) {

    public StoryLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
