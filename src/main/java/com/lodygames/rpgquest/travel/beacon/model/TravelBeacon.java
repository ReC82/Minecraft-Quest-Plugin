package com.lodygames.rpgquest.travel.beacon.model;

import java.time.Instant;

/**
 * Une borne de téléportation physique (issue #132/#150) : support identique à un waypoint
 * (`waypoint.render.WaypointModelV1`), mais bloc de diamant au lieu du bloc d'or et bouton en bois
 * au lieu du bouton en pierre. Son bouton ouvre le menu de voyage ; il ne découvre jamais le
 * waypoint voisin et n'est jamais fusionné avec l'identité/table des waypoints ou des waystones
 * (systèmes distincts, voir {@code waypoint.model.Waypoint} / {@code waystone.model.Waystone}).
 *
 * <p>{@code id} : identifiant technique stable, dérivé de la position d'ancre — jamais du rendu.
 * {@code x}/{@code y}/{@code z} : ancre (colonne de surface), pas l'interacteur. {@code facing} :
 * orientation cardinale de l'interacteur. {@code active} : borne désactivée = bouton inerte.</p>
 */
public record TravelBeacon(String id, String world, int x, int y, int z, String facing,
                            int modelVersion, boolean active, Instant createdAt) {
}
