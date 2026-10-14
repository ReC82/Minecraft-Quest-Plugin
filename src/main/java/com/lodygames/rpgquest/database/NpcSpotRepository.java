package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.npc.spot.NpcSpot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Repository des tables {@code npc_spots} et {@code npc_spot_ids} (issue #381). JDBC pur, aucun type
 * Bukkit — testable sur une base SQLite temporaire.
 *
 * <p>La base est la <strong>source de vérité</strong> ; {@code NpcSpotService} n'en garde qu'une
 * copie mémoire. Un PNJ libre est stocké avec {@code npc_id = ''} (voir la migration V38).</p>
 */
public final class NpcSpotRepository {

    private static final String ALLOCATE_ID = "INSERT INTO npc_spot_ids (created_at) VALUES (?)";
    private static final String INSERT = """
            INSERT INTO npc_spots
                (id, name, world, x, y, z, yaw, pitch, radius, npc_id, created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_ALL = "SELECT * FROM npc_spots";
    private static final String UPDATE_NAME = "UPDATE npc_spots SET name = ?, updated_at = ? WHERE id = ?";
    private static final String UPDATE_ORIENTATION =
            "UPDATE npc_spots SET yaw = ?, pitch = ?, updated_at = ? WHERE id = ?";
    private static final String RELEASE_NPC_ELSEWHERE =
            "UPDATE npc_spots SET npc_id = '', updated_at = ? WHERE npc_id = ? AND id <> ?";
    private static final String ASSIGN =
            "UPDATE npc_spots SET npc_id = ?, updated_at = ? WHERE id = ? AND (npc_id = '' OR npc_id = ?)";
    private static final String RELEASE =
            "UPDATE npc_spots SET npc_id = '', updated_at = ? WHERE id = ? AND npc_id = ?";
    private static final String DELETE_FREE = "DELETE FROM npc_spots WHERE id = ? AND npc_id = ''";

    private final DatabaseManager database;

    public NpcSpotRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Réserve le prochain numéro, jamais réutilisé (l'insertion arbitre les créations simultanées). */
    public CompletableFuture<Integer> allocateNumber() {
        return database.execute(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(ALLOCATE_ID, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, Instant.now().toString());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getInt(1);
                }
            }
        });
    }

    public CompletableFuture<List<NpcSpot>> loadAll() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<NpcSpot> spots = new ArrayList<>();
                while (resultSet.next()) {
                    spots.add(map(resultSet));
                }
                return spots;
            }
        });
    }

    /** {@code INSERT} strict : un identifiant déjà présent est une anomalie, jamais un écrasement. */
    public CompletableFuture<Void> insert(NpcSpot spot) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, spot.id());
                statement.setString(2, spot.name());
                statement.setString(3, spot.world());
                statement.setDouble(4, spot.x());
                statement.setDouble(5, spot.y());
                statement.setDouble(6, spot.z());
                statement.setDouble(7, spot.yaw());
                statement.setDouble(8, spot.pitch());
                statement.setInt(9, spot.radius());
                statement.setString(10, spot.npcId() == null ? "" : spot.npcId());
                statement.setString(11, spot.createdBy());
                statement.setString(12, spot.createdAt().toString());
                statement.setString(13, spot.updatedAt().toString());
                statement.executeUpdate();
                return null;
            }
        });
    }

    public CompletableFuture<Integer> updateName(String id, String name, Instant now) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_NAME)) {
                statement.setString(1, name);
                statement.setString(2, now.toString());
                statement.setString(3, id);
                return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Integer> updateOrientation(String id, float yaw, float pitch, Instant now) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_ORIENTATION)) {
                statement.setDouble(1, yaw);
                statement.setDouble(2, pitch);
                statement.setString(3, now.toString());
                statement.setString(4, id);
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Affecte un PNJ à un emplacement <strong>en une transaction</strong> : il quitte d'abord tout
     * autre emplacement, puis occupe celui-ci — à condition qu'il soit libre ou déjà le sien. Un
     * emplacement pris entre-temps par un autre PNJ fait échouer l'ensemble (aucune ligne modifiée,
     * l'ancien emplacement est conservé).
     *
     * @return {@code true} si l'affectation a eu lieu
     */
    public CompletableFuture<Boolean> assign(String spotId, String npcId, Instant now) {
        return database.execute(connection -> inTransaction(connection, () -> {
            try (PreparedStatement release = connection.prepareStatement(RELEASE_NPC_ELSEWHERE);
                 PreparedStatement assign = connection.prepareStatement(ASSIGN)) {
                release.setString(1, now.toString());
                release.setString(2, npcId);
                release.setString(3, spotId);
                release.executeUpdate();
                assign.setString(1, npcId);
                assign.setString(2, now.toString());
                assign.setString(3, spotId);
                assign.setString(4, npcId);
                if (assign.executeUpdate() == 0) {
                    throw new AssignRefused();
                }
                return true;
            }
        })).exceptionally(error -> {
            if (rootOf(error) instanceof AssignRefused) {
                return false;
            }
            throw error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
        });
    }

    /** Libère l'emplacement s'il est bien occupé par ce PNJ. Rejouer est inoffensif ({@code 0}). */
    public CompletableFuture<Integer> release(String spotId, String expectedNpcId, Instant now) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(RELEASE)) {
                statement.setString(1, now.toString());
                statement.setString(2, spotId);
                statement.setString(3, expectedNpcId);
                return statement.executeUpdate();
            }
        });
    }

    /** Supprime un emplacement <strong>libre</strong> uniquement ; {@code 0} = absent ou occupé. */
    public CompletableFuture<Integer> deleteFree(String id) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_FREE)) {
                statement.setString(1, id);
                return statement.executeUpdate();
            }
        });
    }

    private static NpcSpot map(ResultSet resultSet) throws SQLException {
        String npcId = resultSet.getString("npc_id");
        return new NpcSpot(
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getString("world"),
                resultSet.getDouble("x"),
                resultSet.getDouble("y"),
                resultSet.getDouble("z"),
                (float) resultSet.getDouble("yaw"),
                (float) resultSet.getDouble("pitch"),
                resultSet.getInt("radius"),
                npcId == null || npcId.isEmpty() ? null : npcId,
                resultSet.getString("created_by"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")));
    }

    /** Signal interne : l'emplacement visé est occupé par un autre PNJ — on annule la transaction. */
    private static final class AssignRefused extends RuntimeException {
        AssignRefused() {
            super("emplacement occupé", null, false, false);
        }
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }

    private static <T> T inTransaction(Connection connection, SqlAction<T> action) throws SQLException {
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

    private static Throwable rootOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
