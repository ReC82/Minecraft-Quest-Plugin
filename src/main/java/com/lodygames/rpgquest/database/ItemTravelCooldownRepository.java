package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository de la table {@code item_travel_cooldowns} (mission « Rune de rappel »). JDBC pur,
 * aucun type Bukkit. {@link #allForPlayer} est lu une seule fois, à la connexion (voir
 * {@code travel.ItemTravelService}), puis mis en cache mémoire — un clic droit ne déclenche jamais
 * de requête asynchrone.
 */
public final class ItemTravelCooldownRepository {

    private static final String SELECT_ALL_FOR_PLAYER =
            "SELECT item_id, expires_at FROM item_travel_cooldowns WHERE player_uuid = ?";
    private static final String UPSERT = """
            INSERT INTO item_travel_cooldowns (player_uuid, item_id, expires_at) VALUES (?, ?, ?)
            ON CONFLICT (player_uuid, item_id) DO UPDATE SET expires_at = excluded.expires_at
            """;
    private static final String DELETE_ALL_FOR_PLAYER = "DELETE FROM item_travel_cooldowns WHERE player_uuid = ?";

    private final DatabaseManager database;

    public ItemTravelCooldownRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Map<String, Instant>> allForPlayer(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL_FOR_PLAYER)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, Instant> cooldowns = new HashMap<>();
                    while (resultSet.next()) {
                        cooldowns.put(resultSet.getString("item_id"), Instant.parse(resultSet.getString("expires_at")));
                    }
                    return cooldowns;
                }
            }
        });
    }

    public CompletableFuture<Void> setCooldown(UUID playerId, String itemId, Instant expiresAt) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, itemId);
                statement.setString(3, expiresAt.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Supprime tous les cooldowns de voyage par objet d'un joueur (reset admin « nouveau joueur »). */
    public CompletableFuture<Integer> deleteAllForPlayer(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ALL_FOR_PLAYER)) {
                statement.setString(1, playerId.toString());
                return statement.executeUpdate();
            }
        });
    }
}
