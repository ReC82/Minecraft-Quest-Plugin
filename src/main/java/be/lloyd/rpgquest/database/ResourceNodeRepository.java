package be.lloyd.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Repository pour {@code resource_nodes} (positions de nœuds de ressource par
 * monde). Pure JDBC, requêtes préparées uniquement, aucun type Bukkit/Paper —
 * même conception que {@link QuestProgressRepository}.
 */
public final class ResourceNodeRepository {

    private static final String SELECT_ALL =
            "SELECT world, x, y, z, type_id, depleted_at FROM resource_nodes";
    private static final String UPSERT = """
            INSERT INTO resource_nodes (world, x, y, z, type_id, depleted_at) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (world, x, y, z) DO UPDATE SET type_id = excluded.type_id, depleted_at = excluded.depleted_at
            """;
    private static final String UPDATE_DEPLETED_AT =
            "UPDATE resource_nodes SET depleted_at = ? WHERE world = ? AND x = ? AND y = ? AND z = ?";
    private static final String DELETE =
            "DELETE FROM resource_nodes WHERE world = ? AND x = ? AND y = ? AND z = ?";

    private final DatabaseManager database;

    public ResourceNodeRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<List<ResourceNodeRecord>> findAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<ResourceNodeRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(map(resultSet));
                }
                return records;
            }
        });
    }

    public CompletableFuture<Void> upsert(ResourceNodeRecord record) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                statement.setString(1, record.world());
                statement.setInt(2, record.x());
                statement.setInt(3, record.y());
                statement.setInt(4, record.z());
                statement.setString(5, record.typeId());
                setNullableInstant(statement, 6, record.depletedAt());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> updateDepletedAt(String world, int x, int y, int z, Instant depletedAt) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_DEPLETED_AT)) {
                setNullableInstant(statement, 1, depletedAt);
                statement.setString(2, world);
                statement.setInt(3, x);
                statement.setInt(4, y);
                statement.setInt(5, z);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> delete(String world, int x, int y, int z) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                statement.setString(1, world);
                statement.setInt(2, x);
                statement.setInt(3, y);
                statement.setInt(4, z);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void setNullableInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, instant.toString());
        }
    }

    private ResourceNodeRecord map(ResultSet resultSet) throws SQLException {
        String rawDepletedAt = resultSet.getString("depleted_at");
        return new ResourceNodeRecord(
                resultSet.getString("world"),
                resultSet.getInt("x"),
                resultSet.getInt("y"),
                resultSet.getInt("z"),
                resultSet.getString("type_id"),
                rawDepletedAt == null ? null : Instant.parse(rawDepletedAt));
    }
}
