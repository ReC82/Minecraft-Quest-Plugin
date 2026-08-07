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

    private static final int CURRENT_VERSION = 6;

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
        if (version < 3) {
            applyV3(connection);
            version = 3;
        }
        if (version < 4) {
            applyV4(connection);
            version = 4;
        }
        if (version < 5) {
            applyV5(connection);
            version = 5;
        }
        if (version < 6) {
            applyV6(connection);
            version = 6;
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

    private static void applyV3(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Positions par joueur inutile ici : un nœud appartient au monde, pas à un joueur.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resource_nodes (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        type_id TEXT NOT NULL,
                        depleted_at TEXT,
                        PRIMARY KEY (world, x, y, z)
                    )
                    """);
        }
    }

    private static void applyV4(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS wallets (
                        player_uuid TEXT PRIMARY KEY,
                        balance INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        context TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private static void applyV5(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // item_data : ItemStack#serializeAsBytes(), l'objet complet (méta, PDC d'un objet
            // personnalisé compris) plutôt qu'une référence recomposée à la remise.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS market_listings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        seller_uuid TEXT NOT NULL,
                        item_data BLOB NOT NULL,
                        price INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        resolved_at TEXT,
                        buyer_uuid TEXT,
                        FOREIGN KEY (seller_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_market_listings_status ON market_listings (status)
                    """);
        }
    }

    private static void applyV6(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Un cooldown de portail doit survivre à une reconnexion (mission étape 16) : persisté ici,
            // rechargé en mémoire à la connexion par travel.PortalService (jamais consulté en base
            // depuis PlayerMoveEvent, trop fréquent pour une requête asynchrone par événement).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS portal_cooldowns (
                        player_uuid TEXT NOT NULL,
                        portal_id TEXT NOT NULL,
                        expires_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, portal_id),
                        FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }
}
