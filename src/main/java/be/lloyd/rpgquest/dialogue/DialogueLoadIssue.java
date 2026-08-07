package be.lloyd.rpgquest.dialogue;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record DialogueLoadIssue(String file, String message) {
}
