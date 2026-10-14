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
 *
 * <p><strong>Issue #322</strong> — deux informations facultatives, sans second système :
 * {@code centralNpcId}, l'identité stable (npc_id logique) d'un PNJ associé à la ville, jamais sa
 * position recopiée ; et {@code arrival}, le point d'arrivée de voyage, distinct du centre de
 * détection. Sans arrivée, le voyage arrive au centre (comportement de #151/#178).</p>
 */
public record VillageCenter(String id, String name, String world, double x, double y, double z,
                             float yaw, float pitch, boolean active, Instant createdAt,
                             int detectionDistance, String centralNpcId, Arrival arrival) {

    /** Distance de détection par défaut d'une ville : 50 blocs dans chaque direction. */
    public static final int DEFAULT_DETECTION_DISTANCE = 50;

    /**
     * Point d'arrivée de voyage (issue #322) : position et orientation relevées en jeu, toujours
     * dans le monde de la ville.
     */
    public record Arrival(String world, double x, double y, double z, float yaw, float pitch) {
    }

    /** Forme #178, sans PNJ central ni arrivée distincte. */
    public VillageCenter(String id, String name, String world, double x, double y, double z,
                         float yaw, float pitch, boolean active, Instant createdAt, int detectionDistance) {
        this(id, name, world, x, y, z, yaw, pitch, active, createdAt, detectionDistance, null, null);
    }

    /** Forme historique (#151), sans distance : la ville prend la distance par défaut. */
    public VillageCenter(String id, String name, String world, double x, double y, double z,
                         float yaw, float pitch, boolean active, Instant createdAt) {
        this(id, name, world, x, y, z, yaw, pitch, active, createdAt, DEFAULT_DETECTION_DISTANCE);
    }

    public VillageCenter withName(String newName) {
        return new VillageCenter(id, newName, world, x, y, z, yaw, pitch, active, createdAt, detectionDistance,
                centralNpcId, arrival);
    }

    public VillageCenter withDetectionDistance(int distance) {
        return new VillageCenter(id, name, world, x, y, z, yaw, pitch, active, createdAt, distance,
                centralNpcId, arrival);
    }

    public VillageCenter withActive(boolean nowActive) {
        return new VillageCenter(id, name, world, x, y, z, yaw, pitch, nowActive, createdAt, detectionDistance,
                centralNpcId, arrival);
    }

    /** {@code null} retire le PNJ central. */
    public VillageCenter withCentralNpc(String npcId) {
        return new VillageCenter(id, name, world, x, y, z, yaw, pitch, active, createdAt, detectionDistance,
                npcId, arrival);
    }

    /** {@code null} revient à l'arrivée au centre. */
    public VillageCenter withArrival(Arrival newArrival) {
        return new VillageCenter(id, name, world, x, y, z, yaw, pitch, active, createdAt, detectionDistance,
                centralNpcId, newArrival);
    }

    /**
     * Déplace le centre. Une arrivée distincte reste valable dans le même monde ; changer de monde
     * l'efface, car une arrivée hors du monde de la ville n'aurait aucun sens.
     */
    public VillageCenter movedTo(String newWorld, double nx, double ny, double nz, float nyaw, float npitch) {
        Arrival kept = arrival != null && arrival.world().equals(newWorld) ? arrival : null;
        return new VillageCenter(id, name, newWorld, nx, ny, nz, nyaw, npitch, active, createdAt,
                detectionDistance, centralNpcId, kept);
    }

    public boolean hasCentralNpc() {
        return centralNpcId != null && !centralNpcId.isBlank();
    }

    /** Où arrive un voyage vers cette ville : l'arrivée distincte si elle existe, sinon le centre. */
    public Arrival travelTarget() {
        return arrival != null ? arrival : new Arrival(world, x, y, z, yaw, pitch);
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
