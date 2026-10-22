package com.lodygames.rpgquest.config;

import com.lodygames.rpgquest.hub.HubStationException;
import java.util.List;

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
 * @param stationExceptions exceptions à la fermeture des postes de travail du Hub ({@code
 *                          hub.stations.exceptions}, issue #675) — vide par défaut : tous fermés
 */
public record HubConfig(String world, String guideNpcId,
                        int placementRadius, int placementVertical, int placementAttempts,
                        List<HubStationException> stationExceptions) {

    public HubConfig {
        stationExceptions = stationExceptions == null ? List.of() : List.copyOf(stationExceptions);
    }

    /** Sans exception de poste de travail (tous fermés). */
    public HubConfig(String world, String guideNpcId, int placementRadius, int placementVertical,
                     int placementAttempts) {
        this(world, guideNpcId, placementRadius, placementVertical, placementAttempts, List.of());
    }

    /** Valeurs par défaut historiques, pour les appelants qui ne règlent que le monde. */
    public HubConfig(String world) {
        this(world, "guide", 8, 3, 2000);
    }
}
