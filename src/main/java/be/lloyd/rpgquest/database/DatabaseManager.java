package be.lloyd.rpgquest.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns the single JDBC connection to the SQLite database and serializes all
 * access to it on a dedicated background thread. Every public operation is
 * asynchronous and must never be called from, nor block, the server main
 * thread.
 */
public final class DatabaseManager {

    private final Path databaseFile;
    private final ExecutorService executor;
    private volatile Connection connection;

    public DatabaseManager(Path databaseFile) {
        this.databaseFile = databaseFile;
        this.executor = Executors.newSingleThreadExecutor(DatabaseManager::newDaemonThread);
    }

    /**
     * Opens the connection and applies pending schema migrations.
     * Because the executor is single-threaded and FIFO, any {@link #execute}
     * call submitted before this future completes is queued behind it and
     * will only run once the schema is ready — no explicit wait is needed.
     */
    public CompletableFuture<Void> initialize() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                Path parent = databaseFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                }
                SchemaMigrator.migrate(connection);
                future.complete(null);
            } catch (IOException | SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Runs {@code action} against the connection on the database thread and
     * completes the returned future with its result (or failure).
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
     * Closes the connection and stops the database thread, waiting briefly
     * for pending work to finish. Intended for plugin shutdown only.
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
