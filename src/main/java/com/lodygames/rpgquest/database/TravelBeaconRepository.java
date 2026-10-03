package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.travel.beacon.model.TravelBeacon;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Repository de la table {@code travel_beacons} (issues #132/#150). JDBC pur, aucun type Bukkit.
 * Table strictement séparée de {@code waypoints}/{@code waystones} : une borne n'est jamais une
 * destination en elle-même, seulement un point d'accès au menu de voyage.
 */
public final class TravelBeaconRepository {

    private static final String INSERT_IGNORE = """
            INSERT OR IGNORE INTO travel_beacons (id, world, x, y, z, facing, model_version, active, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_ALL = "SELECT * FROM travel_beacons";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public TravelBeaconRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    public CompletableFuture<List<TravelBeacon>> loadAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<TravelBeacon> beacons = new ArrayList<>();
                while (resultSet.next()) {
                    beacons.add(map(resultSet));
                }
                return beacons;
            }
        });
    }

    /** {@code true} si la ligne a bien été insérée (aucune borne n'existait déjà pour cet id). */
    public CompletableFuture<Boolean> insertIfAbsent(TravelBeacon beacon) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(INSERT_IGNORE))) {
                statement.setString(1, beacon.id());
                statement.setString(2, beacon.world());
                statement.setInt(3, beacon.x());
                statement.setInt(4, beacon.y());
                statement.setInt(5, beacon.z());
                statement.setString(6, beacon.facing());
                statement.setInt(7, beacon.modelVersion());
                statement.setInt(8, beacon.active() ? 1 : 0);
                statement.setString(9, beacon.createdAt().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private TravelBeacon map(ResultSet resultSet) throws SQLException {
        return new TravelBeacon(
                resultSet.getString("id"),
                resultSet.getString("world"),
                resultSet.getInt("x"),
                resultSet.getInt("y"),
                resultSet.getInt("z"),
                resultSet.getString("facing"),
                resultSet.getInt("model_version"),
                resultSet.getInt("active") != 0,
                Instant.parse(resultSet.getString("created_at")));
    }
}
