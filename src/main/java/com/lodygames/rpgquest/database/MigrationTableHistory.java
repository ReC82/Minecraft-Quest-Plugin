package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * Historique de schéma dans une table de métadonnées portable {@code rpgquest_schema_migrations}
 * (issue #40) : une ligne par migration appliquée, avec horodatage. Convient à tout moteur JDBC
 * (utilisée par MySQL/MariaDB en #41 ; testée ici sur SQLite).
 *
 * <pre>
 *   rpgquest_schema_migrations(version INTEGER PRIMARY KEY, name TEXT, applied_at TEXT)
 * </pre>
 *
 * <p>La « version courante » est {@code MAX(version)} (0 si la table est vide). Le DDL de création
 * utilise des types acceptés à l'identique par SQLite et MySQL.</p>
 */
public final class MigrationTableHistory implements SchemaHistory {

    static final String TABLE = "rpgquest_schema_migrations";

    private static final String CREATE = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "version INTEGER NOT NULL PRIMARY KEY, "
            + "name VARCHAR(200) NOT NULL, "
            + "applied_at VARCHAR(40) NOT NULL)";

    @Override
    public void ensureInitialised(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE);
        }
    }

    @Override
    public int currentVersion(Connection connection) throws SQLException {
        ensureInitialised(connection);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM " + TABLE)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    @Override
    public void recordApplied(Connection connection, int version, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (version, name, applied_at) VALUES (?, ?, ?)")) {
            statement.setInt(1, version);
            statement.setString(2, name);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        }
    }
}
