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
 * Table {@code player_heart_grants} (issue #166, schéma V36) : les droits aux cœurs de vie
 * maximum déjà accordés, un par joueur et par clé stable.
 *
 * <p>Seule source de vérité du bonus : le total appliqué en jeu est la somme de ces lignes,
 * relue à la connexion. L'octroi lui-même est écrit <strong>dans la transaction de
 * complétion</strong> de la quête (voir {@link QuestProgressRepository#completeQuest}) via
 * {@link #insertGrant}, pour qu'une quête terminée ne puisse ni perdre son cœur ni le donner
 * deux fois.</p>
 */
public final class HeartGrantRepository {

    static final String INSERT_GRANT =
            "INSERT OR IGNORE INTO player_heart_grants (player_uuid, grant_key, hearts, quest_id, granted_at) "
                    + "VALUES (?, ?, ?, ?, ?)";
    private static final String TOTAL =
            "SELECT COALESCE(SUM(hearts), 0) FROM player_heart_grants WHERE player_uuid = ?";
    private static final String SELECT_ALL =
            "SELECT grant_key, hearts, quest_id, granted_at FROM player_heart_grants WHERE player_uuid = ? "
                    + "ORDER BY granted_at, grant_key";

    /** Un droit déjà accordé. */
    public record HeartGrant(String grantKey, int hearts, String questId, Instant grantedAt) {
    }

    private final DatabaseManager database;

    public HeartGrantRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Total des cœurs accordés à ce joueur (0 s'il n'en a aucun). */
    public CompletableFuture<Integer> totalHearts(UUID playerUuid) {
        return database.execute(connection -> totalHearts(connection, playerUuid));
    }

    /** {@link #totalHearts(UUID)} dans la connexion (et la transaction) de l'appelant. */
    static int totalHearts(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TOTAL)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    /** Droits accordés à ce joueur, du plus ancien au plus récent. */
    public CompletableFuture<List<HeartGrant>> grants(UUID playerUuid) {
        return database.execute(connection -> {
            List<HeartGrant> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(new HeartGrant(resultSet.getString("grant_key"), resultSet.getInt("hearts"),
                                resultSet.getString("quest_id"), Instant.parse(resultSet.getString("granted_at"))));
                    }
                }
            }
            return result;
        });
    }

    /**
     * Enregistre un droit dans la transaction de l'appelant.
     *
     * @return {@code true} si le droit vient d'être accordé, {@code false} s'il l'était déjà
     */
    static boolean insertGrant(Connection connection, SqlDialect dialect, UUID playerUuid, String grantKey,
                               int hearts, String questId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(INSERT_GRANT))) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, grantKey);
            statement.setInt(3, hearts);
            statement.setString(4, questId);
            statement.setString(5, Instant.now().toString());
            return statement.executeUpdate() > 0;
        }
    }
}
