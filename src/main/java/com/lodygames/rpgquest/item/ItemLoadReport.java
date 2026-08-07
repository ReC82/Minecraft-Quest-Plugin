package com.lodygames.rpgquest.item;

import com.lodygames.rpgquest.item.model.CustomItemDefinition;
import java.util.List;

/** Résultat d'un chargement : les objets valides, et un problème par fichier/objet rejeté. */
public record ItemLoadReport(List<CustomItemDefinition> loaded, List<ItemLoadIssue> issues) {

    public ItemLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
