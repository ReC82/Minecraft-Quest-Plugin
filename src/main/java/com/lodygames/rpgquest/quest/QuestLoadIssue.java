package com.lodygames.rpgquest.quest;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record QuestLoadIssue(String file, String message) {
}
