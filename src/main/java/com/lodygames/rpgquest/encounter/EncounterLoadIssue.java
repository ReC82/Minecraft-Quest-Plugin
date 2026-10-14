package com.lodygames.rpgquest.encounter;

/** Un problème rencontré en chargeant un modèle de PNJ récurrent ({@code file} = nom de fichier). */
public record EncounterLoadIssue(String file, String message) {
}
