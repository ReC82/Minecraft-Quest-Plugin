package com.lodygames.rpgquest.waypoint.render;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Catalogue des {@link WaypointModel} connus, indexés par {@link WaypointModel#version()} (issue
 * #124). La logique métier stocke un simple numéro de version par waypoint ; ce registre fait le
 * pont version → rendu, ce qui permet :
 *
 * <ul>
 *   <li>de re-poser un waypoint existant avec <em>son</em> modèle d'origine (rendu historique) ;</li>
 *   <li>d'introduire un modèle v2 et de basculer la génération dessus sans toucher aux waypoints
 *       déjà en base ni à la découverte (leur identité est indépendante du rendu) ;</li>
 *   <li>de migrer plus tard les waypoints existants vers un nouveau modèle de façon explicite et
 *       coordonnée (hors périmètre MVP).</li>
 * </ul>
 */
public final class WaypointModelRegistry {

    private final Map<Integer, WaypointModel> byVersion = new HashMap<>();
    private final int currentVersion;

    /**
     * @param desiredCurrentVersion version demandée par la configuration pour les nouvelles
     *                              générations ; si aucun modèle ne la porte, on retombe sur la
     *                              plus haute version enregistrée.
     */
    public WaypointModelRegistry(int desiredCurrentVersion, WaypointModel... models) {
        if (models.length == 0) {
            throw new IllegalArgumentException("au moins un WaypointModel est requis");
        }
        int highest = Integer.MIN_VALUE;
        for (WaypointModel model : models) {
            if (byVersion.putIfAbsent(model.version(), model) != null) {
                throw new IllegalArgumentException("version de modèle dupliquée : " + model.version());
            }
            highest = Math.max(highest, model.version());
        }
        this.currentVersion = byVersion.containsKey(desiredCurrentVersion) ? desiredCurrentVersion : highest;
    }

    /** Version stampée sur les waypoints nouvellement générés. */
    public int currentVersion() {
        return currentVersion;
    }

    /** Modèle utilisé pour les nouvelles générations. */
    public WaypointModel current() {
        return byVersion.get(currentVersion);
    }

    /** Modèle d'une version précise ({@link Optional#empty()} si inconnue — build plus ancien qu'une donnée). */
    public Optional<WaypointModel> forVersion(int version) {
        return Optional.ofNullable(byVersion.get(version));
    }

    /** Modèle d'une version précise, ou le modèle courant en dernier recours (rendu « au mieux »). */
    public WaypointModel resolveOrCurrent(int version) {
        return byVersion.getOrDefault(version, current());
    }
}
