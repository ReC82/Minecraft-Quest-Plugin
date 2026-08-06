package be.lloyd.rpgquest.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies schema migrations in order, tracked via SQLite's {@code PRAGMA
 * user_version}. Running {@link #migrate(Connection)} on an already
 * up-to-date database is a no-op.
 */
public final class SchemaMigrator {

    private static final int CURRENT_VERSION = 2;

    private SchemaMigrator() {
    }

    public static void migrate(Connection connection) throws SQLException {
        int startingVersion = currentVersion(connection);
        int version = startingVersion;

        if (version < 1) {
            applyV1(connection);
            version = 1;
        }
        if (version < 2) {
            applyV2(connection);
            version = 2;
        }

        if (version != startingVersion) {
            setVersion(connection, version);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static void setVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        }
    }

    private static void applyV1(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_profiles (
                        uuid TEXT PRIMARY KEY,
                        last_name TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_variables (
                        player_uuid TEXT NOT NULL,
                        variable_key TEXT NOT NULL,
                        variable_value TEXT,
                        PRIMARY KEY (player_uuid, variable_key),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);

            // Préparée pour une étape ultérieure : non exploitée pour l'instant.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS quest_progress (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        progress_data TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, quest_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private static void applyV2(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS quest_objective_progress (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        step_id TEXT NOT NULL,
                        objective_index INTEGER NOT NULL,
                        progress INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, quest_id, step_id, objective_index),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }
}
