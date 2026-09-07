package com.lodygames.rpgquest.panel.audit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Journal d'audit persistant dans {@code control-panel.db} (SQLite) — base <strong>propre au
 * Control Panel</strong>, totalement séparée de {@code data.db} du plugin et de {@code store.db}
 * du module {@code web-api}. Table append-only ; aucune purge automatique en V1.
 */
public final class SqliteAuditLog implements AuditLog {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS audit_log (
                id TEXT PRIMARY KEY,
                ts TEXT NOT NULL,
                actor TEXT NOT NULL,
                action TEXT NOT NULL,
                target TEXT,
                result TEXT NOT NULL,
                details TEXT,
                request_id TEXT
            )
            """;
    private static final String INSERT =
            "INSERT INTO audit_log (id, ts, actor, action, target, result, details, request_id) VALUES (?,?,?,?,?,?,?,?)";
    private static final String RECENT =
            "SELECT id, ts, actor, action, target, result, details, request_id FROM audit_log ORDER BY ts DESC LIMIT ?";

    private final String jdbcUrl;

    public SqliteAuditLog(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser control-panel.db (" + dbPath + ")", e);
        }
    }

    @Override
    public void append(AuditEntry entry) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, entry.id());
            statement.setString(2, entry.ts().toString());
            statement.setString(3, entry.actor());
            statement.setString(4, entry.action());
            statement.setString(5, entry.target());
            statement.setString(6, entry.result());
            statement.setString(7, entry.details());
            statement.setString(8, entry.requestId());
            statement.executeUpdate();
        } catch (SQLException e) {
            // Ne jamais faire échouer une action métier parce que l'audit n'a pas pu écrire :
            // on trace sur stderr, l'appelant continue.
            System.getLogger(SqliteAuditLog.class.getName())
                    .log(System.Logger.Level.WARNING, "Échec d'écriture dans le journal d'audit", e);
        }
    }

    @Override
    public List<AuditEntry> recent(int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(RECENT)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    entries.add(new AuditEntry(
                            rs.getString("id"), Instant.parse(rs.getString("ts")), rs.getString("actor"),
                            rs.getString("action"), rs.getString("target"), rs.getString("result"),
                            rs.getString("details"), rs.getString("request_id")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture du journal d'audit impossible", e);
        }
        return entries;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
