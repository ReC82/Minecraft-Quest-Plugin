package com.lodygames.rpgquest.crafting;

import com.lodygames.rpgquest.crafting.model.RecipeDefinition;
import java.util.List;

/** Résultat d'un chargement : les recettes valides, et un problème par fichier/recette rejetée. */
public record RecipeLoadReport(List<RecipeDefinition> loaded, List<RecipeLoadIssue> issues) {

    public RecipeLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
