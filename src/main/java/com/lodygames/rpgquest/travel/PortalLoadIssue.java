package com.lodygames.rpgquest.travel;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record PortalLoadIssue(String file, String message) {
}
