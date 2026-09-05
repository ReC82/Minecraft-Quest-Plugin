package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.progression.model.SkillType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for the {@code player_skills}/{@code xp_grants} tables. Pure
 * JDBC, no Bukkit/Paper types. Même conception que {@code WalletRepository}
 * : chaque octroi est une seule transaction JDBC explicite (déduplication +
 * mise à jour du total, ou aucune des deux) — la même action de jeu ne peut
 * jamais récompenser une compétence deux fois (mission étape 19, point 5),
 * et un plantage à mi-opération ne peut jamais laisser un octroi enregistré
 * sans que le total corresponde (ou l'inverse).
 */
public final class ProgressionRepository {

    private static final String SELECT_ALL_SKILLS = "SELECT skill, total_xp FROM player_skills WHERE player_uuid = ?";
    private static final String SELECT_TOTAL_XP = "SELECT total_xp FROM player_skills WHERE player_uuid = ? AND skill = ?";
    private static final String ENSURE_SKILL_ROW =
            "INSERT OR IGNORE INTO player_skills (player_uuid, skill, total_xp, updated_at) VALUES (?, ?, 0, ?)";
    private static final String UPDATE_TOTAL_XP =
            "UPDATE player_skills SET total_xp = ?, updated_at = ? WHERE player_uuid = ? AND skill = ?";
    private static final String INSERT_GRANT =
            "INSERT OR IGNORE INTO xp_grants (player_uuid, skill, event_id, amount, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SELECT_TOP_PLAYERS =
            "SELECT p.last_name AS name, s.total_xp AS total_xp "
                    + "FROM player_skills s JOIN player_profiles p ON p.uuid = s.player_uuid "
                    + "WHERE s.skill = ? AND s.total_xp > 0 "
                    + "ORDER BY s.total_xp DESC LIMIT ?";

    private final DatabaseManager database;

    public ProgressionRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Toute l'XP totale connue d'un joueur par compétence (absente = 0, jamais interrogée en base pour une compétence inconnue). */
    public CompletableFuture<Map<SkillType, Long>> findAll(UUID uuid) {
        return database.execute(connection -> {
            Map<SkillType, Long> result = new EnumMap<>(SkillType.class);
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL_SKILLS)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        try {
                            SkillType skill = SkillType.valueOf(resultSet.getString("skill"));
                            result.put(skill, resultSet.getLong("total_xp"));
                        } catch (IllegalArgumentException e) {
                            // Compétence inconnue (retirée d'une version future) : ignorée, jamais fatale au chargement.
                        }
                    }
                }
            }
            return result;
        });
    }

    /**
     * Tente d'accorder {@code amount} XP à {@code skill} pour l'événement {@code eventId}. Si ce
     * (joueur, compétence, événement) a déjà été récompensé, ne touche à rien et retourne {@code
     * Optional.empty()}. Sinon, ajoute {@code amount} au total actuel, plafonné à {@code
     * maxTotalXp} (jamais de dépassement de capacité), et retourne le nouveau total.
     */
    public CompletableFuture<GrantOutcome> grantXp(UUID uuid, SkillType skill, long amount, String reason,
                                                     String eventId, long maxTotalXp) {
        return database.execute(connection -> inTransaction(connection, () -> {
            String now = Instant.now().toString();

            int inserted;
            try (PreparedStatement statement = connection.prepareStatement(INSERT_GRANT)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, skill.name());
                statement.setString(3, eventId);
                statement.setLong(4, amount);
                statement.setString(5, reason);
                statement.setString(6, now);
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) {
                return GrantOutcome.duplicate();
            }

            try (PreparedStatement ensure = connection.prepareStatement(ENSURE_SKILL_ROW)) {
                ensure.setString(1, uuid.toString());
                ensure.setString(2, skill.name());
                ensure.setString(3, now);
                ensure.executeUpdate();
            }

            long currentTotal = readTotalXp(connection, uuid, skill);
            long newTotal = Math.min(maxTotalXp, addClamped(currentTotal, amount));
            try (PreparedStatement update = connection.prepareStatement(UPDATE_TOTAL_XP)) {
                update.setLong(1, newTotal);
                update.setString(2, now);
                update.setString(3, uuid.toString());
                update.setString(4, skill.name());
                update.executeUpdate();
            }
            return GrantOutcome.granted(newTotal);
        }));
    }

    /**
     * Efface toute la progression RPG d'un joueur : ses lignes {@code player_skills} (totaux d'XP
     * par compétence) et {@code xp_grants} (journal de déduplication des octrois). Utilisé par le
     * reset admin « nouveau joueur » ({@code player.PlayerResetService}) — ne touche qu'à ce joueur,
     * jamais aux classements ni aux autres joueurs.
     */
    public CompletableFuture<Void> resetPlayer(UUID uuid) {
        return database.execute(connection -> inTransaction(connection, () -> {
            try (PreparedStatement grants = connection.prepareStatement("DELETE FROM xp_grants WHERE player_uuid = ?")) {
                grants.setString(1, uuid.toString());
                grants.executeUpdate();
            }
            try (PreparedStatement skills = connection.prepareStatement("DELETE FROM player_skills WHERE player_uuid = ?")) {
                skills.setString(1, uuid.toString());
                skills.executeUpdate();
            }
            return null;
        }));
    }

    /** Fixe l'XP totale d'une compétence à une valeur exacte (outil admin de test), jamais négative. */
    public CompletableFuture<Void> setTotalXp(UUID uuid, SkillType skill, long totalXp) {
        if (totalXp < 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("« totalXp » ne peut pas être négative : " + totalXp));
        }
        return database.execute(connection -> inTransaction(connection, () -> {
            String now = Instant.now().toString();
            try (PreparedStatement ensure = connection.prepareStatement(ENSURE_SKILL_ROW)) {
                ensure.setString(1, uuid.toString());
                ensure.setString(2, skill.name());
                ensure.setString(3, now);
                ensure.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(UPDATE_TOTAL_XP)) {
                update.setLong(1, totalXp);
                update.setString(2, now);
                update.setString(3, uuid.toString());
                update.setString(4, skill.name());
                update.executeUpdate();
            }
            return null;
        }));
    }

    /**
     * Classement en lecture seule des {@code limit} joueurs avec le plus d'XP totale sur
     * {@code skill} (nom d'affichage = dernier pseudo connu, ex-aequo à 0 exclus). Utilisé
     * uniquement par l'export web périodique (mission étape 21, point 4) — jamais interrogé
     * depuis un chemin de jeu synchrone.
     */
    public CompletableFuture<List<LeaderboardRow>> topPlayers(SkillType skill, int limit) {
        return database.execute(connection -> {
            List<LeaderboardRow> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_TOP_PLAYERS)) {
                statement.setString(1, skill.name());
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new LeaderboardRow(resultSet.getString("name"), resultSet.getLong("total_xp")));
                    }
                }
            }
            return rows;
        });
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

    private long readTotalXp(Connection connection, UUID uuid, SkillType skill) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_TOTAL_XP)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, skill.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong("total_xp") : 0L;
            }
        }
    }

    private long addClamped(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    public record LeaderboardRow(String name, long totalXp) {
    }

    public record GrantOutcome(boolean granted, long newTotalXp) {
        static GrantOutcome duplicate() {
            return new GrantOutcome(false, 0L);
        }

        static GrantOutcome granted(long newTotalXp) {
            return new GrantOutcome(true, newTotalXp);
        }
    }
}
