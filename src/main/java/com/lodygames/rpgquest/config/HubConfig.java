package com.lodygames.rpgquest.config;

/**
 * Réglages du Hub, unique source de vérité pour {@code hub.HubWorldRulesService}/{@code
 * hub.HubWorldProtectionListener}/{@code claim.ClaimService} — jamais de comparaison à une chaîne
 * codée en dur ailleurs dans le plugin.
 *
 * @param world             nom exact du monde Hub
 * @param guideNpcId        identifiant <strong>logique</strong> du PNJ Guide ({@code npcs/<id>.yml}),
 *                          autour duquel un PNJ créé sans position explicite est placé. C'est une
 *                          définition stable, jamais un nom affiché — renommer le Guide en jeu ne
 *                          casse donc pas ce repère.
 * @param placementRadius   rayon horizontal de recherche d'un emplacement sûr, en blocs
 * @param placementVertical écart vertical exploré autour de la hauteur du Guide, en blocs
 * @param placementAttempts nombre maximum d'emplacements examinés — la recherche est toujours bornée
 */
public record HubConfig(String world, String guideNpcId,
                        int placementRadius, int placementVertical, int placementAttempts) {

    /** Valeurs par défaut historiques, pour les appelants qui ne règlent que le monde. */
    public HubConfig(String world) {
        this(world, "guide", 8, 3, 2000);
    }
}
