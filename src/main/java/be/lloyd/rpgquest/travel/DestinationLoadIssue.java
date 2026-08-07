package be.lloyd.rpgquest.travel;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record DestinationLoadIssue(String file, String message) {
}
