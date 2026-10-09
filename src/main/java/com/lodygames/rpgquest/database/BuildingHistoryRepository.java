package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.building.model.BuildingHistoryEntry;
import com.lodygames.rpgquest.building.model.BuildingOperation;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Repository de la table {@code building_placement_history} (issue #234) : le journal des opérations
 * d'un emplacement.
 *
 * <p>JDBC pur, aucun type Bukkit ni WorldEdit. <strong>Écritures seulement ajoutées</strong> : il
 * n'y a ni {@code UPDATE} ni {@code DELETE} par ligne, parce qu'un journal qu'on peut réécrire ne
 * répond plus à la question qu'on lui pose.</p>
 *
 * <p>Les lignes sont rendues du <strong>plus récent au plus ancien</strong> : c'est l'ordre dans
 * lequel on lit un journal quand on cherche ce qui vient de se passer.</p>
 */
public final class BuildingHistoryRepository {

    /** Garde-fou de lecture : un emplacement très expérimenté ne doit pas charger mille lignes. */
    public static final int DEFAULT_LIMIT = 50;

    private static final String INSERT = """
            INSERT INTO building_placement_history
                (site_id, building_id, building_version, schematic_sha256, rotation, world,
                 min_x, min_y, min_z, max_x, max_y, max_z,
                 operation, actor, happened_at, ok, detail, backup_schematic)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_SITE =
            "SELECT * FROM building_placement_history WHERE site_id = ? ORDER BY id DESC LIMIT ?";
    private static final String DELETE_SITE =
            "DELETE FROM building_placement_history WHERE site_id = ?";

    private final DatabaseManager database;

    public BuildingHistoryRepository(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Ajoute une ligne au journal.
     *
     * <p>Les <strong>échecs sont enregistrés comme les succès</strong> ({@code ok = 0}) : un journal
     * qui ne garderait que ce qui a marché serait muet au moment exact où on le consulte.</p>
     */
    public CompletableFuture<Void> append(BuildingHistoryEntry entry) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, entry.siteId());
                statement.setString(2, entry.buildingId());
                statement.setInt(3, entry.buildingVersion());
                statement.setString(4, entry.schematicSha256());
                statement.setInt(5, entry.rotationDegrees());
                statement.setString(6, entry.world());
                statement.setInt(7, entry.minX());
                statement.setInt(8, entry.minY());
                statement.setInt(9, entry.minZ());
                statement.setInt(10, entry.maxX());
                statement.setInt(11, entry.maxY());
                statement.setInt(12, entry.maxZ());
                statement.setString(13, entry.operation() == null
                        ? BuildingOperation.PLACE.name() : entry.operation().name());
                statement.setString(14, entry.actor());
                statement.setString(15, entry.at().toString());
                statement.setInt(16, entry.ok() ? 1 : 0);
                statement.setString(17, entry.detail());
                statement.setString(18, entry.backupSchematic());
                statement.executeUpdate();
                return null;
            }
        });
    }

    /** Les dernières opérations d'un emplacement, de la plus récente à la plus ancienne. */
    public CompletableFuture<List<BuildingHistoryEntry>> forSite(String siteId, int limit) {
        int bounded = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 500);
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_SITE)) {
                statement.setString(1, siteId);
                statement.setInt(2, bounded);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<BuildingHistoryEntry> entries = new ArrayList<>();
                    while (resultSet.next()) {
                        entries.add(map(resultSet));
                    }
                    return entries;
                }
            }
        });
    }

    /** Oublie le journal d'un emplacement — uniquement quand l'emplacement lui-même disparaît. */
    public CompletableFuture<Integer> deleteAllForSite(String siteId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_SITE)) {
                statement.setString(1, siteId);
                return statement.executeUpdate();
            }
        });
    }

    private static BuildingHistoryEntry map(ResultSet row) throws SQLException {
        return new BuildingHistoryEntry(
                row.getLong("id"),
                row.getString("site_id"),
                row.getString("building_id"),
                row.getInt("building_version"),
                row.getString("schematic_sha256"),
                row.getInt("rotation"),
                row.getString("world"),
                row.getInt("min_x"), row.getInt("min_y"), row.getInt("min_z"),
                row.getInt("max_x"), row.getInt("max_y"), row.getInt("max_z"),
                BuildingOperation.ofWire(row.getString("operation")),
                row.getString("actor"),
                Instant.parse(row.getString("happened_at")),
                row.getInt("ok") != 0,
                row.getString("detail"),
                row.getString("backup_schematic"));
    }
}
