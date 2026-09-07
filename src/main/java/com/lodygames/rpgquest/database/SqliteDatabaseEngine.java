package com.lodygames.rpgquest.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Moteur SQLite — <strong>comportement strictement identique</strong> à ce que faisait
 * {@link DatabaseManager} avant l'issue #40 : un fichier {@code data.db} dans le dossier du
 * plugin, {@code PRAGMA foreign_keys = ON}, version de schéma via {@code PRAGMA user_version},
 * une <strong>connexion unique</strong> réutilisée. Aucune dépendance ni configuration
 * supplémentaire — SQLite reste le mode « installation simple » et le mode des tests.
 */
public final class SqliteDatabaseEngine implements DatabaseEngine {

    private final Path databaseFile;
    private final SqlDialect dialect = new SqliteDialect();
    private final SchemaHistory schemaHistory = new PragmaUserVersionHistory();
    private volatile Connection connection;

    public SqliteDatabaseEngine(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.SQLITE;
    }

    @Override
    public String describe() {
        return "sqlite (" + databaseFile.getFileName() + ")";
    }

    @Override
    public void start() throws SQLException {
        Path absolute = databaseFile.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SQLException("Impossible de créer le dossier de la base SQLite : " + parent, e);
            }
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    @Override
    public Connection borrow() throws SQLException {
        if (connection == null) {
            throw new SQLException("Moteur SQLite non démarré.");
        }
        return connection;
    }

    @Override
    public void release(Connection connection) {
        // connexion unique : rien à rendre
    }

    @Override
    public SqlDialect dialect() {
        return dialect;
    }

    @Override
    public SchemaHistory schemaHistory() {
        return schemaHistory;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // best effort à l'arrêt
            }
            connection = null;
        }
    }
}
