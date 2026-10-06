package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.quest.model.QuestState;
import java.sql.Connection;
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
    private final SqlDialect dialect;

    public QuestProgressRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
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
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT_STATE))) {
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
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT_OBJECTIVE_PROGRESS))) {
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

    /**
     * Enregistre une complétion de quête <strong>et</strong> les récompenses monétaires qu'elle
     * fait naître, dans une <strong>seule transaction</strong> (issue #16, second lot).
     *
     * <p>Cette méthode existe pour fermer une fenêtre précise, et c'est sa seule raison d'être.
     * Avec deux transactions séparées, un arrêt brutal entre les deux laisse l'un des deux pires
     * états :</p>
     * <ul>
     *   <li>état persisté d'abord : la quête est terminée et <strong>aucune dette n'existe</strong>
     *       — la récompense est perdue en silence, et une quête non répétable ne sera jamais
     *       rejouée ;</li>
     *   <li>dettes d'abord : des dettes existent pour une quête <strong>pas terminée</strong> — le
     *       joueur la refait, une seconde dette naît, et il est payé deux fois.</li>
     * </ul>
     *
     * <p>Les deux faits sont donc écrits ensemble, ou pas du tout. L'occurrence est calculée
     * <strong>dans</strong> la transaction et partagée par toutes les récompenses de cette
     * complétion ; chacune reçoit son propre {@code reward_index}, sans quoi plusieurs récompenses
     * monétaires d'une même quête partageraient une identité de paiement et toutes sauf la première
     * seraient avalées comme « déjà payées ».</p>
     *
     * <p>Le SQL des dettes vit dans {@link WalletRepository} (seul propriétaire des tables d'argent)
     * et est appelé ici via deux méthodes package-private : une seule définition de chaque requête,
     * une seule transaction.</p>
     *
     * @param completionToken identifiant stable de CETTE complétion, qui devient la racine de
     *                        l'identité de paiement ({@code <jeton>#<index>})
     * @param amounts         montants des récompenses monétaires, dans l'ordre de la quête
     * @return les identifiants de paiement créés, dans le même ordre
     */
    public CompletableFuture<List<String>> completeQuestWithMoneyDebts(
            UUID playerUuid, NamespacedKey questId, String completionToken, List<Long> amounts) {
        if (completionToken == null || completionToken.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "« completionToken » est obligatoire : c'est la racine de l'identité de paiement."));
        }
        List<Long> owed = List.copyOf(amounts);
        return database.execute(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                writeState(connection, playerUuid, questId, QuestState.COMPLETED, null);
                List<String> grantIds = new ArrayList<>();
                if (!owed.isEmpty()) {
                    int occurrence = WalletRepository.nextQuestRewardOccurrence(
                            connection, playerUuid, questId.toString());
                    for (int index = 0; index < owed.size(); index++) {
                        String grantId = completionToken + "#" + index;
                        WalletRepository.insertQuestRewardDebt(connection, dialect, grantId, playerUuid,
                                questId.toString(), occurrence, index, owed.get(index));
                        grantIds.add(grantId);
                    }
                }
                connection.commit();
                return grantIds;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    private void writeState(Connection connection, UUID playerUuid, NamespacedKey questId,
                            QuestState state, String currentStepId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT_STATE))) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, questId.toString());
            statement.setString(3, state.name());
            statement.setString(4, currentStepId);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
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
