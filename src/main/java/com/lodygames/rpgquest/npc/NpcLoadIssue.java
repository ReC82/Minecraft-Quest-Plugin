package com.lodygames.rpgquest.npc;

/** Un problème rencontré en chargeant une définition PNJ ({@code file} = nom de fichier). */
public record NpcLoadIssue(String file, String message) {
}
