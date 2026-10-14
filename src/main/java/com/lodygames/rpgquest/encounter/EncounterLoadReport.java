package com.lodygames.rpgquest.encounter;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.util.List;

/** Résultat d'un chargement de modèles de PNJ récurrents : ceux valides + un problème par fichier rejeté. */
public record EncounterLoadReport(List<EncounterTemplate> loaded, List<EncounterLoadIssue> issues) {

    public EncounterLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
