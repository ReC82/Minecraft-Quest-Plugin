package com.lodygames.rpgquest.item;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record ItemLoadIssue(String file, String message) {
}
