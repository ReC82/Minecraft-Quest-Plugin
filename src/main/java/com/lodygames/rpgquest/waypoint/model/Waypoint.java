package com.lodygames.rpgquest.waypoint.model;

import java.time.Instant;

/**
 * Un waypoint physique persistant du monde d'exploration (issue #124) : objet global, partagé par
 * tous les joueurs, découvert individuellement.
 *
 * <ul>
 *   <li>{@code id} : identifiant technique déterministe de l'instance de biome
 *       ({@link BiomeInstanceKey#waypointId()}) — <strong>jamais fonction du rendu</strong> ;</li>
 *   <li>{@code biomeInstance} : {@link BiomeInstanceKey#serialize()} — l'identité métier de la zone,
 *       {@code (world, biomeInstance)} est unique en base ;</li>
 *   <li>{@code biomeKey}/{@code regionX}/{@code regionZ} : composantes dénormalisées de l'instance,
 *       pratiques pour la lecture Control Panel / les diagnostics sans re-parser ;</li>
 *   <li>{@code x}/{@code y}/{@code z} : <strong>ancre</strong> de la structure (colonne de surface,
 *       premier bloc d'air), pas la position de l'interacteur — celle-ci se déduit du modèle ;</li>
 *   <li>{@code facing} : orientation cardinale de l'interacteur ({@code NORTH|SOUTH|EAST|WEST}) ;</li>
 *   <li>{@code modelVersion} : version du modèle de rendu à utiliser pour poser/re-poser/protéger ;</li>
 *   <li>{@code active} : notion prévue (waypoint désactivé = ignoré pour découverte/protection) ;</li>
 *   <li>{@code createdAt} : horodatage de première génération.</li>
 * </ul>
 */
public record Waypoint(String id, String world, String biomeInstance, String biomeKey,
                       long regionX, long regionZ, int x, int y, int z, String facing,
                       int modelVersion, boolean active, Instant createdAt) {
}
