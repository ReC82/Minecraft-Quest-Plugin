package be.lloyd.rpgquest.resource;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record ResourceNodeLoadIssue(String file, String message) {
}
