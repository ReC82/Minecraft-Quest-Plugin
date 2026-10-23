package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.player.PublicProfilePolicy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Statistiques de quêtes pour le portail web (issue #54), en <strong>lecture seule</strong>.
 *
 * <p>Source de vérité : {@code quest_progress}, la table que le moteur de quêtes écrit déjà. Aucun
 * compteur parallèle n'est tenu — une seconde source divergerait un jour de la première. Portée
 * {@code lifetime} : une quête terminée compte une fois, quelle que soit sa date.</p>
 */
public final class QuestStatisticsRepository {

    private final DatabaseManager database;

    public QuestStatisticsRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Les joueurs au profil web visible (#55) ayant terminé le plus de quêtes. */
    public CompletableFuture<List<Row>> topCompleted(int limit, PublicProfilePolicy.Visibility visibility) {
        return topCompleted(limit, visibility, List.of());
    }

    /** Même classement, sans les joueurs {@code excluded} (modération, {@code statistics.yml}). */
    public CompletableFuture<List<Row>> topCompleted(int limit, PublicProfilePolicy.Visibility visibility,
                                                     Collection<UUID> excluded) {
        String sql = "SELECT p.last_name AS name, COUNT(*) AS completed "
                + "FROM quest_progress q JOIN player_profiles p ON p.uuid = q.player_uuid "
                + "WHERE q.state = 'COMPLETED' AND " + PublicProfilePolicy.visible("p", visibility)
                + StatisticsRepository.notIn("p.uuid", excluded.size()) + " "
                + "GROUP BY p.uuid, p.last_name ORDER BY completed DESC, p.last_name ASC, p.uuid ASC LIMIT ?";
        return database.execute(connection -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = StatisticsRepository.bindUuids(statement, 1, excluded);
                statement.setInt(index, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new Row(resultSet.getString("name"), resultSet.getLong("completed")));
                    }
                }
            }
            return rows;
        });
    }

    /**
     * Quêtes terminées par un joueur, son rang et l'effectif (issue #53) — tous profils confondus,
     * sans les {@code excluded}. Vide s'il n'en a terminé aucune.
     */
    public CompletableFuture<java.util.Optional<StatisticsRepository.Standing>> standing(UUID player,
                                                                                          Collection<UUID> excluded) {
        String sql = "WITH t AS (SELECT q.player_uuid AS u, COUNT(*) AS v FROM quest_progress q "
                + "WHERE q.state = 'COMPLETED'" + StatisticsRepository.notIn("q.player_uuid", excluded.size())
                + " GROUP BY q.player_uuid) SELECT m.v AS mine, (SELECT COUNT(*) FROM t) AS players, "
                + "(SELECT COUNT(*) FROM t o WHERE o.v > m.v) AS better FROM t m WHERE m.u = ?";
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = StatisticsRepository.bindUuids(statement, 1, excluded);
                statement.setString(index, player.toString());
                return StatisticsRepository.standing(statement);
            }
        });
    }

    public record Row(String name, long completed) {
    }
}
