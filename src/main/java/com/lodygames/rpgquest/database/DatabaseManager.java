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
 * Sérialise tous les accès à la base sur un thread de fond dédié. Toute opération publique est
 * asynchrone et ne doit jamais être appelée depuis — ni bloquer — le thread principal du serveur.
 *
 * <p>Le <strong>moteur</strong> (SQLite, MariaDB…) est un {@link DatabaseEngine} injecté : ce
 * gestionnaire n'ouvre plus d'URL JDBC en dur, n'exécute plus de {@code PRAGMA} spécifique et ne
 * choisit ni le dialecte ni le suivi de version. Chaque unité de travail
 * {@link #borrow() emprunte} une connexion au moteur et la {@link DatabaseEngine#release rend}
 * ensuite : pour SQLite c'est la connexion unique historique (comportement inchangé), pour MariaDB
 * une connexion du pool. L'executor étant mono-thread, une seule connexion est active à la fois —
 * l'ordre FIFO dont dépendent les repositories est préservé quel que soit le moteur.</p>
 *
 * <p>Le constructeur historique {@link #DatabaseManager(Path)} reste disponible et sélectionne
 * SQLite.</p>
 */
public final class DatabaseManager {

    private final DatabaseEngine engine;
    private final ExecutorService executor;
    private volatile boolean started;
    private volatile boolean closed;

    /** Construit un gestionnaire SQLite sur {@code databaseFile} (API historique). */
    public DatabaseManager(Path databaseFile) {
        this(new SqliteDatabaseEngine(databaseFile));
    }

    public DatabaseManager(DatabaseEngine engine) {
        this.engine = engine;
        this.executor = Executors.newSingleThreadExecutor(DatabaseManager::newDaemonThread);
    }

    /**
     * Démarre le moteur (ouverture / pool + contrôle de connectivité), puis applique les migrations
     * de schéma en attente. L'executor étant mono-thread et FIFO, tout {@link #execute} soumis
     * avant la fin de ce future est mis en file derrière lui : le schéma est prêt avant toute
     * requête. Le future échoue si la base est injoignable ou si une migration critique échoue —
     * l'appelant ne doit alors pas servir de gameplay.
     */
    public CompletableFuture<Void> initialize() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                engine.start();
                started = true;
                Connection connection = engine.borrow();
                try {
                    new SchemaMigrationRunner(SchemaMigrator.ALL, engine.schemaHistory(), engine.dialect())
                            .run(connection);
                } finally {
                    engine.release(connection);
                }
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Exécute {@code action} contre une connexion empruntée au moteur, sur le thread base de
     * données, et complète le future avec son résultat (ou son échec). La connexion est rendue au
     * moteur dès la fin de l'action (y compris en cas d'exception).
     */
    public <T> CompletableFuture<T> execute(SqlFunction<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            Connection connection = null;
            try {
                connection = engine.borrow();
                future.complete(action.apply(connection));
            } catch (SQLException e) {
                future.completeExceptionally(e);
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            } finally {
                if (connection != null) {
                    engine.release(connection);
                }
            }
        });
        return future;
    }

    /**
     * Vérifie la vie de la base par une requête triviale ({@link SqlDialect#healthQuery()}). Ne
     * lève jamais : renvoie {@code false} si la base est momentanément indisponible, non démarrée
     * ou fermée. Utilisée au démarrage et par les diagnostics.
     */
    public CompletableFuture<Boolean> healthCheck() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (!started || closed) {
                    future.complete(false);
                    return;
                }
                Connection connection = null;
                try {
                    connection = engine.borrow();
                    try (Statement statement = connection.createStatement();
                         ResultSet resultSet = statement.executeQuery(engine.dialect().healthQuery())) {
                        future.complete(resultSet.next());
                    }
                } catch (SQLException | RuntimeException e) {
                    future.complete(false);
                } finally {
                    if (connection != null) {
                        engine.release(connection);
                    }
                }
            });
        } catch (RuntimeException e) {
            future.complete(false); // executor déjà arrêté
        }
        return future;
    }

    /** Dialecte SQL du moteur actif — pour les repositories qui adaptent leur SQL canonique. */
    public SqlDialect dialect() {
        return engine.dialect();
    }

    /** Type de moteur actif. */
    public DatabaseType engineType() {
        return engine.type();
    }

    /** Description sûre du moteur actif (jamais de secret) — pour les logs et les diagnostics. */
    public String describeEngine() {
        return engine.describe();
    }

    /** Version de schéma attendue par ce build. */
    public int expectedSchemaVersion() {
        return SchemaMigrator.CURRENT_VERSION;
    }

    /**
     * Arrête le moteur (fermeture de la connexion / du pool) et le thread base de données, en
     * attendant brièvement la fin du travail en cours. Réservé à l'arrêt du plugin.
     */
    public void shutdown() {
        closed = true;
        executor.execute(engine::close);
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
        return closed || !started;
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
