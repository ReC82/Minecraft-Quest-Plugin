package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Table {@code periodic_quest_assignments} (issue #15, schéma V42) : quelles quêtes quotidiennes ou
 * hebdomadaires ont été attribuées à un joueur, pour quelle période, et où en est l'attribution.
 *
 * <p>Toutes les écritures sont idempotentes : {@link #assign} n'insère que les emplacements encore
 * libres ({@code INSERT OR IGNORE} sur la clé primaire) et relit ce qui est réellement en base ;
 * {@link #markStarted} et {@link #close} sont des {@code UPDATE} conditionnels sur le statut
 * précédent, dont le nombre de lignes dit qui a gagné. Aucune récompense n'est écrite ici.</p>
 */
public final class PeriodicQuestAssignmentRepository {

    /** Quête choisie pour la période, pas encore démarrée (crash entre les deux, joueur parti…). */
    public static final String ASSIGNED = "ASSIGNED";
    /** Quête démarrée dans le moteur de quêtes pour cette période. */
    public static final String STARTED = "STARTED";
    /** Période échue : la quête a expiré si elle n'était pas finie. Plus jamais rouverte. */
    public static final String CLOSED = "CLOSED";

    private static final String COLUMNS = "period_type, period_key, slot, quest_id, status, assigned_at";
    private static final String INSERT = "INSERT OR IGNORE INTO periodic_quest_assignments "
            + "(player_uuid, period_type, period_key, slot, quest_id, status, assigned_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_PERIOD = "SELECT " + COLUMNS + " FROM periodic_quest_assignments "
            + "WHERE player_uuid = ? AND period_type = ? AND period_key = ? ORDER BY slot";
    private static final String SELECT_OPEN = "SELECT " + COLUMNS + " FROM periodic_quest_assignments "
            + "WHERE player_uuid = ? AND status <> 'CLOSED' ORDER BY period_type, period_key, slot";
    private static final String SELECT_PREVIOUS_KEY = "SELECT MAX(period_key) FROM periodic_quest_assignments "
            + "WHERE player_uuid = ? AND period_type = ? AND period_key < ?";
    private static final String UPDATE_STATUS = "UPDATE periodic_quest_assignments SET status = ?, updated_at = ? "
            + "WHERE player_uuid = ? AND period_type = ? AND period_key = ? AND slot = ? AND status = ?";

    /** Une quête attribuée pour une période. */
    public record Assignment(String periodType, String periodKey, int slot, String questId, String status,
                             Instant assignedAt) {
    }

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public PeriodicQuestAssignmentRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    /**
     * Attribue {@code questIds} (emplacements 0, 1, 2…) pour la période, sans rien écraser, puis rend
     * l'attribution réellement en base — celle d'un appel concurrent arrivé avant, le cas échéant.
     */
    public CompletableFuture<List<Assignment>> assign(UUID playerUuid, String periodType, String periodKey,
                                                      List<String> questIds) {
        List<String> ids = List.copyOf(questIds);
        return database.execute(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String now = Instant.now().toString();
                try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(INSERT))) {
                    for (int slot = 0; slot < ids.size(); slot++) {
                        statement.setString(1, playerUuid.toString());
                        statement.setString(2, periodType);
                        statement.setString(3, periodKey);
                        statement.setInt(4, slot);
                        statement.setString(5, ids.get(slot));
                        statement.setString(6, ASSIGNED);
                        statement.setString(7, now);
                        statement.setString(8, now);
                        statement.executeUpdate();
                    }
                }
                List<Assignment> stored = period(connection, playerUuid, periodType, periodKey);
                connection.commit();
                return stored;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    /** Attribution d'une période (vide si aucune). */
    public CompletableFuture<List<Assignment>> findPeriod(UUID playerUuid, String periodType, String periodKey) {
        return database.execute(connection -> period(connection, playerUuid, periodType, periodKey));
    }

    /** Attributions non closes, toutes périodes confondues (expiration à la connexion / au reset). */
    public CompletableFuture<List<Assignment>> findOpen(UUID playerUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_OPEN)) {
                statement.setString(1, playerUuid.toString());
                return read(statement);
            }
        });
    }

    /** Quêtes de la dernière période attribuée avant {@code periodKey} (anti-répétition). */
    public CompletableFuture<List<String>> previousQuestIds(UUID playerUuid, String periodType, String periodKey) {
        return database.execute(connection -> {
            String previousKey;
            try (PreparedStatement statement = connection.prepareStatement(SELECT_PREVIOUS_KEY)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, periodType);
                statement.setString(3, periodKey);
                try (ResultSet resultSet = statement.executeQuery()) {
                    previousKey = resultSet.next() ? resultSet.getString(1) : null;
                }
            }
            if (previousKey == null) {
                return List.of();
            }
            return period(connection, playerUuid, periodType, previousKey).stream().map(Assignment::questId).toList();
        });
    }

    /** {@code ASSIGNED → STARTED}. {@code true} seulement pour l'appel qui a fait la transition. */
    public CompletableFuture<Boolean> markStarted(UUID playerUuid, Assignment assignment) {
        return transition(playerUuid, assignment, ASSIGNED, STARTED);
    }

    /** {@code fromStatus → CLOSED}. {@code true} seulement pour l'appel qui a fait la transition. */
    public CompletableFuture<Boolean> close(UUID playerUuid, Assignment assignment) {
        return transition(playerUuid, assignment, assignment.status(), CLOSED);
    }

    private CompletableFuture<Boolean> transition(UUID playerUuid, Assignment assignment, String from, String to) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_STATUS)) {
                statement.setString(1, to);
                statement.setString(2, Instant.now().toString());
                statement.setString(3, playerUuid.toString());
                statement.setString(4, assignment.periodType());
                statement.setString(5, assignment.periodKey());
                statement.setInt(6, assignment.slot());
                statement.setString(7, from);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static List<Assignment> period(Connection connection, UUID playerUuid, String periodType,
                                           String periodKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_PERIOD)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, periodType);
            statement.setString(3, periodKey);
            return read(statement);
        }
    }

    private static List<Assignment> read(PreparedStatement statement) throws SQLException {
        List<Assignment> result = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                result.add(new Assignment(resultSet.getString("period_type"), resultSet.getString("period_key"),
                        resultSet.getInt("slot"), resultSet.getString("quest_id"), resultSet.getString("status"),
                        Instant.parse(resultSet.getString("assigned_at"))));
            }
        }
        return result;
    }
}
