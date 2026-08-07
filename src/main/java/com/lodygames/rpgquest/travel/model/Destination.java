package com.lodygames.rpgquest.travel.model;

/**
 * Une position nommée, réutilisable par plusieurs portails (ex. plusieurs
 * portails « retour au village » dans des zones d'aventure différentes
 * pointant tous vers la même destination {@code village}). Correcte par
 * construction, même discipline que {@code ZoneDefinition}. Ne porte
 * aucune garantie de sécurité en soi : la vérification « position sûre »
 * se fait à la téléportation ({@code travel.PortalService}), pas ici — le
 * monde peut avoir changé depuis la création de la destination.
 */
public record Destination(String id, String world, double x, double y, double z, float yaw, float pitch) {

    public Destination {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id est obligatoire.");
        }
        if (!id.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "id invalide : \"" + id + "\" (minuscules, chiffres, « _ » et « - » uniquement).");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world est obligatoire.");
        }
    }
}
