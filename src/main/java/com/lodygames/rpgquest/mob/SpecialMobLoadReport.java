package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.List;

/**
 * Résultat d'un chargement : les variantes valides, un problème par fichier/variante rejetée, et
 * (issue #542) les avertissements sur des profils chargés quand même (capacité en double…).
 */
public record SpecialMobLoadReport(List<SpecialMobDefinition> loaded, List<SpecialMobLoadIssue> issues,
                                   List<SpecialMobLoadIssue> warnings) {

    public SpecialMobLoadReport(List<SpecialMobDefinition> loaded, List<SpecialMobLoadIssue> issues) {
        this(loaded, issues, List.of());
    }

    public SpecialMobLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
        warnings = List.copyOf(warnings);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
