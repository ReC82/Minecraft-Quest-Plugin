package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.List;

/** Résultat d'un chargement : les variantes valides, et un problème par fichier/variante rejetée. */
public record SpecialMobLoadReport(List<SpecialMobDefinition> loaded, List<SpecialMobLoadIssue> issues) {

    public SpecialMobLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
