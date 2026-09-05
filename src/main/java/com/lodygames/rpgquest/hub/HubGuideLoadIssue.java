package com.lodygames.rpgquest.hub;

/** Un problème de chargement d'un fichier {@code hub-guides/*.yml} : le fichier concerné et le message. */
public record HubGuideLoadIssue(String file, String message) {
}
