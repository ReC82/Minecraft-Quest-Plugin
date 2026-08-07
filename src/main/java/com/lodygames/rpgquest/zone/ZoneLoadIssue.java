package com.lodygames.rpgquest.zone;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record ZoneLoadIssue(String file, String message) {
}
