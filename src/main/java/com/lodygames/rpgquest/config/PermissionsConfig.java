package com.lodygames.rpgquest.config;

import java.util.Map;

/**
 * Section {@code permissions} de {@code config.yml} (issue #27).
 *
 * <p>{@code buildAreas} : nom de monde → spécification de zone de build, consommée par {@code
 * permission.BuildPermissionService}. Formes acceptées de la valeur :</p>
 * <ul>
 *   <li>{@code hub.<id>} → {@code rpgquest.build.hub.<id>} (ex. {@code hub.0}, {@code hub.arena}) ;</li>
 *   <li>{@code wild} → {@code rpgquest.build.wild} ;</li>
 *   <li>{@code world.<clé>} ou {@code <clé>} → {@code rpgquest.build.world.<clé>}.</li>
 * </ul>
 *
 * <p>Vide par défaut : sans entrée explicite, le monde Hub de {@code hub.world} vaut {@code hub.0},
 * {@code travel.wild-world} vaut {@code wild} et {@code claims.world} vaut {@code world.claims} —
 * ajouter un Hub supplémentaire ne demande donc qu'une ligne ici, jamais de changement de code.</p>
 */
public record PermissionsConfig(Map<String, String> buildAreas) {

    public PermissionsConfig {
        buildAreas = Map.copyOf(buildAreas);
    }

    public static PermissionsConfig empty() {
        return new PermissionsConfig(Map.of());
    }
}
