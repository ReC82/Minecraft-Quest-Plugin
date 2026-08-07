package be.lloyd.rpgquest.database;

import be.lloyd.rpgquest.entitlement.EntitlementService;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Pure-JDBC implementation of {@link EntitlementService}, backed by {@code
 * player_entitlements}. Same placement convention as every other repository
 * in this project ({@code database} package regardless of which feature
 * consumes it) ; the generic contract itself lives in {@code entitlement}.
 */
public final class EntitlementRepository implements EntitlementService {

    private static final String SELECT_TIER =
            "SELECT tier FROM player_entitlements WHERE player_uuid = ? AND entitlement_key = ?";
    private static final String UPSERT_ENTITLEMENT = """
            INSERT INTO player_entitlements (player_uuid, entitlement_key, tier, granted_at, reason)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid, entitlement_key)
            DO UPDATE SET tier = excluded.tier, granted_at = excluded.granted_at, reason = excluded.reason
            """;
    private static final String DELETE_ENTITLEMENT =
            "DELETE FROM player_entitlements WHERE player_uuid = ? AND entitlement_key = ?";

    private final DatabaseManager database;

    public EntitlementRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Optional<String>> currentTier(UUID playerId, String entitlementKey) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_TIER)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, entitlementKey);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(resultSet.getString("tier")) : Optional.<String>empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> grant(UUID playerId, String entitlementKey, String tier, String reason) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_ENTITLEMENT)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, entitlementKey);
                statement.setString(3, tier);
                statement.setString(4, Instant.now().toString());
                statement.setString(5, reason);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> revoke(UUID playerId, String entitlementKey, String reason) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ENTITLEMENT)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, entitlementKey);
                statement.executeUpdate();
            }
            return null;
        });
    }

}
