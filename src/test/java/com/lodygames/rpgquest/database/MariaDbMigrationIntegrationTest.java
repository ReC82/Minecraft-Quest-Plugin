package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.migration.MigrationFixtures;
import com.lodygames.rpgquest.database.migration.MigrationMode;
import com.lodygames.rpgquest.database.migration.MigrationReport;
import com.lodygames.rpgquest.database.migration.SqliteMigrationTool;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Outil de migration SQLite → MariaDB (issue #42) contre un <strong>vrai</strong> serveur MariaDB.
 *
 * <p>Optionnel, comme les autres tests MariaDB : ignoré sans les variables {@code RPGQUEST_DB_*}
 * (voir {@link MariaDbTestSupport}). Chaque test crée sa propre base jetable
 * {@code <RPGQUEST_DB_NAME>_mig_<n>} (le compte de test doit pouvoir la créer) et la supprime à la
 * fin : c'est le <em>test</em> qui nettoie, l'outil lui-même ne supprime jamais rien.</p>
 */
class MariaDbMigrationIntegrationTest {

    @TempDir
    Path dir;

    private String database;

    @BeforeEach
    void createScratchDatabase() throws SQLException {
        MariaDbTestSupport.assumeAvailable();
        database = System.getenv("RPGQUEST_DB_NAME") + "_mig_" + Long.toUnsignedString(System.nanoTime(), 36);
        try (Connection server = serverConnection(); Statement statement = server.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
    }

    @AfterEach
    void dropScratchDatabase() throws SQLException {
        if (database != null) {
            try (Connection server = serverConnection(); Statement statement = server.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database);
            }
        }
    }

    private static Connection serverConnection() throws SQLException {
        DatabaseSettings.MySqlSettings settings = MariaDbTestSupport.settings();
        return DriverManager.getConnection("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/",
                settings.username(), System.getenv("RPGQUEST_DB_PASSWORD"));
    }

    private MySqlDatabaseEngine target() {
        DatabaseSettings.MySqlSettings base = MariaDbTestSupport.settings();
        return new MySqlDatabaseEngine(new DatabaseSettings.MySqlSettings(base.host(), base.port(), database,
                base.username(), base.passwordEnv(), base.sslMode(), base.pool()), System::getenv);
    }

    private MigrationReport run(Path source, MigrationMode mode) {
        return new SqliteMigrationTool(Clock.systemUTC(), UnaryOperator.identity())
                .run(source, target(), "mariadb …/" + database, mode);
    }

    @Test
    void realisticDatasetIsCopiedVerifiedAndReadableByThePlugin() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));

        MigrationReport report = run(source, MigrationMode.COMMIT);

        assertEquals(MigrationReport.Status.SUCCESS, report.status(), report.toText());
        assertTrue(report.dataCommitted());
        // le plugin lui-même (repositories + dialecte MariaDB) relit les données migrées
        DatabaseManager manager = new DatabaseManager(target());
        try {
            manager.initialize().get(30, TimeUnit.SECONDS);
            WalletRepository wallets = new WalletRepository(manager);
            assertEquals(1500L, wallets.balance(UUID.fromString(MigrationFixtures.ALICE)).get(10, TimeUnit.SECONDS));
            // séquence : l'identifiant npc_3, supprimé avant la migration, n'est jamais réattribué
            int next = new NpcIdRepository(manager).allocateId().get(10, TimeUnit.SECONDS);
            assertEquals(4, next);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void dryRunLeavesNoDataAndNonEmptyTargetIsRefused() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));

        MigrationReport dryRun = run(source, MigrationMode.DRY_RUN);
        assertEquals(MigrationReport.Status.SUCCESS, dryRun.status(), dryRun.toText());
        assertEquals(0L, count("player_profiles"));

        assertEquals(MigrationReport.Status.SUCCESS, run(source, MigrationMode.COMMIT).status());
        MigrationReport again = run(source, MigrationMode.COMMIT);
        assertEquals(MigrationReport.Status.FAILED, again.status());
        assertFalse(again.dataCommitted());
        assertEquals(3L, count("player_profiles"), "rien n'a été dupliqué");
    }

    @Test
    void textLongerThanTargetColumnIsDetectedBeforeAnyWrite() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO building_sites (id, name, description, world, x, y, z, facing, created_at) "
                             + "VALUES ('buildsite_1', 'Hutte', ?, 'world', 0, 64, 0, 'NORTH', 'maintenant')")) {
            insert.setString(1, "x".repeat(400));
            insert.executeUpdate();
        }

        MigrationReport report = run(source, MigrationMode.DRY_RUN);

        assertEquals(MigrationReport.Status.FAILED, report.status());
        assertTrue(report.findings().stream().anyMatch(f -> f.scope().equals("building_sites.description")),
                report.toText());
        assertTrue(report.tables().isEmpty(), "copie non tentée");
    }

    private long count(String table) throws SQLException {
        try (Connection server = serverConnection(); Statement statement = server.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + database + "." + table)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
