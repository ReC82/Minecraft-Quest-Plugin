package be.lloyd.rpgquest.travel;

import be.lloyd.rpgquest.travel.model.PortalDefinition;
import java.util.List;

/** Résultat d'un chargement : les portails valides, et un problème par fichier/portail rejeté. */
public record PortalLoadReport(List<PortalDefinition> loaded, List<PortalLoadIssue> issues) {

    public PortalLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
