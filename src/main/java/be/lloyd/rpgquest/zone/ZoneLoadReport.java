package be.lloyd.rpgquest.zone;

import be.lloyd.rpgquest.zone.model.ZoneDefinition;
import java.util.List;

/** Résultat d'un chargement : les zones valides, et un problème par fichier/zone rejetée. */
public record ZoneLoadReport(List<ZoneDefinition> loaded, List<ZoneLoadIssue> issues) {

    public ZoneLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
