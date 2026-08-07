package be.lloyd.rpgquest.webapi.store;

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
 * Base SQLite propre au module {@code web-api} ({@code store.db}),
 * entièrement séparée de {@code data.db} du plugin (mission étape 21,
 * points 1-2 : jamais d'accès direct depuis le web). Même conception que
 * {@code be.lloyd.rpgquest.database.DatabaseManager} côté plugin — un seul
 * thread dédié, sérialise tous les accès — mais réimplémentée ici plutôt
 * que partagée : les deux modules restent délibérément indépendants (voir
 * docs/WEB_API.md, section "Aucune dépendance externe obligatoire dans
 * web-api").
 *
 * <p>Un stockage transactionnel est nécessaire ici (contrairement au reste
 * de web-api, purement lecture de snapshot) : une commande/livraison
 * idempotente ne peut pas être garantie par un simple fichier JSON sous
 * écritures concurrentes (mission étape 22, validation "aucun achat ne
 * peut être livré deux fois").</p>
 */
public final class StoreDatabase {

    private static final int CURRENT_VERSION = 1;

    private final Path databaseFile;
    private final ExecutorService executor;
    private volatile Connection connection;

    public StoreDatabase(Path databaseFile) {
        this.databaseFile = databaseFile;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RPGQuest-StoreDatabase");
            thread.setDaemon(true);
            return thread;
        });
    }

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
                migrate(connection);
                future.complete(null);
            } catch (IOException | SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

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

    private static void migrate(Connection connection) throws SQLException {
        int version = currentVersion(connection);
        if (version < 1) {
            applyV1(connection);
            version = 1;
        }
        setVersion(connection, version);
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA user_version")) {
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
                    CREATE TABLE IF NOT EXISTS orders (
                        id TEXT PRIMARY KEY,
                        product_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        status TEXT NOT NULL,
                        amount_cents INTEGER NOT NULL,
                        currency TEXT NOT NULL,
                        provider_session_id TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_orders_player ON orders (player_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_orders_session ON orders (provider_session_id)");

            // kind = GRANT (achat/upgrade) ou REVOKE (remboursement) — mission point 10.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS deliveries (
                        id TEXT PRIMARY KEY,
                        order_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        product_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        delivered_at TEXT,
                        last_error TEXT,
                        FOREIGN KEY (order_id) REFERENCES orders (id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_deliveries_status ON deliveries (status)");

            // Déduplication des webhooks rejoués par le prestataire (mission, test "webhook répété") :
            // l'identifiant d'événement du prestataire est la clé primaire, jamais un simple horodatage.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS webhook_events (
                        event_id TEXT PRIMARY KEY,
                        order_id TEXT,
                        received_at TEXT NOT NULL
                    )
                    """);
        }
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}
