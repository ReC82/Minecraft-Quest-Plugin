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
    private static final String DELETE_ALL_FOR_PLAYER = "DELETE FROM player_variables WHERE player_uuid = ?";

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

    /**
     * Supprime toutes les variables d'un joueur (unlocks type {@code CLAIM_TIER_1}, quête suivie,
     * marqueur de kit de départ...) — utilisé par le reset admin « nouveau joueur » ({@code
     * player.PlayerResetService}). Ne concerne que ce joueur : {@code player_uuid} fait partie du
     * filtre, aucune autre ligne n'est touchée. Retourne le nombre de lignes supprimées.
     */
    public CompletableFuture<Integer> deleteAllForPlayer(UUID uuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ALL_FOR_PLAYER)) {
                statement.setString(1, uuid.toString());
                return statement.executeUpdate();
            }
        });
    }
}
