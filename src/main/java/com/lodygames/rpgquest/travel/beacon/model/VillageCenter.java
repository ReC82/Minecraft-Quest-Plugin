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
 *
 * <p><strong>Une ville est un centre de village</strong> (issue #178) : même table, même identité,
 * même destination de voyage. La ville ajoute seulement {@code detectionDistance}, la demi-largeur
 * du carré horizontal de détection (voir {@code city.CityPerimeter}).</p>
 */
public record VillageCenter(String id, String name, String world, double x, double y, double z,
                             float yaw, float pitch, boolean active, Instant createdAt,
                             int detectionDistance) {

    /** Distance de détection par défaut d'une ville : 50 blocs dans chaque direction. */
    public static final int DEFAULT_DETECTION_DISTANCE = 50;

    /** Forme historique (#151), sans distance : la ville prend la distance par défaut. */
    public VillageCenter(String id, String name, String world, double x, double y, double z,
                         float yaw, float pitch, boolean active, Instant createdAt) {
        this(id, name, world, x, y, z, yaw, pitch, active, createdAt, DEFAULT_DETECTION_DISTANCE);
    }

    public VillageCenter withName(String newName) {
        return new VillageCenter(id, newName, world, x, y, z, yaw, pitch, active, createdAt, detectionDistance);
    }

    public VillageCenter withDetectionDistance(int distance) {
        return new VillageCenter(id, name, world, x, y, z, yaw, pitch, active, createdAt, distance);
    }

    public VillageCenter withActive(boolean nowActive) {
        return new VillageCenter(id, name, world, x, y, z, yaw, pitch, nowActive, createdAt, detectionDistance);
    }

    public VillageCenter movedTo(String newWorld, double nx, double ny, double nz, float nyaw, float npitch) {
        return new VillageCenter(id, name, newWorld, nx, ny, nz, nyaw, npitch, active, createdAt, detectionDistance);
    }

    /** Colonne X du bloc central — la géométrie de la ville raisonne en blocs entiers. */
    public int blockX() {
        return (int) Math.floor(x);
    }

    /** Ligne Z du bloc central. */
    public int blockZ() {
        return (int) Math.floor(z);
    }
}
