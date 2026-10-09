package com.lodygames.rpgquest.building.model;

import java.time.Instant;

/**
 * Une ligne du journal des opérations d'un emplacement (issue #234).
 *
 * <h2>Ce que ce journal est, et ce qu'il n'est pas</h2>
 *
 * <p>Il répond à une seule question : <em>« qu'est-ce qui a été posé ici, dans quel ordre, par qui,
 * et est-ce que ça a marché ? »</em>. Ce n'est pas un système de versions : on ne rejoue rien depuis
 * le journal, et il ne contient aucun bloc. Le ticket le dit explicitement — pas besoin de
 * reconstruire Git dans SQLite.</p>
 *
 * <p>Conséquence assumée : les <strong>échecs sont enregistrés aussi</strong>
 * ({@code ok = false}). Un journal qui ne garderait que les succès laisserait croire qu'il ne s'est
 * rien passé là où, précisément, quelque chose s'est mal passé — et c'est le moment où l'on consulte
 * un journal.</p>
 *
 * @param buildingVersion  version déclarée de la définition au moment de l'opération
 * @param schematicSha256  empreinte du fichier réellement collé, {@code ""} si inconnue. C'est elle
 *                         qui est fiable : une version déclarée peut être oubliée, un contenu non
 * @param backupSchematic  sauvegarde de compensation prise avant cette opération, {@code ""} si
 *                         aucune
 */
public record BuildingHistoryEntry(long id,
                                   String siteId,
                                   String buildingId,
                                   int buildingVersion,
                                   String schematicSha256,
                                   int rotationDegrees,
                                   String world,
                                   int minX, int minY, int minZ,
                                   int maxX, int maxY, int maxZ,
                                   BuildingOperation operation,
                                   String actor,
                                   Instant at,
                                   boolean ok,
                                   String detail,
                                   String backupSchematic) {

    public BuildingHistoryEntry {
        buildingId = buildingId == null ? "" : buildingId;
        schematicSha256 = schematicSha256 == null ? "" : schematicSha256;
        actor = actor == null ? "" : actor;
        detail = detail == null ? "" : detail;
        backupSchematic = backupSchematic == null ? "" : backupSchematic;
    }

    /** Entrée pas encore persistée, décrivant une opération sur cette emprise. */
    public static BuildingHistoryEntry of(String siteId, BuildingOperation operation,
                                          String buildingId, int buildingVersion,
                                          String schematicSha256, int rotationDegrees,
                                          BuildingFootprint footprint, String actor, Instant at,
                                          boolean ok, String detail, String backupSchematic) {
        return new BuildingHistoryEntry(0L, siteId, buildingId, buildingVersion, schematicSha256,
                BuildingRotation.normalize(rotationDegrees),
                footprint == null ? "" : footprint.world(),
                footprint == null ? 0 : footprint.minX(),
                footprint == null ? 0 : footprint.minY(),
                footprint == null ? 0 : footprint.minZ(),
                footprint == null ? 0 : footprint.maxX(),
                footprint == null ? 0 : footprint.maxY(),
                footprint == null ? 0 : footprint.maxZ(),
                operation, actor, at, ok, detail, backupSchematic);
    }

    public BuildingFootprint footprint() {
        return world.isEmpty() ? null
                : new BuildingFootprint(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Les douze premiers caractères de l'empreinte : assez pour comparer deux versions à l'œil. */
    public String shortSha() {
        return schematicSha256.length() <= 12 ? schematicSha256 : schematicSha256.substring(0, 12);
    }
}
