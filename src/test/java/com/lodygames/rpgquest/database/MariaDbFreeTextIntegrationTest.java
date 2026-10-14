package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #354 — les textes libres {@code NOT NULL} sont des {@code TEXT} sur MariaDB : une
 * description de 2 000 caractères s'écrit et se relit intacte, et une base créée avant V35 (colonnes
 * en {@code VARCHAR(191)}) est élargie au démarrage suivant. S'exécute uniquement si
 * {@code RPGQUEST_DB_*} est défini (sinon ignoré).
 */
class MariaDbFreeTextIntegrationTest {

    private static final String SITE_ID = "buildsite_it354";

    @BeforeAll
    static void requireMariaDb() {
        MariaDbTestSupport.assumeAvailable();
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        MariaDbTestSupport.wipe();
    }

    @Test
    void longDescriptionIsStoredWithoutTruncation() throws Exception {
        DatabaseManager database = new DatabaseManager(MariaDbTestSupport.engine());
        try {
            database.initialize().get(30, TimeUnit.SECONDS);
            String description = "é".repeat(1_000) + "x".repeat(1_000);
            String read = database.execute(connection -> writeAndReadDescription(connection, description))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(description, read);
            for (MySqlDialect.FreeTextColumn column : MySqlDialect.FREE_TEXT_COLUMNS) {
                assertEquals("text", database.execute(connection -> dataType(connection, column))
                        .get(10, TimeUnit.SECONDS), column.table() + "." + column.column());
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void baseCreatedBeforeV35IsWidenedOnNextStart() throws Exception {
        DatabaseManager first = new DatabaseManager(MariaDbTestSupport.engine());
        try {
            first.initialize().get(30, TimeUnit.SECONDS);
            // reproduit l'état d'une base créée par le dialecte V34 : textes libres en VARCHAR(191)
            first.execute(connection -> {
                // wipe() ne connaît pas les tables bâtiment : la ligne longue d'un test précédent
                // empêcherait le rétrécissement
                deleteTestSite(connection);
                try (Statement statement = connection.createStatement()) {
                    for (MySqlDialect.FreeTextColumn column : MySqlDialect.FREE_TEXT_COLUMNS) {
                        statement.execute(column.widenDdl().replace(" TEXT NOT NULL", " VARCHAR(191) NOT NULL"));
                    }
                    statement.execute("DELETE FROM rpgquest_schema_migrations WHERE version = 35");
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
            assertEquals("varchar", first.execute(connection -> dataType(connection,
                    MySqlDialect.FREE_TEXT_COLUMNS.get(0))).get(10, TimeUnit.SECONDS));
        } finally {
            first.shutdown();
        }

        DatabaseManager second = new DatabaseManager(MariaDbTestSupport.engine());
        try {
            second.initialize().get(30, TimeUnit.SECONDS);
            for (MySqlDialect.FreeTextColumn column : MySqlDialect.FREE_TEXT_COLUMNS) {
                assertEquals("text", second.execute(connection -> dataType(connection, column))
                        .get(10, TimeUnit.SECONDS), column.table() + "." + column.column());
            }
            String description = "d".repeat(2_000);
            assertEquals(description, second.execute(connection -> writeAndReadDescription(connection, description))
                    .get(10, TimeUnit.SECONDS));
        } finally {
            second.shutdown();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static void deleteTestSite(Connection connection) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM building_sites WHERE id = ?")) {
            delete.setString(1, SITE_ID);
            delete.executeUpdate();
        }
    }

    private static String writeAndReadDescription(Connection connection, String description) throws SQLException {
        deleteTestSite(connection);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO building_sites (id, name, description, world, x, y, z, facing, created_at) "
                        + "VALUES (?, 'Test #354', ?, 'world', 0, 64, 0, 'NORTH', '2026-10-14T00:00:00Z')")) {
            insert.setString(1, SITE_ID);
            insert.setString(2, description);
            insert.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT description FROM building_sites WHERE id = ?")) {
            select.setString(1, SITE_ID);
            try (ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static String dataType(Connection connection, MySqlDialect.FreeTextColumn column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, column.table());
            statement.setString(2, column.column());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1).toLowerCase(java.util.Locale.ROOT);
            }
        }
    }
}
