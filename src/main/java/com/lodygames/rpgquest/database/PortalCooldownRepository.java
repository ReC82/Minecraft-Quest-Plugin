package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for the {@code portal_cooldowns} table. Pure JDBC, no Bukkit
 * types. {@link #allForPlayer} is meant to be read once, at connection
 * (see {@code travel.PortalService}), and cached in memory afterwards —
 * {@code PlayerMoveEvent} fires far too often to justify a per-event
 * asynchronous database round trip.
 */
public final class PortalCooldownRepository {

    private static final String SELECT_ALL_FOR_PLAYER =
            "SELECT portal_id, expires_at FROM portal_cooldowns WHERE player_uuid = ?";
    private static final String UPSERT = """
            INSERT INTO portal_cooldowns (player_uuid, portal_id, expires_at) VALUES (?, ?, ?)
            ON CONFLICT (player_uuid, portal_id) DO UPDATE SET expires_at = excluded.expires_at
            """;
    private static final String DELETE_ALL_FOR_PLAYER = "DELETE FROM portal_cooldowns WHERE player_uuid = ?";

    private final DatabaseManager database;

    public PortalCooldownRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Map<String, Instant>> allForPlayer(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL_FOR_PLAYER)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, Instant> cooldowns = new HashMap<>();
                    while (resultSet.next()) {
                        cooldowns.put(resultSet.getString("portal_id"), Instant.parse(resultSet.getString("expires_at")));
                    }
                    return cooldowns;
                }
            }
        });
    }

    public CompletableFuture<Void> setCooldown(UUID playerId, String portalId, Instant expiresAt) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, portalId);
                statement.setString(3, expiresAt.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Supprime tous les cooldowns de portail d'un joueur (reset admin « nouveau joueur »). */
    public CompletableFuture<Integer> deleteAllForPlayer(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ALL_FOR_PLAYER)) {
                statement.setString(1, playerId.toString());
                return statement.executeUpdate();
            }
        });
    }
}
