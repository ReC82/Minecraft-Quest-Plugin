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
            "SELECT item_id, expires_at, remaining_ms FROM item_travel_cooldowns WHERE player_uuid = ?";
    private static final String UPSERT_REMAINING = """
            INSERT INTO item_travel_cooldowns (player_uuid, item_id, expires_at, remaining_ms) VALUES (?, ?, ?, ?)
            ON CONFLICT (player_uuid, item_id) DO UPDATE SET expires_at = excluded.expires_at,
                remaining_ms = excluded.remaining_ms
            """;
    private static final String DELETE_ONE = "DELETE FROM item_travel_cooldowns WHERE player_uuid = ? AND item_id = ?";
    private static final String UPSERT = """
            INSERT INTO item_travel_cooldowns (player_uuid, item_id, expires_at) VALUES (?, ?, ?)
            ON CONFLICT (player_uuid, item_id) DO UPDATE SET expires_at = excluded.expires_at
            """;
    private static final String DELETE_ALL_FOR_PLAYER = "DELETE FROM item_travel_cooldowns WHERE player_uuid = ?";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public ItemTravelCooldownRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    public CompletableFuture<Map<String, Instant>> allForPlayer(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL_FOR_PLAYER)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    // Issue #221 : une durée restante est une PAUSE — elle reprend à partir de
                    // maintenant, donc le temps hors ligne et l'arrêt du serveur ne l'ont pas
                    // consommée. Sans durée (ligne antérieure), l'échéance absolue s'applique.
                    Instant now = Instant.now();
                    Map<String, Instant> cooldowns = new HashMap<>();
                    while (resultSet.next()) {
                        long remaining = resultSet.getLong("remaining_ms");
                        boolean paused = !resultSet.wasNull();
                        cooldowns.put(resultSet.getString("item_id"), paused
                                ? now.plusMillis(Math.max(0, remaining))
                                : Instant.parse(resultSet.getString("expires_at")));
                    }
                    return cooldowns;
                }
            }
        });
    }

    public CompletableFuture<Void> setCooldown(UUID playerId, String itemId, Instant expiresAt) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT))) {
                statement.setString(1, playerId.toString());
                statement.setString(2, itemId);
                statement.setString(3, expiresAt.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Issue #221 : enregistre la DURÉE RESTANTE (jamais négative) ; une durée nulle supprime la
     * ligne — le cooldown est fini, il n'y a rien à reprendre.
     */
    public CompletableFuture<Void> saveRemaining(UUID playerId, String itemId, java.time.Duration remaining) {
        long millis = Math.max(0, remaining.toMillis());
        return database.execute(connection -> {
            if (millis == 0) {
                try (PreparedStatement statement = connection.prepareStatement(DELETE_ONE)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, itemId);
                    statement.executeUpdate();
                }
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT_REMAINING))) {
                statement.setString(1, playerId.toString());
                statement.setString(2, itemId);
                statement.setString(3, Instant.now().plusMillis(millis).toString());
                statement.setLong(4, millis);
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
