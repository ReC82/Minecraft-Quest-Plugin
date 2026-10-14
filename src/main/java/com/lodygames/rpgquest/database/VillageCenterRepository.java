package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
            INSERT INTO village_centers (id, name, world, x, y, z, yaw, pitch, active, created_at,
                detection_distance, central_npc_id, arrival_world, arrival_x, arrival_y, arrival_z,
                arrival_yaw, arrival_pitch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = excluded.name, world = excluded.world, x = excluded.x, y = excluded.y,
                z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch, active = excluded.active,
                detection_distance = excluded.detection_distance,
                central_npc_id = excluded.central_npc_id, arrival_world = excluded.arrival_world,
                arrival_x = excluded.arrival_x, arrival_y = excluded.arrival_y,
                arrival_z = excluded.arrival_z, arrival_yaw = excluded.arrival_yaw,
                arrival_pitch = excluded.arrival_pitch
            """;
    private static final String SELECT_ALL = "SELECT * FROM village_centers";
    private static final String DELETE = "DELETE FROM village_centers WHERE id = ?";
    private static final String SET_ACTIVE = "UPDATE village_centers SET active = ? WHERE id = ?";
    /** Issue #178 : supprimer une ville efface aussi ses découvertes, dans la même transaction. */
    private static final String DELETE_DISCOVERIES = "DELETE FROM city_discoveries WHERE city_id = ?";

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
                statement.setInt(11, center.detectionDistance());
                statement.setString(12, center.hasCentralNpc() ? center.centralNpcId() : null);
                VillageCenter.Arrival arrival = center.arrival();
                if (arrival == null) {
                    statement.setNull(13, Types.VARCHAR);
                    for (int i = 14; i <= 18; i++) {
                        statement.setNull(i, Types.DOUBLE);
                    }
                } else {
                    statement.setString(13, arrival.world());
                    statement.setDouble(14, arrival.x());
                    statement.setDouble(15, arrival.y());
                    statement.setDouble(16, arrival.z());
                    statement.setDouble(17, arrival.yaw());
                    statement.setDouble(18, arrival.pitch());
                }
                statement.executeUpdate();
                return null;
            }
        });
    }

    /**
     * Supprime le centre <strong>et</strong> les découvertes de cette ville (issue #178), dans une
     * seule transaction : une découverte orpheline ressusciterait si l'identifiant était un jour
     * réutilisé, et compterait alors pour une ville que le joueur n'a jamais vue.
     */
    public CompletableFuture<Integer> delete(String id) {
        return database.execute(connection -> {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement discoveries = connection.prepareStatement(DELETE_DISCOVERIES);
                 PreparedStatement statement = connection.prepareStatement(DELETE)) {
                discoveries.setString(1, id);
                discoveries.executeUpdate();
                statement.setString(1, id);
                int deleted = statement.executeUpdate();
                connection.commit();
                return deleted;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
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
                Instant.parse(resultSet.getString("created_at")),
                resultSet.getInt("detection_distance"),
                resultSet.getString("central_npc_id"),
                arrival(resultSet));
    }

    /** Issue #322 : une arrivée n'existe que complète (monde présent) ; sinon le voyage vise le centre. */
    private static VillageCenter.Arrival arrival(ResultSet resultSet) throws SQLException {
        String world = resultSet.getString("arrival_world");
        if (world == null || world.isBlank()) {
            return null;
        }
        return new VillageCenter.Arrival(world, resultSet.getDouble("arrival_x"),
                resultSet.getDouble("arrival_y"), resultSet.getDouble("arrival_z"),
                resultSet.getFloat("arrival_yaw"), resultSet.getFloat("arrival_pitch"));
    }
}
