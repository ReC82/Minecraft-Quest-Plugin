package com.lodygames.rpgquest.database.migration;

import com.lodygames.rpgquest.database.DatabaseEngine;
import com.lodygames.rpgquest.database.SchemaMigrationRunner;
import com.lodygames.rpgquest.database.SchemaMigrator;
import com.lodygames.rpgquest.database.SqlDialect;
import com.lodygames.rpgquest.database.migration.MigrationFinding.Check;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Migration <strong>contrôlée</strong> d'une base SQLite RPGQuest vers un autre moteur — MariaDB
 * en pratique, SQLite pour les répétitions et les tests (issue #42).
 *
 * <p>Déroulé : audit de la source (lecture seule) → contrôle de la cible (joignable, dédiée,
 * <strong>vide</strong>, schéma pas plus récent que l'outil) → création du schéma par le catalogue
 * de migrations du plugin ({@link SchemaMigrator#ALL}, le même que celui qu'exécute le plugin au
 * démarrage) → contrôle des types/tailles → copie de toutes les tables dans l'ordre des clés
 * étrangères, <strong>dans une seule transaction</strong> → validation (comptages, empreintes
 * logiques, orphelins) → validation de la transaction uniquement en mode {@link MigrationMode#COMMIT}
 * et si aucun constat n'est critique → alignement des séquences {@code AUTOINCREMENT}.</p>
 *
 * <p>Garanties :</p>
 * <ul>
 *   <li>la source n'est <strong>jamais</strong> écrite (lecture seule + empreinte avant/après) ;</li>
 *   <li>une cible contenant déjà des données, ou des tables étrangères à RPGQuest, est
 *       <strong>refusée</strong> avant toute écriture — l'outil ne fait jamais de {@code DROP},
 *       {@code DELETE} ni {@code TRUNCATE} ;</li>
 *   <li>un échec en cours de copie annule la transaction : la cible reste vide de données ;</li>
 *   <li>le statut {@code SUCCESS} exige que chaque table ait le même nombre de lignes et la même
 *       empreinte logique des deux côtés.</li>
 * </ul>
 *
 * <p>Ce n'est <strong>pas</strong> une commande Minecraft : l'outil s'exécute hors serveur, sur
 * une copie de {@code data.db} prise serveur arrêté (voir {@code docs/deployment/MIGRATION_SQLITE_MARIADB.md}).</p>
 */
public final class SqliteMigrationTool {

    private static final int BATCH_SIZE = 500;
    private static final int MAX_LISTED = 10;

    private final Clock clock;
    private final UnaryOperator<String> redact;

    /**
     * @param clock  horloge du rapport
     * @param redact filtre appliqué à tout message d'erreur issu de la cible avant de l'écrire dans le
     *               rapport (masque l'hôte ; les messages du driver ne contiennent pas le mot de passe)
     */
    public SqliteMigrationTool(Clock clock, UnaryOperator<String> redact) {
        this.clock = clock;
        this.redact = redact;
    }

    /**
     * Exécute l'outil.
     *
     * @param sourceFile  copie de {@code data.db}
     * @param target      moteur cible <strong>non démarré</strong>, ou {@code null} pour un audit de
     *                    la seule source ({@link MigrationMode#AUDIT} uniquement)
     * @param targetLabel description sûre de la cible (moteur + nom de base, jamais l'hôte)
     */
    public MigrationReport run(Path sourceFile, DatabaseEngine target, String targetLabel, MigrationMode mode) {
        if (target == null && mode != MigrationMode.AUDIT) {
            throw new IllegalArgumentException("une cible est obligatoire hors mode audit");
        }
        MigrationReport report = new MigrationReport(mode, clock.instant());
        String shaBefore;
        try {
            shaBefore = SqliteSource.sha256(sourceFile);
            for (Path journal : SqliteSource.pendingJournals(sourceFile)) {
                report.add(MigrationFinding.critical(Check.SOURCE, "source", "journal SQLite non vide à côté de la "
                        + "source (" + journal.getFileName() + ") : copie prise serveur démarré ? Arrêter "
                        + "RPGQuest, puis recopier data.db"));
            }
        } catch (IOException e) {
            report.add(MigrationFinding.critical(Check.SOURCE, "source", "fichier source illisible : " + e.getMessage()));
            report.finish(clock.instant());
            return report;
        }

        try (SqliteSource source = SqliteSource.openReadOnly(sourceFile)) {
            List<SourceTable> tables = auditSource(source, report);
            if (target != null && tables != null) {
                migrate(source, tables, target, targetLabel, mode, report);
            }
        } catch (SQLException | RuntimeException e) {
            report.add(MigrationFinding.critical(Check.SOURCE, "source", "lecture de la source impossible : "
                    + e.getMessage()));
        } catch (LinkageError e) {
            report.add(MigrationFinding.critical(Check.SOURCE, "outil", "classe absente du JAR de l'outil ("
                    + e.getMessage() + ") : JAR incomplet, reconstruire avec ./gradlew dbMigrationToolJar"));
        } finally {
            if (target != null) {
                target.close();
            }
        }

        try {
            report.sourceChecksums(shaBefore, SqliteSource.sha256(sourceFile));
        } catch (IOException e) {
            report.add(MigrationFinding.critical(Check.SOURCE, "source", "empreinte finale illisible : " + e.getMessage()));
        }
        report.finish(clock.instant());
        return report;
    }

    // ---- 1. source -------------------------------------------------------------------------

    /** Audite la source ; renvoie les tables dans l'ordre d'import, ou {@code null} si illisible. */
    private List<SourceTable> auditSource(SqliteSource source, MigrationReport report) throws SQLException {
        int version = source.userVersion();
        report.source(source.file().getFileName().toString(), version, SchemaMigrator.CURRENT_VERSION);
        if (version != SchemaMigrator.CURRENT_VERSION) {
            report.add(MigrationFinding.critical(Check.SOURCE, "source", "schéma source V" + version
                    + " ≠ schéma de l'outil V" + SchemaMigrator.CURRENT_VERSION + " : la source doit être "
                    + "produite par la MÊME version du plugin que l'outil (démarrer une fois le plugin de "
                    + "cette version sur SQLite, l'arrêter, puis recopier data.db)"));
        }

        List<String> integrity = source.integrityCheck();
        if (!List.of("ok").equals(integrity)) {
            report.add(MigrationFinding.critical(Check.INTEGRITY, "source", "PRAGMA integrity_check : "
                    + String.join(" | ", integrity.subList(0, Math.min(MAX_LISTED, integrity.size())))));
        }
        List<String> violations = source.foreignKeyViolations();
        if (!violations.isEmpty()) {
            report.add(MigrationFinding.critical(Check.FOREIGN_KEY, "source", violations.size()
                    + " ligne(s) orpheline(s) (PRAGMA foreign_key_check), refusées par MariaDB : "
                    + String.join(", ", violations.subList(0, Math.min(MAX_LISTED, violations.size())))));
        }

        Map<String, SourceTable> byName = new LinkedHashMap<>();
        for (String name : source.tableNames()) {
            if (!SqliteSource.IDENTIFIER.matcher(name).matches()) {
                report.add(MigrationFinding.critical(Check.SOURCE, name, "nom de table non pris en charge"));
                continue;
            }
            SourceTable table = source.describe(name);
            byName.put(name, table);
            auditColumns(source, table, report);
        }
        List<SourceTable> ordered = importOrder(byName, report);
        report.add(MigrationFinding.info(Check.SOURCE, "source", byName.size() + " table(s) ; ordre d'import : "
                + String.join(", ", ordered.stream().map(SourceTable::name).toList())));
        return ordered;
    }

    private void auditColumns(SqliteSource source, SourceTable table, MigrationReport report) throws SQLException {
        for (SourceTable.Column column : table.columns()) {
            String scope = table.name() + "." + column.name();
            if (!SqliteSource.IDENTIFIER.matcher(column.name()).matches()) {
                report.add(MigrationFinding.critical(Check.SOURCE, scope, "nom de colonne non pris en charge"));
                continue;
            }
            Map<String, Long> classes = source.storageClasses(table.name(), column.name());
            Set<String> allowed = switch (column.affinity()) {
                case "INTEGER" -> Set.of("integer", "null");
                case "REAL", "NUMERIC" -> Set.of("integer", "real", "null");
                case "TEXT" -> Set.of("text", "null");
                default -> Set.of("blob", "null");
            };
            Map<String, Long> unexpected = new LinkedHashMap<>(classes);
            unexpected.keySet().removeAll(allowed);
            if (!unexpected.isEmpty()) {
                report.add(MigrationFinding.critical(Check.TYPE, scope, "valeurs dont le type réel ne correspond "
                        + "pas au type déclaré " + column.declaredType() + " : " + unexpected
                        + " (SQLite les accepte, MariaDB en mode strict non)"));
            }
            if (column.name().toLowerCase(Locale.ROOT).endsWith("uuid") && "TEXT".equals(column.affinity())) {
                long invalid = source.invalidUuids(table.name(), column.name());
                if (invalid > 0) {
                    report.add(MigrationFinding.warning(Check.UUID, scope, invalid + " valeur(s) qui ne sont pas des "
                            + "UUID valides (copiées telles quelles)"));
                }
            }
        }
    }

    /** Tri topologique sur les clés étrangères : un parent est toujours importé avant ses enfants. */
    private static List<SourceTable> importOrder(Map<String, SourceTable> tables, MigrationReport report) {
        List<SourceTable> ordered = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String name : tables.keySet()) {
            visit(name, tables, done, visiting, ordered, report);
        }
        return ordered;
    }

    private static void visit(String name, Map<String, SourceTable> tables, Set<String> done, Set<String> visiting,
                              List<SourceTable> ordered, MigrationReport report) {
        if (done.contains(name)) {
            return;
        }
        SourceTable table = tables.get(name);
        if (table == null) {
            return;
        }
        if (!visiting.add(name)) {
            report.add(MigrationFinding.critical(Check.FOREIGN_KEY, name, "cycle de clés étrangères : ordre d'import "
                    + "impossible à garantir"));
            return;
        }
        for (SourceTable.ForeignKey fk : table.foreignKeys()) {
            if (!fk.parentTable().equals(name)) {
                if (!tables.containsKey(fk.parentTable())) {
                    report.add(MigrationFinding.critical(Check.FOREIGN_KEY, name, "clé étrangère vers une table absente : "
                            + fk.parentTable()));
                }
                visit(fk.parentTable(), tables, done, visiting, ordered, report);
            }
        }
        visiting.remove(name);
        done.add(name);
        ordered.add(table);
    }

    // ---- 2. cible --------------------------------------------------------------------------

    private void migrate(SqliteSource source, List<SourceTable> tables, DatabaseEngine target, String targetLabel,
                         MigrationMode mode, MigrationReport report) {
        try {
            target.start();
        } catch (SQLException | RuntimeException e) {
            report.target(targetLabel, -1);
            report.add(MigrationFinding.critical(Check.TARGET, "cible", "connexion impossible : "
                    + redact.apply(String.valueOf(e.getMessage()))));
            return;
        }
        Connection connection = null;
        try {
            connection = target.borrow();
            if (!inspectTarget(connection, tables, target, targetLabel, report)) {
                return;
            }
            if (mode == MigrationMode.AUDIT) {
                report.add(MigrationFinding.info(Check.TARGET, "cible", "audit : cible joignable et vide, aucune écriture "
                        + "effectuée (schéma non créé)"));
                return;
            }
            if (!hasCritical(report)) {
                copyAndValidate(source, tables, connection, target, targetLabel, mode, report);
            } else {
                report.add(MigrationFinding.info(Check.TARGET, "cible", "copie non tentée : constats critiques sur la "
                        + "source"));
            }
        } catch (SQLException | RuntimeException e) {
            report.add(MigrationFinding.critical(Check.TARGET, "cible", "erreur sur la cible : "
                    + redact.apply(String.valueOf(e.getMessage()))));
        } finally {
            if (connection != null) {
                target.release(connection);
            }
        }
    }

    /** Contrôles en lecture seule de la cible. {@code false} : refus, rien n'a été écrit. */
    private boolean inspectTarget(Connection connection, List<SourceTable> tables, DatabaseEngine target,
                                  String targetLabel, MigrationReport report) throws SQLException {
        Set<String> sourceNames = new LinkedHashSet<>();
        tables.forEach(t -> sourceNames.add(t.name()));
        List<String> existing = targetTables(connection);
        boolean refused = false;
        for (String table : existing) {
            if (table.equalsIgnoreCase("rpgquest_schema_migrations") || table.startsWith("sqlite_")) {
                continue;
            }
            if (!sourceNames.contains(table)) {
                report.add(MigrationFinding.critical(Check.TARGET, table, "table inconnue de RPGQuest dans la cible : la "
                        + "base doit être DÉDIÉE à RPGQuest. Refus (l'outil ne supprime jamais rien)"));
                refused = true;
                continue;
            }
            long rows = count(connection, table);
            if (rows > 0) {
                report.add(MigrationFinding.critical(Check.TARGET, table, "la cible contient déjà " + rows + " ligne(s). "
                        + "Refus : l'outil n'écrase ni ne complète jamais une base existante. Utiliser une base vide"));
                refused = true;
            }
        }
        int version = existing.isEmpty() ? 0 : target.schemaHistory().currentVersion(connection);
        report.target(targetLabel, version);
        if (version > SchemaMigrator.CURRENT_VERSION) {
            report.add(MigrationFinding.critical(Check.TARGET, "cible", "schéma cible V" + version
                    + " plus récent que l'outil V" + SchemaMigrator.CURRENT_VERSION));
            refused = true;
        }
        return !refused;
    }

    // ---- 3. copie + validation -------------------------------------------------------------

    private void copyAndValidate(SqliteSource source, List<SourceTable> tables, Connection connection,
                                 DatabaseEngine target, String targetLabel, MigrationMode mode,
                                 MigrationReport report) throws SQLException {
        SqlDialect dialect = target.dialect();
        // même catalogue et même historique que ceux qu'exécute le plugin au démarrage sur ce moteur
        int version = new SchemaMigrationRunner(SchemaMigrator.ALL, target.schemaHistory(), dialect)
                .run(connection);
        report.target(targetLabel, version);

        checkTargetColumns(source, tables, connection, dialect, report);
        if (hasCritical(report)) {
            report.add(MigrationFinding.info(Check.DATA, "cible", "copie non tentée : incompatibilités de schéma"));
            return;
        }

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        boolean committed = false;
        try {
            for (SourceTable table : tables) {
                try {
                    copyTable(source.connection(), connection, table);
                } catch (SQLException e) {
                    report.add(MigrationFinding.critical(Check.DATA, table.name(), "échec de la copie, transaction "
                            + "annulée : " + redact.apply(String.valueOf(e.getMessage()))));
                    connection.rollback();
                    return;
                }
            }
            validate(source.connection(), connection, tables, report);
            Map<String, Long> plannedSequences = plannedSequences(source, tables, connection, dialect);

            if (mode == MigrationMode.COMMIT && !hasCritical(report)) {
                connection.commit();
                committed = true;
                report.dataCommitted(true);
            } else {
                connection.rollback();
                for (Map.Entry<String, Long> entry : plannedSequences.entrySet()) {
                    report.addSequence(new MigrationReport.SequenceResult(entry.getKey(), entry.getValue(),
                            entry.getValue(), false));
                }
                if (mode == MigrationMode.DRY_RUN) {
                    report.add(MigrationFinding.info(Check.DATA, "cible", "dry-run : transaction annulée, aucune donnée "
                            + "conservée dans la cible"));
                }
            }
            if (committed) {
                connection.setAutoCommit(true);
                alignSequences(source, tables, connection, dialect, report);
                recount(source, tables, connection, report);
            }
        } finally {
            if (!committed) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // déjà annulée
                }
            }
            connection.setAutoCommit(autoCommit);
        }
    }

    private void checkTargetColumns(SqliteSource source, List<SourceTable> tables, Connection connection,
                                    SqlDialect dialect, MigrationReport report) throws SQLException {
        boolean padSpace = !"sqlite".equals(dialect.name());
        for (SourceTable table : tables) {
            Map<String, TargetColumn> columns = targetColumns(connection, table.name());
            if (columns.isEmpty()) {
                report.add(MigrationFinding.critical(Check.TARGET, table.name(), "table absente de la cible après "
                        + "création du schéma (table inconnue du catalogue de migrations du plugin ?)"));
                continue;
            }
            for (SourceTable.Column column : table.columns()) {
                TargetColumn targetColumn = columns.remove(column.name().toLowerCase(Locale.ROOT));
                String scope = table.name() + "." + column.name();
                if (targetColumn == null) {
                    report.add(MigrationFinding.critical(Check.TARGET, scope, "colonne absente de la cible"));
                    continue;
                }
                if (targetColumn.boundedText() && "TEXT".equals(column.affinity())) {
                    long longest = source.maxTextLength(table.name(), column.name());
                    if (longest > targetColumn.size()) {
                        report.add(MigrationFinding.critical(Check.TYPE, scope, "valeur de " + longest + " caractères, "
                                + "la colonne cible est " + targetColumn.typeName() + "(" + targetColumn.size()
                                + ") : elle serait refusée (mode strict). Correction de schéma nécessaire avant "
                                + "migration"));
                    }
                }
            }
            for (TargetColumn extra : columns.values()) {
                report.add(MigrationFinding.critical(Check.TARGET, table.name() + "." + extra.name(),
                        "colonne présente dans la cible mais pas dans la source"));
            }
            if (padSpace) {
                List<List<String>> keys = new ArrayList<>(table.uniqueKeys());
                if (!table.primaryKey().isEmpty()) {
                    keys.add(table.primaryKey());
                }
                for (List<String> key : keys) {
                    long collisions = source.padSpaceCollisions(table.name(), key);
                    if (collisions > 0) {
                        report.add(MigrationFinding.critical(Check.TYPE, table.name() + key, collisions + " groupe(s) "
                                + "de clés qui ne diffèrent que par des espaces finaux : doublons pour la collation "
                                + "MariaDB utf8mb4_bin (PAD SPACE)"));
                    }
                }
            }
        }
    }

    private static void copyTable(Connection source, Connection target, SourceTable table) throws SQLException {
        List<String> columns = table.columnNames();
        String columnList = String.join(", ", columns);
        String order = table.primaryKey().isEmpty() ? "rowid" : String.join(", ", table.primaryKey());
        String insert = "INSERT INTO " + table.name() + " (" + columnList + ") VALUES ("
                + SqlDialect.placeholders(columns.size()) + ")";
        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT " + columnList + " FROM " + table.name() + " ORDER BY " + order);
             PreparedStatement write = target.prepareStatement(insert)) {
            int pending = 0;
            while (rows.next()) {
                for (int i = 1; i <= columns.size(); i++) {
                    write.setObject(i, rows.getObject(i));
                }
                write.addBatch();
                if (++pending == BATCH_SIZE) {
                    write.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                write.executeBatch();
            }
        }
    }

    private static void validate(Connection source, Connection target, List<SourceTable> tables,
                                 MigrationReport report) throws SQLException {
        for (SourceTable table : tables) {
            LogicalChecksum.Result before = LogicalChecksum.of(source, table.name(), table.columnNames());
            LogicalChecksum.Result after = LogicalChecksum.of(target, table.name(), table.columnNames());
            report.addTable(new MigrationReport.TableResult(table.name(), before.rows(), before.checksum(),
                    after.rows(), after.checksum()));
            for (SourceTable.ForeignKey fk : table.foreignKeys()) {
                long orphans = orphans(target, table.name(), fk);
                if (orphans > 0) {
                    report.add(MigrationFinding.critical(Check.FOREIGN_KEY, table.name() + fk.columns(), orphans
                            + " ligne(s) orpheline(s) dans la cible vers " + fk.parentTable()));
                }
            }
        }
    }

    private static long orphans(Connection connection, String table, SourceTable.ForeignKey fk) throws SQLException {
        StringBuilder notNull = new StringBuilder();
        StringBuilder join = new StringBuilder();
        for (int i = 0; i < fk.columns().size(); i++) {
            String child = fk.columns().get(i);
            String parent = fk.parentColumns().get(i);
            SqliteSource.requireIdentifier(child);
            SqliteSource.requireIdentifier(parent);
            notNull.append(i == 0 ? "" : " AND ").append("c.").append(child).append(" IS NOT NULL");
            join.append(i == 0 ? "" : " AND ").append("p.").append(parent).append(" = c.").append(child);
        }
        SqliteSource.requireIdentifier(fk.parentTable());
        String sql = "SELECT COUNT(*) FROM " + table + " c WHERE " + notNull + " AND NOT EXISTS (SELECT 1 FROM "
                + fk.parentTable() + " p WHERE " + join + ")";
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    /** Prochain identifiant attendu par table AUTOINCREMENT : max(source, cible après copie). */
    private static Map<String, Long> plannedSequences(SqliteSource source, List<SourceTable> tables,
                                                      Connection target, SqlDialect dialect) throws SQLException {
        Map<String, Long> sequences = source.sequences();
        Map<String, Long> planned = new LinkedHashMap<>();
        for (SourceTable table : tables) {
            if (table.autoIncrement()) {
                long sourceNext = sequences.getOrDefault(table.name(), 0L) + 1;
                planned.put(table.name(), Math.max(sourceNext, dialect.nextAutoIncrement(target, table.name())));
            }
        }
        return planned;
    }

    private void alignSequences(SqliteSource source, List<SourceTable> tables, Connection target,
                                SqlDialect dialect, MigrationReport report) throws SQLException {
        Map<String, Long> sequences = source.sequences();
        for (SourceTable table : tables) {
            if (!table.autoIncrement()) {
                continue;
            }
            long sourceNext = sequences.getOrDefault(table.name(), 0L) + 1;
            long before = dialect.nextAutoIncrement(target, table.name());
            dialect.raiseAutoIncrement(target, table.name(), sourceNext);
            long after = dialect.nextAutoIncrement(target, table.name());
            report.addSequence(new MigrationReport.SequenceResult(table.name(), sourceNext, after, after != before));
        }
    }

    /** Recomptage après validation de la transaction, sur des lectures neuves. */
    private static void recount(SqliteSource source, List<SourceTable> tables, Connection target,
                                MigrationReport report) throws SQLException {
        for (SourceTable table : tables) {
            long expected = source.rowCount(table.name());
            long actual = count(target, table.name());
            if (expected != actual) {
                report.add(MigrationFinding.critical(Check.DATA, table.name(), "après validation de la transaction : "
                        + actual + " ligne(s) dans la cible pour " + expected + " attendue(s)"));
            }
        }
    }

    // ---- utilitaires ----------------------------------------------------------------------

    private static boolean hasCritical(MigrationReport report) {
        return report.findings().stream().anyMatch(f -> f.severity() == MigrationFinding.Severity.CRITICAL);
    }

    private static long count(Connection connection, String table) throws SQLException {
        SqliteSource.requireIdentifier(table);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private static List<String> targetTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet resultSet = meta.getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (resultSet.next()) {
                tables.add(resultSet.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    private record TargetColumn(String name, String typeName, int size) {
        boolean boundedText() {
            String type = typeName.toUpperCase(Locale.ROOT);
            return (type.contains("CHAR")) && size > 0;
        }
    }

    private static Map<String, TargetColumn> targetColumns(Connection connection, String table) throws SQLException {
        Map<String, TargetColumn> columns = new HashMap<>();
        DatabaseMetaData meta = connection.getMetaData();
        String escape = meta.getSearchStringEscape();
        String pattern = escape == null || escape.isEmpty() ? table
                : table.replace("_", escape + "_").replace("%", escape + "%");
        try (ResultSet resultSet = meta.getColumns(connection.getCatalog(), null, pattern, "%")) {
            while (resultSet.next()) {
                if (!table.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) {
                    continue;
                }
                String name = resultSet.getString("COLUMN_NAME");
                columns.put(name.toLowerCase(Locale.ROOT), new TargetColumn(name,
                        String.valueOf(resultSet.getString("TYPE_NAME")), resultSet.getInt("COLUMN_SIZE")));
            }
        }
        return columns;
    }
}
