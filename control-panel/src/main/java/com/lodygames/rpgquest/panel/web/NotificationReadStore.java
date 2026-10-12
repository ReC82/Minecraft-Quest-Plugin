package com.lodygames.rpgquest.panel.web;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Ce que chaque compte a lu dans le centre de notifications (issue #293).
 *
 * <p>Avant, le badge comptait tous les échecs des dernières 24 h, sans aucune notion de lecture :
 * il restait à 18 quoi qu'on ouvre. Une notification est désormais <strong>lue</strong> quand ce
 * compte ouvre sa fiche (le lien de la cloche y mène), ou par « Tout marquer comme lu ». Lire une
 * notification ne marque jamais les autres. Persisté par compte : survit à la navigation, à
 * l'actualisation et à la reconnexion. Les notifications elles-mêmes (table {@code agent_action})
 * ne sont jamais modifiées.</p>
 */
public final class NotificationReadStore {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS notification_read (
                user_id TEXT NOT NULL,
                action_id TEXT NOT NULL,
                read_at TEXT NOT NULL,
                PRIMARY KEY (user_id, action_id)
            )
            """;

    private final String jdbcUrl;

    public NotificationReadStore(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(CREATE);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser les lectures de notifications dans " + dbPath, e);
        }
    }

    /** Marque ces actions comme lues par ce compte. Idempotent. */
    public void markRead(String userId, Collection<String> actionIds) {
        if (userId == null || userId.isBlank() || actionIds.isEmpty()) {
            return;
        }
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO notification_read (user_id, action_id, read_at) VALUES (?, ?, ?)")) {
            String now = Instant.now().toString();
            for (String actionId : actionIds) {
                ps.setString(1, userId);
                ps.setString(2, actionId);
                ps.setString(3, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Enregistrement de la lecture impossible", e);
        }
    }

    /** Parmi ces actions, celles que ce compte a déjà lues. */
    public Set<String> readAmong(String userId, Collection<String> actionIds) {
        Set<String> read = new HashSet<>();
        if (userId == null || userId.isBlank() || actionIds.isEmpty()) {
            return read;
        }
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT action_id FROM notification_read WHERE user_id = ? AND action_id = ?")) {
            for (String actionId : actionIds) {
                ps.setString(1, userId);
                ps.setString(2, actionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        read.add(actionId);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des notifications lues impossible", e);
        }
        return read;
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }
}
