package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Repository de la table {@code village_centers} (issue #151). JDBC pur, aucun type Bukkit.
 * {@code id} est la seule identité stable : {@link #upsert} déplace/renomme/réactive un centre
 * existant sans jamais changer son {@code id} ni perdre les références qui le ciblent déjà.
 */
public final class VillageCenterRepository {

    private static final String UPSERT = """
            INSERT INTO village_centers (id, name, world, x, y, z, yaw, pitch, active, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = excluded.name, world = excluded.world, x = excluded.x, y = excluded.y,
                z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch, active = excluded.active
            """;
    private static final String SELECT_ALL = "SELECT * FROM village_centers";
    private static final String DELETE = "DELETE FROM village_centers WHERE id = ?";
    private static final String SET_ACTIVE = "UPDATE village_centers SET active = ? WHERE id = ?";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public VillageCenterRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    public CompletableFuture<List<VillageCenter>> loadAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<VillageCenter> centers = new ArrayList<>();
                while (resultSet.next()) {
                    centers.add(map(resultSet));
                }
                return centers;
            }
        });
    }

    public CompletableFuture<Void> upsert(VillageCenter center) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT))) {
                statement.setString(1, center.id());
                statement.setString(2, center.name());
                statement.setString(3, center.world());
                statement.setDouble(4, center.x());
                statement.setDouble(5, center.y());
                statement.setDouble(6, center.z());
                statement.setFloat(7, center.yaw());
                statement.setFloat(8, center.pitch());
                statement.setInt(9, center.active() ? 1 : 0);
                statement.setString(10, center.createdAt().toString());
                statement.executeUpdate();
                return null;
            }
        });
    }

    public CompletableFuture<Integer> delete(String id) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                statement.setString(1, id);
                return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Integer> setActive(String id, boolean active) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SET_ACTIVE)) {
                statement.setInt(1, active ? 1 : 0);
                statement.setString(2, id);
                return statement.executeUpdate();
            }
        });
    }

    private VillageCenter map(ResultSet resultSet) throws SQLException {
        return new VillageCenter(
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getString("world"),
                resultSet.getDouble("x"),
                resultSet.getDouble("y"),
                resultSet.getDouble("z"),
                resultSet.getFloat("yaw"),
                resultSet.getFloat("pitch"),
                resultSet.getInt("active") != 0,
                Instant.parse(resultSet.getString("created_at")));
    }
}
