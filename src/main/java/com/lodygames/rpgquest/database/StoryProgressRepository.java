package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.story.model.StoryState;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for {@code story_progress}. Pure JDBC, no Bukkit types. {@code current_index}
 * (migration V14) tracks the player's position in {@code StoryDefinition#questIds()} — 0 while
 * {@code NOT_STARTED}/at the first quest, meaningless once {@code COMPLETED} (kept at whatever
 * value it reached, never read back as a quest index once the state is {@code COMPLETED}).
 */
public final class StoryProgressRepository {

    public record StoryProgressRecord(StoryState state, int currentIndex) {
    }

    private static final String SELECT_ONE =
            "SELECT state, current_index FROM story_progress WHERE player_uuid = ? AND story_id = ?";
    private static final String SELECT_ALL =
            "SELECT story_id, state, current_index FROM story_progress WHERE player_uuid = ?";
    private static final String UPSERT_PROGRESS = """
            INSERT INTO story_progress (player_uuid, story_id, state, current_index, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid, story_id) DO UPDATE SET
                state = excluded.state, current_index = excluded.current_index, updated_at = excluded.updated_at
            """;
    private static final String DELETE_STORY =
            "DELETE FROM story_progress WHERE player_uuid = ? AND story_id = ?";
    private static final String DELETE_ALL_FOR_PLAYER =
            "DELETE FROM story_progress WHERE player_uuid = ?";

    private final DatabaseManager database;

    public StoryProgressRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Optional<StoryProgressRecord>> find(UUID playerUuid, String storyId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ONE)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, storyId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.<StoryProgressRecord>empty();
                }
            }
        });
    }

    public CompletableFuture<Map<String, StoryProgressRecord>> findAll(UUID playerUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, StoryProgressRecord> states = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        states.put(resultSet.getString("story_id"), map(resultSet));
                    }
                    return states;
                }
            }
        });
    }

    public CompletableFuture<Void> upsertProgress(UUID playerUuid, String storyId, StoryState state, int currentIndex) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_PROGRESS)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, storyId);
                statement.setString(3, state.name());
                statement.setInt(4, currentIndex);
                statement.setString(5, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Supprime intégralement l'état d'une story pour un joueur (pas de remise à zéro : la ligne
     * disparaît, ce qui équivaut à {@code NOT_STARTED}) — les autres stories du joueur ne sont
     * jamais affectées ({@code story_id} fait partie du filtre), tout comme l'inventaire,
     * l'économie et les quêtes (aucune de ces tables n'est touchée ici).
     */
    public CompletableFuture<Void> deleteStory(UUID playerUuid, String storyId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_STORY)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, storyId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Équivalent de {@link #deleteStory} pour toutes les stories d'un joueur en une fois ({@code reset ... all}). */
    public CompletableFuture<Void> deleteAllForPlayer(UUID playerUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ALL_FOR_PLAYER)) {
                statement.setString(1, playerUuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private StoryProgressRecord map(ResultSet resultSet) throws SQLException {
        return new StoryProgressRecord(StoryState.valueOf(resultSet.getString("state")), resultSet.getInt("current_index"));
    }
}
