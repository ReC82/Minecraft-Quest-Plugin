package be.lloyd.rpgquest.mob;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record SpecialMobLoadIssue(String file, String message) {
}
