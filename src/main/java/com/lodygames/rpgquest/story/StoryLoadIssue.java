package com.lodygames.rpgquest.story;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record StoryLoadIssue(String file, String message) {
}
