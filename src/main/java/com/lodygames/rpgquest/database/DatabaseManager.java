package com.lodygames.rpgquest.database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Possède l'unique {@link Connection} JDBC et sérialise tous les accès sur un thread de fond dédié.
 * Toute opération publique est asynchrone et ne doit jamais être appelée depuis — ni bloquer — le
 * thread principal du serveur.
 *
 * <p>Depuis l'issue #40, le <strong>moteur</strong> (SQLite, MySQL…) est un {@link DatabaseEngine}
 * injecté : ce gestionnaire n'ouvre plus d'URL JDBC en dur, n'exécute plus de {@code PRAGMA}
 * spécifique et ne choisit plus le dialecte ni le suivi de version. Le constructeur historique
 * {@link #DatabaseManager(Path)} reste disponible et sélectionne SQLite — comportement inchangé.</p>
 */
public final class DatabaseManager {

    private final DatabaseEngine engine;
    private final ExecutorService executor;
    private volatile Connection connection;

    /** Construit un gestionnaire SQLite sur {@code databaseFile} (API historique). */
    public DatabaseManager(Path databaseFile) {
        this(new SqliteDatabaseEngine(databaseFile));
    }

    public DatabaseManager(DatabaseEngine engine) {
        this.engine = engine;
        this.executor = Executors.newSingleThreadExecutor(DatabaseManager::newDaemonThread);
    }

    /**
     * Ouvre la connexion (via le moteur), applique les réglages de session puis les migrations de
     * schéma en attente. L'executor étant mono-thread et FIFO, tout {@link #execute} soumis avant
     * la fin de ce future est mis en file derrière lui : le schéma est prêt avant toute requête.
     */
    public CompletableFuture<Void> initialize() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                connection = engine.openConnection();
                engine.configureSession(connection);
                new SchemaMigrationRunner(SchemaMigrator.ALL, engine.schemaHistory(), engine.dialect())
                        .run(connection);
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Exécute {@code action} contre la connexion sur le thread base de données et complète le
     * future avec son résultat (ou son échec).
     */
    public <T> CompletableFuture<T> execute(SqlFunction<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(action.apply(connection));
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Vérifie la vie de la connexion par une requête triviale ({@link SqlDialect#healthQuery()}).
     * Ne lève jamais : renvoie {@code false} si la base est momentanément indisponible ou fermée.
     * Utile au démarrage et pour un futur health check (Control Panel, diagnostics).
     */
    public CompletableFuture<Boolean> healthCheck() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        executor.execute(() -> {
            if (connection == null) {
                future.complete(false);
                return;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(engine.dialect().healthQuery())) {
                future.complete(resultSet.next());
            } catch (SQLException e) {
                future.complete(false);
            }
        });
        return future;
    }

    /** Dialecte SQL du moteur actif — pour les repositories qui construisent du SQL portable. */
    public SqlDialect dialect() {
        return engine.dialect();
    }

    /** Type de moteur actif. */
    public DatabaseType engineType() {
        return engine.type();
    }

    /** Description sûre du moteur actif (jamais de secret) — pour les logs. */
    public String describeEngine() {
        return engine.describe();
    }

    /**
     * Ferme la connexion et arrête le thread base de données, en attendant brièvement la fin du
     * travail en cours. Réservé à l'arrêt du plugin.
     */
    public void shutdown() {
        executor.execute(() -> {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // best effort close during shutdown
                }
            }
            engine.close();
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public boolean isClosed() {
        try {
            return connection == null || connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    private static Thread newDaemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "RPGQuest-Database");
        thread.setDaemon(true);
        return thread;
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}
