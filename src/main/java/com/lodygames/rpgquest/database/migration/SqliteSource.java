package com.lodygames.rpgquest.database.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Base SQLite <strong>source</strong> d'une migration (issue #42), ouverte en
 * <strong>lecture seule</strong>.
 *
 * <p>Trois verrous empêchent toute écriture : ouverture {@code SQLITE_OPEN_READONLY}
 * ({@code open_mode=1}), {@code PRAGMA query_only = ON}, et l'empreinte SHA-256 du fichier,
 * calculée avant et après l'exécution par {@link SqliteMigrationTool} — une différence rend la
 * migration {@code FAILED}.</p>
 *
 * <p>Le schéma est lu dans {@code sqlite_master} et les {@code PRAGMA} : aucune table n'est codée
 * en dur, la migration suit la base réelle.</p>
 */
public final class SqliteSource implements AutoCloseable {

    /** Identifiant SQL simple : seul format accepté pour un nom de table ou de colonne. */
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final Path file;
    private final Connection connection;

    private SqliteSource(Path file, Connection connection) {
        this.file = file;
        this.connection = connection;
    }

    /** Ouvre {@code file} en lecture seule. Échoue si le fichier n'existe pas (jamais de création). */
    public static SqliteSource openReadOnly(Path file) throws SQLException {
        if (!Files.isRegularFile(file)) {
            throw new SQLException("Base SQLite source introuvable : " + file);
        }
        Properties properties = new Properties();
        properties.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY, sans SQLITE_OPEN_CREATE
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + file.toAbsolutePath(), properties);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return new SqliteSource(file, connection);
    }

    public Connection connection() {
        return connection;
    }

    public Path file() {
        return file;
    }

    /** Empreinte SHA-256 du fichier (preuve que la source n'a pas été modifiée). */
    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /**
     * Journal SQLite encore présent à côté du fichier ({@code -journal} / {@code -wal}) : signe que
     * le serveur n'a pas été arrêté proprement ou que la copie a été prise à chaud.
     */
    public static List<Path> pendingJournals(Path file) throws IOException {
        List<Path> journals = new ArrayList<>();
        for (String suffix : List.of("-journal", "-wal")) {
            Path journal = file.resolveSibling(file.getFileName() + suffix);
            if (Files.exists(journal) && Files.size(journal) > 0) {
                journals.add(journal);
            }
        }
        return journals;
    }

    public int userVersion() throws SQLException {
        return queryInt("PRAGMA user_version");
    }

    /** Résultat de {@code PRAGMA integrity_check} ({@code ok} si la base est saine). */
    public List<String> integrityCheck() throws SQLException {
        return queryStrings("PRAGMA integrity_check");
    }

    /** Violations de clé étrangère, au format {@code table(rowid) → parent}. */
    public List<String> foreignKeyViolations() throws SQLException {
        List<String> violations = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
            while (resultSet.next()) {
                violations.add(resultSet.getString(1) + "(rowid " + resultSet.getLong(2) + ") → "
                        + resultSet.getString(3));
            }
        }
        return violations;
    }

    /** Noms des tables utilisateur (hors tables internes {@code sqlite_*}), triés. */
    public List<String> tableNames() throws SQLException {
        return queryStrings("SELECT name FROM sqlite_master WHERE type = 'table' "
                + "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name");
    }

    /** Lit la structure complète d'une table (colonnes, clé primaire, clés étrangères, unicités). */
    public SourceTable describe(String table) throws SQLException {
        requireIdentifier(table);
        List<SourceTable.Column> columns = new ArrayList<>();
        Map<Integer, String> primaryKey = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                columns.add(new SourceTable.Column(name, resultSet.getString("type"),
                        resultSet.getInt("notnull") == 1));
                int pk = resultSet.getInt("pk");
                if (pk > 0) {
                    primaryKey.put(pk, name);
                }
            }
        }

        Map<Integer, List<String[]>> fkParts = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            while (resultSet.next()) {
                fkParts.computeIfAbsent(resultSet.getInt("id"), id -> new ArrayList<>()).add(new String[] {
                        resultSet.getString("from"), resultSet.getString("table"), resultSet.getString("to")});
            }
        }
        List<SourceTable.ForeignKey> foreignKeys = new ArrayList<>();
        for (List<String[]> parts : fkParts.values()) {
            List<String> from = parts.stream().map(p -> p[0]).toList();
            List<String> to = parts.stream().map(p -> p[2]).toList();
            foreignKeys.add(new SourceTable.ForeignKey(from, parts.get(0)[1], to));
        }

        List<List<String>> uniqueKeys = new ArrayList<>();
        List<String> uniqueIndexes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
            while (resultSet.next()) {
                if (resultSet.getInt("unique") == 1 && !"pk".equals(resultSet.getString("origin"))) {
                    uniqueIndexes.add(resultSet.getString("name"));
                }
            }
        }
        for (String index : uniqueIndexes) {
            List<String> indexColumns = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name FROM pragma_index_info(?) ORDER BY seqno")) {
                statement.setString(1, index);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        indexColumns.add(resultSet.getString(1));
                    }
                }
            }
            uniqueKeys.add(indexColumns);
        }

        boolean autoIncrement = false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next() && resultSet.getString(1) != null) {
                    autoIncrement = resultSet.getString(1).toUpperCase(java.util.Locale.ROOT)
                            .contains("AUTOINCREMENT");
                }
            }
        }
        return new SourceTable(table, columns, new ArrayList<>(primaryKey.values()), foreignKeys,
                uniqueKeys, autoIncrement);
    }

    public long rowCount(String table) throws SQLException {
        requireIdentifier(table);
        return queryLong("SELECT COUNT(*) FROM " + table);
    }

    /** {@code sqlite_sequence} : dernière valeur attribuée par table {@code AUTOINCREMENT}. */
    public Map<String, Long> sequences() throws SQLException {
        Map<String, Long> sequences = new LinkedHashMap<>();
        if (queryStrings("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'")
                .isEmpty()) {
            return sequences;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT name, seq FROM sqlite_sequence ORDER BY name")) {
            while (resultSet.next()) {
                sequences.put(resultSet.getString(1), resultSet.getLong(2));
            }
        }
        return sequences;
    }

    /**
     * Répartition des types de stockage réels ({@code typeof}) d'une colonne. SQLite accepte
     * n'importe quelle valeur dans n'importe quelle colonne ; MariaDB en mode strict, non.
     */
    public Map<String, Long> storageClasses(String table, String column) throws SQLException {
        requireIdentifier(table);
        requireIdentifier(column);
        Map<String, Long> classes = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT typeof(" + column + "), COUNT(*) FROM " + table + " GROUP BY 1")) {
            while (resultSet.next()) {
                classes.put(resultSet.getString(1), resultSet.getLong(2));
            }
        }
        return classes;
    }

    /** Longueur maximale (en caractères) des valeurs texte d'une colonne ; 0 si aucune. */
    public long maxTextLength(String table, String column) throws SQLException {
        requireIdentifier(table);
        requireIdentifier(column);
        return queryLong("SELECT COALESCE(MAX(length(" + column + ")), 0) FROM " + table
                + " WHERE typeof(" + column + ") = 'text'");
    }

    /**
     * Nombre de groupes de valeurs qui deviendraient des <strong>doublons</strong> sous une
     * collation « PAD SPACE » (MariaDB {@code utf8mb4_bin} ignore les espaces finaux en
     * comparaison) : {@code 'abc'} et {@code 'abc '} sont distincts en SQLite mais entrent en
     * conflit sur une clé unique MariaDB.
     */
    public long padSpaceCollisions(String table, List<String> keyColumns) throws SQLException {
        requireIdentifier(table);
        keyColumns.forEach(SqliteSource::requireIdentifier);
        String trimmed = String.join(", ", keyColumns.stream()
                .map(c -> "CASE WHEN typeof(" + c + ") = 'text' THEN rtrim(" + c + ", ' ') ELSE " + c + " END")
                .toList());
        return queryLong("SELECT COUNT(*) FROM (SELECT 1 FROM " + table + " GROUP BY " + trimmed
                + " HAVING COUNT(*) > 1)");
    }

    /** Nombre de valeurs non nulles et non vides d'une colonne qui ne sont pas des UUID valides. */
    public long invalidUuids(String table, String column) throws SQLException {
        requireIdentifier(table);
        requireIdentifier(column);
        long invalid = 0;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT " + column + " FROM " + table + " WHERE " + column + " IS NOT NULL AND "
                             + column + " <> ''")) {
            while (resultSet.next()) {
                if (!UUID_PATTERN.matcher(String.valueOf(resultSet.getObject(1))).matches()) {
                    invalid++;
                }
            }
        }
        return invalid;
    }

    static void requireIdentifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("identifiant SQL non pris en charge : « " + name + " »");
        }
    }

    private int queryInt(String sql) throws SQLException {
        return (int) queryLong(sql);
    }

    private long queryLong(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private List<String> queryStrings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
