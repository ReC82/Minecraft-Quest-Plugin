package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.waystone.model.Waystone;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository des tables {@code waystones} et {@code waystone_discoveries} (mission « Waystones
 * Wild »). JDBC pur, aucun type Bukkit. La base est la seule source de vérité : une cellule dont
 * une ligne existe déjà n'est jamais régénérée (pas de doublon au reload/redémarrage), garanti en
 * plus par l'index unique {@code (world, cell_x, cell_z)}.
 */
public final class WaystoneRepository {

    private static final String INSERT_WAYSTONE = """
            INSERT OR IGNORE INTO waystones (id, world, x, y, z, cell_x, cell_z, name, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_ALL = "SELECT * FROM waystones";
    private static final String INSERT_DISCOVERY = """
            INSERT OR IGNORE INTO waystone_discoveries (player_uuid, waystone_id, discovered_at) VALUES (?, ?, ?)
            """;
    private static final String SELECT_DISCOVERIES =
            "SELECT waystone_id FROM waystone_discoveries WHERE player_uuid = ?";
    private static final String DELETE_DISCOVERIES =
            "DELETE FROM waystone_discoveries WHERE player_uuid = ?";

    private final DatabaseManager database;

    public WaystoneRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<List<Waystone>> loadAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<Waystone> waystones = new ArrayList<>();
                while (resultSet.next()) {
                    waystones.add(map(resultSet));
                }
                return waystones;
            }
        });
    }

    /** {@code true} si la ligne a bien été insérée (aucune Waystone n'existait pour cette cellule). */
    public CompletableFuture<Boolean> insertIfAbsent(Waystone waystone) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_WAYSTONE)) {
                statement.setString(1, waystone.id());
                statement.setString(2, waystone.world());
                statement.setInt(3, waystone.x());
                statement.setInt(4, waystone.y());
                statement.setInt(5, waystone.z());
                statement.setLong(6, waystone.cellX());
                statement.setLong(7, waystone.cellZ());
                statement.setString(8, waystone.name());
                statement.setString(9, waystone.createdAt().toString());
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
                        ids.add(resultSet.getString("waystone_id"));
                    }
                    return ids;
                }
            }
        });
    }

    /** {@code true} si c'est une découverte réellement nouvelle pour ce joueur. */
    public CompletableFuture<Boolean> recordDiscovery(UUID playerId, String waystoneId, Instant at) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_DISCOVERY)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, waystoneId);
                statement.setString(3, at.toString());
                return statement.executeUpdate() > 0;
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

    private Waystone map(ResultSet resultSet) throws SQLException {
        return new Waystone(
                resultSet.getString("id"), resultSet.getString("world"),
                resultSet.getInt("x"), resultSet.getInt("y"), resultSet.getInt("z"),
                resultSet.getLong("cell_x"), resultSet.getLong("cell_z"),
                resultSet.getString("name"), Instant.parse(resultSet.getString("created_at")));
    }
}
