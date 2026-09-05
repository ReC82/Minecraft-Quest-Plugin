package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for the {@code backpacks}/{@code backpack_overflow}/{@code
 * backpack_audit} tables. Pure JDBC. {@link #applyResize} is the only
 * multi-statement operation and runs as a single explicit JDBC transaction
 * (same conception as {@code WalletRepository}) : the resized content and
 * any overflow it produces are written together, or neither is — an object
 * that doesn't fit after a downgrade can never simply vanish (mission
 * étape 20, validation).
 */
public final class BackpackRepository {

    private static final String SELECT_BACKPACK =
            "SELECT schema_version, contents FROM backpacks WHERE player_uuid = ?";
    private static final String UPSERT_BACKPACK = """
            INSERT INTO backpacks (player_uuid, schema_version, contents, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (player_uuid)
            DO UPDATE SET schema_version = excluded.schema_version, contents = excluded.contents,
                          updated_at = excluded.updated_at
            """;
    private static final String INSERT_OVERFLOW = """
            INSERT INTO backpack_overflow (player_uuid, schema_version, contents, reason, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String SELECT_UNCLAIMED_OVERFLOW = """
            SELECT id, schema_version, contents, reason, created_at FROM backpack_overflow
            WHERE player_uuid = ? AND claimed_at IS NULL ORDER BY id
            """;
    private static final String MARK_OVERFLOW_CLAIMED =
            "UPDATE backpack_overflow SET claimed_at = ? WHERE id = ? AND claimed_at IS NULL";
    private static final String INSERT_AUDIT =
            "INSERT INTO backpack_audit (player_uuid, event_type, detail, created_at) VALUES (?, ?, ?, ?)";

    private final DatabaseManager database;

    public BackpackRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Optional<StoredBackpack>> find(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_BACKPACK)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new StoredBackpack(
                            resultSet.getInt("schema_version"), resultSet.getBytes("contents")));
                }
            }
        });
    }

    /** Sauvegarde simple (fermeture/déconnexion/arrêt) : une seule ligne, un seul statement, déjà atomique. */
    public CompletableFuture<Void> save(UUID playerId, int schemaVersion, byte[] contents) {
        return database.execute(connection -> {
            upsertBackpack(connection, playerId, schemaVersion, contents);
            return null;
        });
    }

    /**
     * Redimensionnement (upgrade/downgrade) : écrit le nouveau contenu et, s'il y a un surplus,
     * l'entrée de récupération correspondante et sa trace d'audit, dans une seule transaction.
     */
    public CompletableFuture<Void> applyResize(UUID playerId, int schemaVersion, byte[] newContents,
                                                byte[] overflowContents, String overflowReason) {
        return database.execute(connection -> inTransaction(connection, () -> {
            upsertBackpack(connection, playerId, schemaVersion, newContents);
            if (overflowContents != null) {
                insertOverflow(connection, playerId, schemaVersion, overflowContents, overflowReason);
                insertAudit(connection, playerId, "OVERFLOW_CREATED", overflowReason);
            }
            return null;
        }));
    }

    public CompletableFuture<List<OverflowEntry>> findUnclaimedOverflow(UUID playerId) {
        return database.execute(connection -> {
            List<OverflowEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_UNCLAIMED_OVERFLOW)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(new OverflowEntry(
                                resultSet.getLong("id"), resultSet.getInt("schema_version"),
                                resultSet.getBytes("contents"), resultSet.getString("reason"),
                                Instant.parse(resultSet.getString("created_at"))));
                    }
                }
            }
            return entries;
        });
    }

    public CompletableFuture<Boolean> markOverflowClaimed(long overflowId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(MARK_OVERFLOW_CLAIMED)) {
                statement.setString(1, Instant.now().toString());
                statement.setLong(2, overflowId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Void> logAudit(UUID playerId, String eventType, String detail) {
        return database.execute(connection -> {
            insertAudit(connection, playerId, eventType, detail);
            return null;
        });
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }

    private <T> T inTransaction(Connection connection, SqlAction<T> action) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = action.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void upsertBackpack(Connection connection, UUID playerId, int schemaVersion, byte[] contents)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_BACKPACK)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, schemaVersion);
            statement.setBytes(3, contents);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private void insertOverflow(Connection connection, UUID playerId, int schemaVersion, byte[] contents, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_OVERFLOW)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, schemaVersion);
            statement.setBytes(3, contents);
            statement.setString(4, reason);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private void insertAudit(Connection connection, UUID playerId, String eventType, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_AUDIT)) {
            statement.setString(1, playerId == null ? null : playerId.toString());
            statement.setString(2, eventType);
            statement.setString(3, detail);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    public record StoredBackpack(int schemaVersion, byte[] contents) {
    }

    public record OverflowEntry(long id, int schemaVersion, byte[] contents, String reason, Instant createdAt) {
    }
}
