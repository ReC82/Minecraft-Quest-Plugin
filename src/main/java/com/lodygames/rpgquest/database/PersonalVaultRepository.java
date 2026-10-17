package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository de la table {@code personal_vaults} (schéma V43, issue #104). Pure JDBC, sur
 * l'exécuteur unique de {@link DatabaseManager} : une sauvegarde mise en file avant une lecture
 * est toujours visible de cette lecture. Clé = (UUID joueur, {@code vault_id}) — jamais une
 * coordonnée ni un Hub. Chaque écriture est un seul statement, donc déjà atomique.
 */
public final class PersonalVaultRepository {

    private static final String SELECT_VAULT =
            "SELECT schema_version, contents FROM personal_vaults WHERE player_uuid = ? AND vault_id = ?";
    private static final String SELECT_VAULT_IDS =
            "SELECT vault_id FROM personal_vaults WHERE player_uuid = ? ORDER BY vault_id";
    private static final String UPSERT_VAULT = """
            INSERT INTO personal_vaults (player_uuid, vault_id, schema_version, contents, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid, vault_id)
            DO UPDATE SET schema_version = excluded.schema_version, contents = excluded.contents,
                          updated_at = excluded.updated_at
            """;

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public PersonalVaultRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    public CompletableFuture<Optional<StoredVault>> find(UUID playerId, String vaultId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_VAULT)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, vaultId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new StoredVault(resultSet.getInt("schema_version"), resultSet.getBytes("contents")));
                }
            }
        });
    }

    /** Coffres déjà persistés pour ce joueur (un coffre jamais fermé n'a pas encore de ligne). */
    public CompletableFuture<List<String>> vaultIds(UUID playerId) {
        return database.execute(connection -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_VAULT_IDS)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        ids.add(resultSet.getString("vault_id"));
                    }
                }
            }
            return ids;
        });
    }

    public CompletableFuture<Void> save(UUID playerId, String vaultId, int schemaVersion, byte[] contents) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT_VAULT))) {
                statement.setString(1, playerId.toString());
                statement.setString(2, vaultId);
                statement.setInt(3, schemaVersion);
                statement.setBytes(4, contents);
                statement.setString(5, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public record StoredVault(int schemaVersion, byte[] contents) {
    }
}
