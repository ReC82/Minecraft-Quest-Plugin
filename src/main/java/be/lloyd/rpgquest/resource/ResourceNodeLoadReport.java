package be.lloyd.rpgquest.resource;

import be.lloyd.rpgquest.resource.model.ResourceNodeDefinition;
import java.util.List;

/** Résultat d'un chargement : les types de nœuds valides, et un problème par fichier/type rejeté. */
public record ResourceNodeLoadReport(List<ResourceNodeDefinition> loaded, List<ResourceNodeLoadIssue> issues) {

    public ResourceNodeLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
