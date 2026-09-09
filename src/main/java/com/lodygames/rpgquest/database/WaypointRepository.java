package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.waypoint.model.Waypoint;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository des tables {@code waypoints} et {@code waypoint_discoveries} (issue #124). JDBC pur,
 * aucun type Bukkit. La base est la seule source de vérité : l'index unique
 * {@code (world, biome_instance)} + {@code INSERT OR IGNORE} garantissent qu'une instance de biome
 * ne peut jamais avoir deux waypoints (pas de doublon au reload/redémarrage, ni si deux joueurs
 * entrent simultanément dans la zone).
 *
 * <p>Séparation stricte définition-monde / progression-joueur : {@code waypoints} décrit l'objet
 * partagé, {@code waypoint_discoveries} n'a que {@code (player_uuid, waypoint_id, discovered_at)} —
 * aucune duplication de la définition physique. Rien ici ne dépend d'un moteur SQL précis (SQL
 * SQLite canonique passé par {@link SqlDialect#rewrite}), la future migration MariaDB reste
 * possible sans réécriture.</p>
 */
public final class WaypointRepository {

    private static final String INSERT_WAYPOINT = """
            INSERT OR IGNORE INTO waypoints
                (id, world, biome_instance, biome_key, region_x, region_z, x, y, z, facing, model_version, active, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_ALL = "SELECT * FROM waypoints";
    private static final String SELECT_BY_INSTANCE = "SELECT * FROM waypoints WHERE world = ? AND biome_instance = ?";
    private static final String INSERT_DISCOVERY = """
            INSERT OR IGNORE INTO waypoint_discoveries (player_uuid, waypoint_id, discovered_at) VALUES (?, ?, ?)
            """;
    private static final String SELECT_DISCOVERIES =
            "SELECT waypoint_id FROM waypoint_discoveries WHERE player_uuid = ?";
    private static final String COUNT_DISCOVERIES_FOR_WAYPOINT =
            "SELECT COUNT(*) FROM waypoint_discoveries WHERE waypoint_id = ?";
    private static final String DELETE_DISCOVERIES =
            "DELETE FROM waypoint_discoveries WHERE player_uuid = ?";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public WaypointRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    public CompletableFuture<List<Waypoint>> loadAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<Waypoint> waypoints = new ArrayList<>();
                while (resultSet.next()) {
                    waypoints.add(map(resultSet));
                }
                return waypoints;
            }
        });
    }

    public CompletableFuture<Optional<Waypoint>> findByInstance(String world, String biomeInstance) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_INSTANCE)) {
                statement.setString(1, world);
                statement.setString(2, biomeInstance);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.<Waypoint>empty();
                }
            }
        });
    }

    /** {@code true} si la ligne a bien été insérée (aucun waypoint n'existait pour cette instance de biome). */
    public CompletableFuture<Boolean> insertIfAbsent(Waypoint waypoint) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(INSERT_WAYPOINT))) {
                statement.setString(1, waypoint.id());
                statement.setString(2, waypoint.world());
                statement.setString(3, waypoint.biomeInstance());
                statement.setString(4, waypoint.biomeKey());
                statement.setLong(5, waypoint.regionX());
                statement.setLong(6, waypoint.regionZ());
                statement.setInt(7, waypoint.x());
                statement.setInt(8, waypoint.y());
                statement.setInt(9, waypoint.z());
                statement.setString(10, waypoint.facing());
                statement.setInt(11, waypoint.modelVersion());
                statement.setInt(12, waypoint.active() ? 1 : 0);
                statement.setString(13, waypoint.createdAt().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Set<String>> discoveriesFor(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_DISCOVERIES)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    Set<String> ids = new HashSet<>();
                    while (resultSet.next()) {
                        ids.add(resultSet.getString("waypoint_id"));
                    }
                    return ids;
                }
            }
        });
    }

    /** {@code true} si c'est une découverte réellement nouvelle pour ce joueur. */
    public CompletableFuture<Boolean> recordDiscovery(UUID playerId, String waypointId, Instant at) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(INSERT_DISCOVERY))) {
                statement.setString(1, playerId.toString());
                statement.setString(2, waypointId);
                statement.setString(3, at.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    /** Nombre de joueurs ayant découvert ce waypoint (lecture Control Panel — jamais sur un chemin chaud). */
    public CompletableFuture<Integer> discoveryCountForWaypoint(String waypointId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(COUNT_DISCOVERIES_FOR_WAYPOINT)) {
                statement.setString(1, waypointId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getInt(1) : 0;
                }
            }
        });
    }

    public CompletableFuture<Integer> deleteDiscoveries(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_DISCOVERIES)) {
                statement.setString(1, playerId.toString());
                return statement.executeUpdate();
            }
        });
    }

    private Waypoint map(ResultSet resultSet) throws SQLException {
        return new Waypoint(
                resultSet.getString("id"),
                resultSet.getString("world"),
                resultSet.getString("biome_instance"),
                resultSet.getString("biome_key"),
                resultSet.getLong("region_x"),
                resultSet.getLong("region_z"),
                resultSet.getInt("x"),
                resultSet.getInt("y"),
                resultSet.getInt("z"),
                resultSet.getString("facing"),
                resultSet.getInt("model_version"),
                resultSet.getInt("active") != 0,
                Instant.parse(resultSet.getString("created_at")));
    }
}
