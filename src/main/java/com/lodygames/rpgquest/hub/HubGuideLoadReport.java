package com.lodygames.rpgquest.hub;

import java.util.List;

/** Résultat d'un chargement du dossier {@code hub-guides/} : définitions retenues + problèmes rencontrés. */
public record HubGuideLoadReport(List<HubGuideDefinition> loaded, List<HubGuideLoadIssue> issues) {

    public HubGuideLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }
}
