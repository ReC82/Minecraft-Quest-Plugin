package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for the free-form {@code player_variables} key/value store.
 * Pure JDBC, no Bukkit/Paper types.
 */
public final class PlayerVariableRepository {

    private static final String SELECT =
            "SELECT variable_value FROM player_variables WHERE player_uuid = ? AND variable_key = ?";
    private static final String UPSERT = """
            INSERT INTO player_variables (player_uuid, variable_key, variable_value) VALUES (?, ?, ?)
            ON CONFLICT (player_uuid, variable_key) DO UPDATE SET variable_value = excluded.variable_value
            """;

    private final DatabaseManager database;

    public PlayerVariableRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Optional<String>> get(UUID uuid, String key) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, key);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.ofNullable(resultSet.getString("variable_value"))
                            : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Void> set(UUID uuid, String key, String value) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, key);
                statement.setString(3, value);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
