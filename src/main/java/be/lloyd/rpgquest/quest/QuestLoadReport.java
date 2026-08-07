package be.lloyd.rpgquest.quest;

import be.lloyd.rpgquest.quest.model.QuestDefinition;
import java.util.List;

/** Résultat d'un chargement : les quêtes valides, et un problème par fichier/quête rejeté. */
public record QuestLoadReport(List<QuestDefinition> loaded, List<QuestLoadIssue> issues) {

    public QuestLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
