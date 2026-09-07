package com.lodygames.rpgquest.hub;

/**
 * Une orientation vers un autre PNJ affichée par le Guide d'un Hub : purement <strong>textuelle</strong>
 * (issue #11, limites V1 — pas de waypoint / halo / navigation). {@code role} décrit le domaine
 * (« Journal &amp; quêtes », « Claims »…), {@code npcName} nomme le PNJ tel qu'un joueur le voit
 * (« le Libraire », « Jo »), {@code note} donne l'explication courte.
 */
public record HubGuideReferral(String role, String npcName, String note) {

    public HubGuideReferral {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role ne peut pas être vide.");
        }
        if (npcName == null || npcName.isBlank()) {
            throw new IllegalArgumentException("npcName ne peut pas être vide.");
        }
        note = note == null ? "" : note;
    }
}
