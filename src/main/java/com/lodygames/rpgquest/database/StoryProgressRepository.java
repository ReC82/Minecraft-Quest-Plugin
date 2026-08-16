package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.story.model.StoryState;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for {@code story_progress}. Pure JDBC, no Bukkit types beyond nothing at all — even
 * lighter than {@link QuestProgressRepository} (pas de sous-table d'objectifs : une Story n'a pour
 * l'instant qu'un état, pas de compteurs).
 */
public final class StoryProgressRepository {

    private static final String SELECT_ONE =
            "SELECT state FROM story_progress WHERE player_uuid = ? AND story_id = ?";
    private static final String SELECT_ALL =
            "SELECT story_id, state FROM story_progress WHERE player_uuid = ?";
    private static final String UPSERT_STATE = """
            INSERT INTO story_progress (player_uuid, story_id, state, updated_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (player_uuid, story_id) DO UPDATE SET state = excluded.state, updated_at = excluded.updated_at
            """;
    private static final String DELETE_STORY =
            "DELETE FROM story_progress WHERE player_uuid = ? AND story_id = ?";
    private static final String DELETE_ALL_FOR_PLAYER =
            "DELETE FROM story_progress WHERE player_uuid = ?";

    private final DatabaseManager database;

    public StoryProgressRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Optional<StoryState>> find(UUID playerUuid, String storyId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ONE)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, storyId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(StoryState.valueOf(resultSet.getString("state")))
                            : Optional.<StoryState>empty();
                }
            }
        });
    }

    public CompletableFuture<Map<String, StoryState>> findAll(UUID playerUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, StoryState> states = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        states.put(resultSet.getString("story_id"), StoryState.valueOf(resultSet.getString("state")));
                    }
                    return states;
                }
            }
        });
    }

    public CompletableFuture<Void> upsertState(UUID playerUuid, String storyId, StoryState state) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_STATE)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, storyId);
                statement.setString(3, state.name());
                statement.setString(4, Instant.now().toString());
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
}
