package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for {@code claims}/{@code claim_members}. Pure JDBC, no Bukkit
 * types — reuses {@link Claim} directly (see its Javadoc for why no
 * separate database-row type is needed here). Every owner-restricted write
 * (delete, trust, untrust, flag) embeds the ownership check in the same
 * statement/query rather than a separate read-then-write, so it stays a
 * single round trip.
 */
public final class ClaimRepository {

    private static final String SELECT_ALL_CLAIMS = "SELECT * FROM claims";
    private static final String SELECT_ALL_MEMBERS = "SELECT claim_id, member_uuid FROM claim_members";
    private static final String SELECT_EXISTS = "SELECT 1 FROM claims WHERE id = ?";
    private static final String INSERT_CLAIM = """
            INSERT INTO claims (id, owner_uuid, world, min_x, min_y, min_z, max_x, max_y, max_z,
                                 reserved_min_x, reserved_min_y, reserved_min_z,
                                 reserved_max_x, reserved_max_y, reserved_max_z,
                                 allow_public_redstone, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_MEMBER = "INSERT INTO claim_members (claim_id, member_uuid) VALUES (?, ?)";
    private static final String SELECT_OWNER = "SELECT owner_uuid FROM claims WHERE id = ?";
    private static final String DELETE_CLAIM = "DELETE FROM claims WHERE id = ? AND owner_uuid = ?";
    private static final String INSERT_MEMBER_IGNORE =
            "INSERT OR IGNORE INTO claim_members (claim_id, member_uuid) VALUES (?, ?)";
    private static final String DELETE_MEMBER = "DELETE FROM claim_members WHERE claim_id = ? AND member_uuid = ?";
    private static final String UPDATE_REDSTONE_FLAG =
            "UPDATE claims SET allow_public_redstone = ? WHERE id = ? AND owner_uuid = ?";

    private final DatabaseManager database;

    public ClaimRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<List<Claim>> allClaims() {
        return database.execute(connection -> {
            Map<String, Set<UUID>> membersByClaim = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(SELECT_ALL_MEMBERS)) {
                while (resultSet.next()) {
                    membersByClaim.computeIfAbsent(resultSet.getString("claim_id"), id -> new HashSet<>())
                            .add(UUID.fromString(resultSet.getString("member_uuid")));
                }
            }

            List<Claim> claims = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(SELECT_ALL_CLAIMS)) {
                while (resultSet.next()) {
                    String id = resultSet.getString("id");
                    claims.add(new Claim(
                            id,
                            UUID.fromString(resultSet.getString("owner_uuid")),
                            resultSet.getString("world"),
                            resultSet.getInt("min_x"), resultSet.getInt("min_y"), resultSet.getInt("min_z"),
                            resultSet.getInt("max_x"), resultSet.getInt("max_y"), resultSet.getInt("max_z"),
                            resultSet.getInt("reserved_min_x"), resultSet.getInt("reserved_min_y"), resultSet.getInt("reserved_min_z"),
                            resultSet.getInt("reserved_max_x"), resultSet.getInt("reserved_max_y"), resultSet.getInt("reserved_max_z"),
                            membersByClaim.getOrDefault(id, Set.of()),
                            new ClaimFlags(resultSet.getBoolean("allow_public_redstone"))));
                }
            }
            return claims;
        });
    }

    /** {@code false} si {@code claim.id()} existe déjà — rien n'est modifié dans ce cas. */
    public CompletableFuture<Boolean> create(Claim claim) {
        return database.execute(connection -> inTransaction(connection, () -> {
            try (PreparedStatement exists = connection.prepareStatement(SELECT_EXISTS)) {
                exists.setString(1, claim.id());
                try (ResultSet resultSet = exists.executeQuery()) {
                    if (resultSet.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(INSERT_CLAIM)) {
                insert.setString(1, claim.id());
                insert.setString(2, claim.owner().toString());
                insert.setString(3, claim.world());
                insert.setInt(4, claim.minX());
                insert.setInt(5, claim.minY());
                insert.setInt(6, claim.minZ());
                insert.setInt(7, claim.maxX());
                insert.setInt(8, claim.maxY());
                insert.setInt(9, claim.maxZ());
                insert.setInt(10, claim.reservedMinX());
                insert.setInt(11, claim.reservedMinY());
                insert.setInt(12, claim.reservedMinZ());
                insert.setInt(13, claim.reservedMaxX());
                insert.setInt(14, claim.reservedMaxY());
                insert.setInt(15, claim.reservedMaxZ());
                insert.setBoolean(16, claim.flags().allowPublicRedstone());
                insert.setString(17, Instant.now().toString());
                insert.executeUpdate();
            }
            for (UUID member : claim.members()) {
                try (PreparedStatement insertMember = connection.prepareStatement(INSERT_MEMBER)) {
                    insertMember.setString(1, claim.id());
                    insertMember.setString(2, member.toString());
                    insertMember.executeUpdate();
                }
            }
            return true;
        }));
    }

    public CompletableFuture<ClaimActionOutcome> delete(String id, UUID requester) {
        return database.execute(connection -> {
            ClaimActionOutcome ownerCheck = checkOwner(connection, id, requester);
            if (ownerCheck != ClaimActionOutcome.SUCCESS) {
                return ownerCheck;
            }
            try (PreparedStatement statement = connection.prepareStatement(DELETE_CLAIM)) {
                statement.setString(1, id);
                statement.setString(2, requester.toString());
                statement.executeUpdate();
            }
            return ClaimActionOutcome.SUCCESS;
        });
    }

    public CompletableFuture<ClaimActionOutcome> addMember(String id, UUID requester, UUID member) {
        return database.execute(connection -> {
            ClaimActionOutcome ownerCheck = checkOwner(connection, id, requester);
            if (ownerCheck != ClaimActionOutcome.SUCCESS) {
                return ownerCheck;
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_MEMBER_IGNORE)) {
                statement.setString(1, id);
                statement.setString(2, member.toString());
                statement.executeUpdate();
            }
            return ClaimActionOutcome.SUCCESS;
        });
    }

    public CompletableFuture<ClaimActionOutcome> removeMember(String id, UUID requester, UUID member) {
        return database.execute(connection -> {
            ClaimActionOutcome ownerCheck = checkOwner(connection, id, requester);
            if (ownerCheck != ClaimActionOutcome.SUCCESS) {
                return ownerCheck;
            }
            try (PreparedStatement statement = connection.prepareStatement(DELETE_MEMBER)) {
                statement.setString(1, id);
                statement.setString(2, member.toString());
                statement.executeUpdate();
            }
            return ClaimActionOutcome.SUCCESS;
        });
    }

    public CompletableFuture<ClaimActionOutcome> setAllowPublicRedstone(String id, UUID requester, boolean value) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_REDSTONE_FLAG)) {
                statement.setBoolean(1, value);
                statement.setString(2, id);
                statement.setString(3, requester.toString());
                int updated = statement.executeUpdate();
                if (updated == 1) {
                    return ClaimActionOutcome.SUCCESS;
                }
            }
            return checkOwner(connection, id, requester);
        });
    }

    private ClaimActionOutcome checkOwner(Connection connection, String id, UUID requester) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_OWNER)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return ClaimActionOutcome.CLAIM_NOT_FOUND;
                }
                UUID owner = UUID.fromString(resultSet.getString("owner_uuid"));
                return owner.equals(requester) ? ClaimActionOutcome.SUCCESS : ClaimActionOutcome.NOT_OWNER;
            }
        }
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
}
