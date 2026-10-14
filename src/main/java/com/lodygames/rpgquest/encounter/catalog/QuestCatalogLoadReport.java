package com.lodygames.rpgquest.encounter.catalog;

import com.lodygames.rpgquest.encounter.EncounterLoadIssue;
import java.util.List;

/** Résultat d'un chargement de catalogues (issue #369) : ceux valides + un problème par fichier rejeté. */
public record QuestCatalogLoadReport(List<QuestCatalog> loaded, List<EncounterLoadIssue> issues) {

    public QuestCatalogLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }
}
