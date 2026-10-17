package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository de la table {@code vault_access_points} (schéma V44, issue #105) : quel bloc
 * (monde + position) ouvre quel {@code vault_id}. Pure JDBC sur l'exécuteur unique de
 * {@link DatabaseManager}. Ne lit ni n'écrit jamais {@code personal_vaults} : un point d'accès
 * n'est qu'une porte, le contenu reste attaché à (UUID joueur, {@code vault_id}).
 */
public final class VaultAccessPointRepository {

    private static final String SELECT_ALL =
            "SELECT world, x, y, z, vault_id FROM vault_access_points ORDER BY vault_id, world, x, y, z";
    private static final String UPSERT = """
            INSERT INTO vault_access_points (world, x, y, z, vault_id, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (world, x, y, z)
            DO UPDATE SET vault_id = excluded.vault_id, created_by = excluded.created_by,
                          created_at = excluded.created_at
            """;
    private static final String DELETE =
            "DELETE FROM vault_access_points WHERE world = ? AND x = ? AND y = ? AND z = ?";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public VaultAccessPointRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    public CompletableFuture<List<StoredAccessPoint>> findAll() {
        return database.execute(connection -> {
            List<StoredAccessPoint> points = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    points.add(new StoredAccessPoint(resultSet.getString("world"), resultSet.getInt("x"),
                            resultSet.getInt("y"), resultSet.getInt("z"), resultSet.getString("vault_id")));
                }
            }
            return points;
        });
    }

    /** Lie (ou relie à un autre {@code vault_id}) une position. Un seul statement, donc atomique. */
    public CompletableFuture<Void> save(StoredAccessPoint point, UUID createdBy) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT))) {
                statement.setString(1, point.world());
                statement.setInt(2, point.x());
                statement.setInt(3, point.y());
                statement.setInt(4, point.z());
                statement.setString(5, point.vaultId());
                statement.setString(6, createdBy.toString());
                statement.setString(7, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Retourne {@code true} si une ligne a été supprimée. */
    public CompletableFuture<Boolean> delete(String world, int x, int y, int z) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                statement.setString(1, world);
                statement.setInt(2, x);
                statement.setInt(3, y);
                statement.setInt(4, z);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public record StoredAccessPoint(String world, int x, int y, int z, String vaultId) {
    }
}
