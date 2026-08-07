package be.lloyd.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for {@code player_profiles}. Pure JDBC, no Bukkit/Paper types,
 * so it can be unit tested without a server.
 */
public final class PlayerProfileRepository {

    private static final String SELECT_BY_UUID =
            "SELECT uuid, last_name, created_at, updated_at FROM player_profiles WHERE uuid = ?";
    private static final String INSERT =
            "INSERT INTO player_profiles (uuid, last_name, created_at, updated_at) VALUES (?, ?, ?, ?)";
    private static final String UPDATE_NAME =
            "UPDATE player_profiles SET last_name = ?, updated_at = ? WHERE uuid = ?";

    private final DatabaseManager database;

    public PlayerProfileRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Optional<PlayerProfile>> find(UUID uuid) {
        return database.execute(connection -> findSync(connection, uuid));
    }

    /**
     * Creates the profile on first sight of a player, or refreshes
     * {@code last_name}/{@code updated_at} when the name changed since the
     * last visit. Returns the resulting profile either way.
     */
    public CompletableFuture<PlayerProfile> findOrCreate(UUID uuid, String currentName) {
        return database.execute(connection -> {
            Optional<PlayerProfile> existing = findSync(connection, uuid);
            Instant now = Instant.now();

            if (existing.isEmpty()) {
                try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, currentName);
                    statement.setString(3, now.toString());
                    statement.setString(4, now.toString());
                    statement.executeUpdate();
                }
                return new PlayerProfile(uuid, currentName, now, now);
            }

            PlayerProfile profile = existing.get();
            if (profile.lastName().equals(currentName)) {
                return profile;
            }

            try (PreparedStatement statement = connection.prepareStatement(UPDATE_NAME)) {
                statement.setString(1, currentName);
                statement.setString(2, now.toString());
                statement.setString(3, uuid.toString());
                statement.executeUpdate();
            }
            return new PlayerProfile(uuid, currentName, profile.createdAt(), now);
        });
    }

    private Optional<PlayerProfile> findSync(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_UUID)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private PlayerProfile map(ResultSet resultSet) throws SQLException {
        return new PlayerProfile(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("last_name"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")));
    }
}
