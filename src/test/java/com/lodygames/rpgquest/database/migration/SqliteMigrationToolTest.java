package com.lodygames.rpgquest.database.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.PragmaUserVersionHistory;
import com.lodygames.rpgquest.database.SchemaMigration;
import com.lodygames.rpgquest.database.SchemaMigrationRunner;
import com.lodygames.rpgquest.database.SchemaMigrator;
import com.lodygames.rpgquest.database.SqliteDatabaseEngine;
import com.lodygames.rpgquest.database.SqliteDialect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Outil de migration SQLite → moteur cible (issue #42), exercé de bout en bout avec une cible
 * SQLite : le déroulé (audit, refus, copie transactionnelle, empreintes, séquences) est
 * indépendant du moteur. La même chaîne contre un vrai MariaDB : {@code MariaDbMigrationIntegrationTest}.
 */
class SqliteMigrationToolTest {

    @TempDir
    Path dir;

    private final SqliteMigrationTool tool = new SqliteMigrationTool(Clock.systemUTC(), UnaryOperator.identity());

    private MigrationReport run(Path source, Path target, MigrationMode mode) {
        return tool.run(source, target == null ? null : new SqliteDatabaseEngine(target),
                target == null ? "" : "sqlite (" + target.getFileName() + ")", mode);
    }

    @Test
    void migratesAnEmptyDatabase() throws Exception {
        Path source = MigrationFixtures.emptyDatabase(dir.resolve("empty.db"));

        MigrationReport report = run(source, dir.resolve("target.db"), MigrationMode.COMMIT);

        assertEquals(MigrationReport.Status.SUCCESS, report.status(), report.toText());
        assertTrue(report.dataCommitted());
        assertFalse(report.tables().isEmpty());
        assertTrue(report.tables().stream().allMatch(t -> t.sourceRows() == 0 && t.matches()));
    }

    @Test
    void commitPreservesEveryRowUuidsAndValues() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path target = dir.resolve("target.db");

        MigrationReport report = run(source, target, MigrationMode.COMMIT);

        assertEquals(MigrationReport.Status.SUCCESS, report.status(), report.toText());
        assertTrue(report.dataCommitted());
        assertEquals(1200, row(report, "transactions").targetRows());
        try (Connection connection = MigrationFixtures.open(target)) {
            assertEquals("Bob_l'Éclaireur", scalar(connection,
                    "SELECT last_name FROM player_profiles WHERE uuid = '" + MigrationFixtures.BOB + "'"));
            assertEquals("COMPLETED", scalar(connection, "SELECT state FROM quest_progress WHERE player_uuid = '"
                    + MigrationFixtures.BOB + "'"));
            assertEquals("2", scalar(connection, "SELECT current_index FROM story_progress"));
            assertEquals("casse distincte", scalar(connection,
                    "SELECT variable_value FROM player_variables WHERE variable_key = 'Unlock.Mine'"));
            assertEquals(null, scalar(connection, "SELECT variable_value FROM player_variables WHERE variable_key = 'note'"));
            assertEquals("", scalar(connection, "SELECT variable_value FROM player_variables WHERE variable_key = 'vide'"));
            assertEquals(MigrationFixtures.BOB, scalar(connection, "SELECT member_uuid FROM claim_members"));
            assertEquals("1500", scalar(connection, "SELECT balance FROM wallets WHERE player_uuid = '"
                    + MigrationFixtures.ALICE + "'"));
            assertEquals("000102ff80002a", scalar(connection, "SELECT lower(hex(item_data)) FROM market_listings"));
            assertEquals("1024.125", scalar(connection, "SELECT x FROM village_centers WHERE id = 'port'"));
        }
    }

    @Test
    void autoIncrementGapIsPreservedSoIdsAreNeverReused() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path target = dir.resolve("target.db");

        MigrationReport report = run(source, target, MigrationMode.COMMIT);

        MigrationReport.SequenceResult npcIds = report.sequences().stream()
                .filter(s -> s.table().equals("npc_ids")).findFirst().orElseThrow();
        assertEquals(4, npcIds.sourceNext());
        assertTrue(npcIds.ok());
        try (Connection connection = MigrationFixtures.open(target);
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO npc_ids (created_at) VALUES ('après migration')");
            assertEquals("4", scalar(connection, "SELECT MAX(id) FROM npc_ids"));
        }
    }

    @Test
    void dryRunValidatesEverythingButKeepsNothing() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path target = dir.resolve("target.db");

        MigrationReport report = run(source, target, MigrationMode.DRY_RUN);

        assertEquals(MigrationReport.Status.SUCCESS, report.status(), report.toText());
        assertFalse(report.dataCommitted());
        assertTrue(report.tables().stream().allMatch(MigrationReport.TableResult::matches));
        try (Connection connection = MigrationFixtures.open(target)) {
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM player_profiles"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM transactions"));
        }
    }

    @Test
    void auditWritesNowhereNotEvenTheTargetSchema() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path target = MigrationFixtures.emptyDatabase(dir.resolve("target.db"));
        // cible déjà au schéma courant et vide : l'audit la déclare utilisable sans y toucher
        String before = SqliteSource.sha256(target);

        MigrationReport report = run(source, target, MigrationMode.AUDIT);

        assertEquals(MigrationReport.Status.SUCCESS, report.status(), report.toText());
        assertTrue(report.tables().isEmpty());
        assertEquals(before, SqliteSource.sha256(target));
    }

    @Test
    void sourceIsNeverModified() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        String before = SqliteSource.sha256(source);
        long modified = Files.getLastModifiedTime(source).toMillis();

        run(source, dir.resolve("target.db"), MigrationMode.COMMIT);

        assertEquals(before, SqliteSource.sha256(source));
        assertEquals(modified, Files.getLastModifiedTime(source).toMillis());
    }

    @Test
    void sourceIsOpenedReadOnly() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        try (SqliteSource opened = SqliteSource.openReadOnly(source);
             Statement statement = opened.connection().createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("DELETE FROM player_profiles"));
        }
        assertThrows(SQLException.class, () -> SqliteSource.openReadOnly(dir.resolve("absent.db")));
        assertFalse(Files.exists(dir.resolve("absent.db")), "jamais de création d'une source absente");
    }

    @Test
    void nonEmptyTargetIsRefusedAndLeftUntouched() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path target = dir.resolve("target.db");
        assertEquals(MigrationReport.Status.SUCCESS, run(source, target, MigrationMode.COMMIT).status());
        String before = SqliteSource.sha256(target);

        MigrationReport second = run(source, target, MigrationMode.COMMIT);

        assertEquals(MigrationReport.Status.FAILED, second.status());
        assertFalse(second.dataCommitted());
        assertTrue(second.findings().stream().anyMatch(f -> f.check() == MigrationFinding.Check.TARGET
                && f.message().contains("contient déjà")));
        assertEquals(before, SqliteSource.sha256(target), "une cible refusée n'est jamais modifiée");
    }

    @Test
    void foreignTableInTargetIsRefused() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path target = dir.resolve("target.db");
        try (Connection connection = MigrationFixtures.open(target); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE autre_application (id INTEGER)");
        }

        MigrationReport report = run(source, target, MigrationMode.DRY_RUN);

        assertEquals(MigrationReport.Status.FAILED, report.status());
        assertTrue(report.findings().stream().anyMatch(f -> f.scope().equals("autre_application")));
    }

    @Test
    void failureDuringCopyRollsBackEverything() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path target = MigrationFixtures.emptyDatabase(dir.resolve("target.db"));
        try (Connection connection = MigrationFixtures.open(target); Statement statement = connection.createStatement()) {
            // panne simulée au milieu de l'import : les tables précédentes ont déjà été copiées
            statement.execute("CREATE TRIGGER panne BEFORE INSERT ON wallets BEGIN SELECT RAISE(ABORT, 'disque plein'); END");
        }

        MigrationReport report = run(source, target, MigrationMode.COMMIT);

        assertEquals(MigrationReport.Status.FAILED, report.status());
        assertFalse(report.dataCommitted());
        assertTrue(report.findings().stream().anyMatch(f -> f.scope().equals("wallets")
                && f.message().contains("disque plein")), report.toText());
        try (Connection connection = MigrationFixtures.open(target)) {
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM player_profiles"),
                    "les tables déjà copiées sont annulées avec le reste");
        }
    }

    @Test
    void sourceAtAnotherSchemaVersionIsRefused() throws Exception {
        Path source = dir.resolve("old.db");
        List<SchemaMigration> older = SchemaMigrator.ALL.stream()
                .filter(m -> m.version() < SchemaMigrator.CURRENT_VERSION).toList();
        try (Connection connection = MigrationFixtures.open(source)) {
            new SchemaMigrationRunner(older, new PragmaUserVersionHistory(), new SqliteDialect()).run(connection);
        }

        MigrationReport report = run(source, dir.resolve("target.db"), MigrationMode.DRY_RUN);

        assertEquals(MigrationReport.Status.FAILED, report.status());
        assertTrue(report.findings().stream().anyMatch(f -> f.check() == MigrationFinding.Check.SOURCE
                && f.message().contains("schéma source V" + (SchemaMigrator.CURRENT_VERSION - 1))));
        assertTrue(report.tables().isEmpty(), "aucune copie tentée");
    }

    @Test
    void orphanRowsInSourceAreCritical() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        try (Connection connection = MigrationFixtures.open(source); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("INSERT INTO player_variables VALUES ('ffffffff-0000-4000-8000-000000000000', 'k', 'v')");
        }

        MigrationReport report = run(source, dir.resolve("target.db"), MigrationMode.DRY_RUN);

        assertEquals(MigrationReport.Status.FAILED, report.status());
        assertTrue(report.findings().stream().anyMatch(f -> f.check() == MigrationFinding.Check.FOREIGN_KEY));
    }

    @Test
    void valueNotMatchingDeclaredTypeIsCritical() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        try (Connection connection = MigrationFixtures.open(source); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE wallets SET balance = 'beaucoup' WHERE player_uuid = '" + MigrationFixtures.BOB + "'");
        }

        MigrationReport report = run(source, dir.resolve("target.db"), MigrationMode.DRY_RUN);

        assertEquals(MigrationReport.Status.FAILED, report.status());
        assertTrue(report.findings().stream().anyMatch(f -> f.check() == MigrationFinding.Check.TYPE
                && f.scope().equals("wallets.balance")), report.toText());
    }

    @Test
    void hotJournalNextToSourceIsCritical() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Files.writeString(dir.resolve("data.db-journal"), "copie prise à chaud");

        MigrationReport report = run(source, null, MigrationMode.AUDIT);

        assertEquals(MigrationReport.Status.FAILED, report.status());
        assertTrue(report.findings().stream().anyMatch(f -> f.message().contains("data.db-journal")));
    }

    @Test
    void invalidPlayerUuidIsReportedAsWarningOnly() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        try (Connection connection = MigrationFixtures.open(source); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO player_profiles VALUES ('pas-un-uuid', 'X', 'a', 'b')");
        }

        MigrationReport report = run(source, dir.resolve("target.db"), MigrationMode.DRY_RUN);

        assertEquals(MigrationReport.Status.SUCCESS, report.status(), report.toText());
        assertTrue(report.findings().stream().anyMatch(f -> f.check() == MigrationFinding.Check.UUID
                && f.severity() == MigrationFinding.Severity.WARNING && f.scope().equals("player_profiles.uuid")));
    }

    @Test
    void reportIsRenderedAsTextAndJson() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));

        MigrationReport report = run(source, dir.resolve("target.db"), MigrationMode.DRY_RUN);

        String text = report.toText();
        assertTrue(text.contains("Statut de la migration : SUCCESS"), text);
        assertTrue(text.contains("Joueurs") && text.contains("player_profiles"));
        assertTrue(text.contains("Source SQLite inchangée : OK"));
        String json = report.toJson();
        assertTrue(json.contains("\"status\": \"SUCCESS\""), json);
        assertTrue(json.contains("\"table\": \"transactions\""));
        assertTrue(json.contains("\"sourceRows\": 1200"));
    }

    private static MigrationReport.TableResult row(MigrationReport report, String table) {
        return report.tables().stream().filter(t -> t.table().equals(table)).findFirst().orElseThrow();
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : "<aucune ligne>";
        }
    }
}
