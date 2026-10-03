package com.lodygames.rpgquest.travel.beacon.model;

import java.time.Instant;

/**
 * Un centre de village administrable (issue #151), destination de la catégorie « Villages » du
 * menu de voyage. Identité <strong>indépendante du nom de monde</strong> : plusieurs centres
 * peuvent exister dans le même monde (ex. plusieurs villes dans {@code world_hub}) — {@code id} est
 * la seule clé stable, jamais recalculée à partir de la position. Déplacer ou désactiver un centre
 * (ré-écrire via le même {@code id}) ne casse donc jamais les références existantes.
 *
 * <p>Ne jamais confondre avec le monde Hub lui-même ({@code hub.HubConfig}) : un centre de village
 * est un point administré au même titre qu'un spawn ({@code spawn.SpawnService}), pas une propriété
 * du monde entier.</p>
 */
public record VillageCenter(String id, String name, String world, double x, double y, double z,
                             float yaw, float pitch, boolean active, Instant createdAt) {
}
