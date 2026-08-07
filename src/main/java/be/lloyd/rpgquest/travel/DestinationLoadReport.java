package be.lloyd.rpgquest.travel;

import be.lloyd.rpgquest.travel.model.Destination;
import java.util.List;

/** Résultat d'un chargement : les destinations valides, et un problème par fichier rejeté. */
public record DestinationLoadReport(List<Destination> loaded, List<DestinationLoadIssue> issues) {

    public DestinationLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
