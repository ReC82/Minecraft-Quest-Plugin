package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.quest.model.QuestState;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.NamespacedKey;

/**
 * Repository for {@code quest_progress} (état + étape courante) and {@code
 * quest_objective_progress} (compteurs par objectif). Pure JDBC, requêtes
 * préparées uniquement, aucun type Bukkit/Paper au-delà de {@link
 * NamespacedKey} (léger, pas de dépendance à un serveur vivant).
 */
public final class QuestProgressRepository {

    private static final String SELECT_ONE =
            "SELECT quest_id, state, progress_data, updated_at FROM quest_progress WHERE player_uuid = ? AND quest_id = ?";
    private static final String SELECT_ALL =
            "SELECT quest_id, state, progress_data, updated_at FROM quest_progress WHERE player_uuid = ?";
    private static final String UPSERT_STATE = """
            INSERT INTO quest_progress (player_uuid, quest_id, state, progress_data, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid, quest_id) DO UPDATE SET state = excluded.state,
                progress_data = excluded.progress_data, updated_at = excluded.updated_at
            """;
    private static final String SELECT_OBJECTIVE_PROGRESS =
            "SELECT objective_index, progress FROM quest_objective_progress WHERE player_uuid = ? AND quest_id = ? AND step_id = ?";
    private static final String UPSERT_OBJECTIVE_PROGRESS = """
            INSERT INTO quest_objective_progress (player_uuid, quest_id, step_id, objective_index, progress) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid, quest_id, step_id, objective_index) DO UPDATE SET progress = excluded.progress
            """;
    private static final String DELETE_STATE =
            "DELETE FROM quest_progress WHERE player_uuid = ? AND quest_id = ?";
    private static final String DELETE_OBJECTIVE_PROGRESS =
            "DELETE FROM quest_objective_progress WHERE player_uuid = ? AND quest_id = ?";
    private static final String DELETE_ALL_STATE =
            "DELETE FROM quest_progress WHERE player_uuid = ?";
    private static final String DELETE_ALL_OBJECTIVE_PROGRESS =
            "DELETE FROM quest_objective_progress WHERE player_uuid = ?";

    private final DatabaseManager database;

    public QuestProgressRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<Optional<QuestProgressRecord>> find(UUID playerUuid, NamespacedKey questId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ONE)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, questId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(playerUuid, resultSet)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<QuestProgressRecord>> findAll(UUID playerUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<QuestProgressRecord> records = new ArrayList<>();
                    while (resultSet.next()) {
                        records.add(map(playerUuid, resultSet));
                    }
                    return records;
                }
            }
        });
    }

    public CompletableFuture<Void> upsertState(UUID playerUuid, NamespacedKey questId, QuestState state, String currentStepId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_STATE)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, questId.toString());
                statement.setString(3, state.name());
                statement.setString(4, currentStepId);
                statement.setString(5, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Map<Integer, Integer>> findObjectiveProgress(UUID playerUuid, NamespacedKey questId, String stepId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_OBJECTIVE_PROGRESS)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, questId.toString());
                statement.setString(3, stepId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<Integer, Integer> progress = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        progress.put(resultSet.getInt("objective_index"), resultSet.getInt("progress"));
                    }
                    return progress;
                }
            }
        });
    }

    public CompletableFuture<Void> setObjectiveProgress(
            UUID playerUuid, NamespacedKey questId, String stepId, int objectiveIndex, int progress) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_OBJECTIVE_PROGRESS)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, questId.toString());
                statement.setString(3, stepId);
                statement.setInt(4, objectiveIndex);
                statement.setInt(5, progress);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Supprime intégralement l'état et les compteurs d'objectifs d'une quête pour un joueur (pas de
     * remise à zéro : la ligne disparaît, ce qui équivaut à {@code NOT_STARTED} — voir
     * {@link com.lodygames.rpgquest.quest.progress.QuestProgressEngine#resetQuest}). Les autres
     * quêtes du joueur ne sont jamais affectées ({@code quest_id} fait partie du filtre des deux
     * requêtes).
     */
    public CompletableFuture<Void> deleteQuest(UUID playerUuid, NamespacedKey questId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_STATE)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, questId.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(DELETE_OBJECTIVE_PROGRESS)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, questId.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Équivalent de {@link #deleteQuest} pour toutes les quêtes d'un joueur en une fois. */
    public CompletableFuture<Void> deleteAllForPlayer(UUID playerUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ALL_STATE)) {
                statement.setString(1, playerUuid.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ALL_OBJECTIVE_PROGRESS)) {
                statement.setString(1, playerUuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private QuestProgressRecord map(UUID playerUuid, ResultSet resultSet) throws SQLException {
        return new QuestProgressRecord(
                playerUuid,
                NamespacedKey.fromString(resultSet.getString("quest_id")),
                QuestState.valueOf(resultSet.getString("state")),
                resultSet.getString("progress_data"),
                Instant.parse(resultSet.getString("updated_at")));
    }
}
