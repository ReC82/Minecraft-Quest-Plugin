package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.encounter.model.EncounterSite;
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
 * Repository de {@code encounter_sites} (issue #13). JDBC pur, aucun type Bukkit. La base est la
 * seule source de vérité : l'index unique {@code (world, biome_instance)} + {@code INSERT OR IGNORE}
 * garantissent qu'une instance de biome n'accueille jamais deux rencontres, au redémarrage comme
 * lors de deux entrées simultanées.
 */
public final class EncounterSiteRepository {

    private static final String INSERT = """
            INSERT OR IGNORE INTO encounter_sites
                (id, world, biome_instance, biome_key, region_x, region_z, template_id, npc_id, x, y, z,
                 citizens_uuid, citizens_id, status, last_error, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_ALL = "SELECT * FROM encounter_sites";
    private static final String SELECT_BY_INSTANCE =
            "SELECT * FROM encounter_sites WHERE world = ? AND biome_instance = ?";
    private static final String UPDATE_CITIZENS =
            "UPDATE encounter_sites SET citizens_uuid = ?, citizens_id = ?, last_error = '', updated_at = ? WHERE id = ?";
    private static final String UPDATE_STATUS =
            "UPDATE encounter_sites SET status = ?, updated_at = ? WHERE id = ?";
    private static final String UPDATE_ERROR =
            "UPDATE encounter_sites SET last_error = ?, updated_at = ? WHERE id = ?";
    private static final String DELETE = "DELETE FROM encounter_sites WHERE id = ?";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public EncounterSiteRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    public CompletableFuture<List<EncounterSite>> loadAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<EncounterSite> sites = new ArrayList<>();
                while (resultSet.next()) {
                    sites.add(map(resultSet));
                }
                return sites;
            }
        });
    }

    public CompletableFuture<Optional<EncounterSite>> findByInstance(String world, String biomeInstance) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_INSTANCE)) {
                statement.setString(1, world);
                statement.setString(2, biomeInstance);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.<EncounterSite>empty();
                }
            }
        });
    }

    /** {@code true} si la ligne a été insérée (aucune rencontre n'existait pour cette instance). */
    public CompletableFuture<Boolean> insertIfAbsent(EncounterSite site) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(INSERT))) {
                statement.setString(1, site.id());
                statement.setString(2, site.world());
                statement.setString(3, site.biomeInstance());
                statement.setString(4, site.biomeKey());
                statement.setLong(5, site.regionX());
                statement.setLong(6, site.regionZ());
                statement.setString(7, site.templateId());
                statement.setString(8, site.npcId());
                statement.setInt(9, site.x());
                statement.setInt(10, site.y());
                statement.setInt(11, site.z());
                statement.setString(12, site.citizensUuid() == null ? "" : site.citizensUuid().toString());
                statement.setInt(13, site.citizensId());
                statement.setString(14, site.status().name());
                statement.setString(15, site.lastError());
                statement.setString(16, site.createdAt().toString());
                statement.setString(17, site.updatedAt().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    /** Remplace l'entité Citizens d'une occurrence ({@code uuid == null} = plus aucune entité). */
    public CompletableFuture<Boolean> updateCitizens(String id, UUID uuid, int numericId, Instant at) {
        return update(UPDATE_CITIZENS, statement -> {
            statement.setString(1, uuid == null ? "" : uuid.toString());
            statement.setInt(2, uuid == null ? -1 : numericId);
            statement.setString(3, at.toString());
            statement.setString(4, id);
        });
    }

    public CompletableFuture<Boolean> updateStatus(String id, EncounterSite.Status status, Instant at) {
        return update(UPDATE_STATUS, statement -> {
            statement.setString(1, status.name());
            statement.setString(2, at.toString());
            statement.setString(3, id);
        });
    }

    public CompletableFuture<Boolean> updateError(String id, String error, Instant at) {
        return update(UPDATE_ERROR, statement -> {
            statement.setString(1, error == null ? "" : error);
            statement.setString(2, at.toString());
            statement.setString(3, id);
        });
    }

    public CompletableFuture<Boolean> delete(String id) {
        return update(DELETE, statement -> statement.setString(1, id));
    }

    private CompletableFuture<Boolean> update(String sql, Binder binder) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static EncounterSite map(ResultSet rs) throws SQLException {
        String uuid = rs.getString("citizens_uuid");
        EncounterSite.Status status;
        try {
            status = EncounterSite.Status.valueOf(rs.getString("status"));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            status = EncounterSite.Status.DISABLED;
        }
        return new EncounterSite(rs.getString("id"), rs.getString("world"), rs.getString("biome_instance"),
                rs.getString("biome_key"), rs.getLong("region_x"), rs.getLong("region_z"),
                rs.getString("template_id"), rs.getString("npc_id"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                uuid == null || uuid.isBlank() ? null : UUID.fromString(uuid), rs.getInt("citizens_id"), status,
                rs.getString("last_error"), Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }
}
