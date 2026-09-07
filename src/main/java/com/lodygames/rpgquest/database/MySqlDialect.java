package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialecte MySQL/MariaDB (issue #41). Deux responsabilités :
 *
 * <ol>
 *   <li>{@link #rewrite(String)} — adapte le <strong>DML</strong> SQLite canonique des repositories :
 *       {@code INSERT OR IGNORE} → {@code INSERT IGNORE} ;
 *       {@code ON CONFLICT (…) DO UPDATE SET c = excluded.c} → {@code ON DUPLICATE KEY UPDATE
 *       c = VALUES(c)} (forme supportée par MariaDB 10.11) ;</li>
 *   <li>{@link #ddl(String)} — adapte le <strong>DDL</strong> SQLite canonique des migrations :
 *       {@code TEXT} clé/index/NOT NULL → {@code VARCHAR(191)} ; {@code TEXT} nullable → {@code TEXT} ;
 *       {@code INTEGER} → {@code BIGINT} (SQLite stocke les entiers en 64 bits, le code fait des
 *       {@code getLong}) ; {@code INTEGER PRIMARY KEY AUTOINCREMENT} → {@code BIGINT NOT NULL
 *       AUTO_INCREMENT PRIMARY KEY} ; {@code BLOB} → {@code LONGBLOB} ; ajout du moteur InnoDB
 *       (clés étrangères) + charset {@code utf8mb4} + collation {@code utf8mb4_bin}
 *       (comparaisons binaires, comme SQLite) ; {@code CREATE INDEX IF NOT EXISTS} →
 *       {@code ALTER TABLE … ADD INDEX IF NOT EXISTS}.</li>
 * </ol>
 *
 * <p>Le SQL/DDL de référence reste écrit en SQLite dans les repositories et les migrations : cette
 * classe est le seul endroit qui connaît les différences MariaDB.</p>
 */
public final class MySqlDialect implements SqlDialect {

    private static final String TABLE_SUFFIX = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
    private static final String KEYED_TEXT_TYPE = "VARCHAR(191)";

    private static final Pattern INSERT_OR_IGNORE = Pattern.compile("(?i)INSERT\\s+OR\\s+IGNORE\\s+INTO");
    private static final Pattern ON_CONFLICT_DO_UPDATE =
            Pattern.compile("(?is)ON\\s+CONFLICT\\s*\\([^)]*\\)\\s*DO\\s+UPDATE\\s+SET");
    private static final Pattern EXCLUDED_REF =
            Pattern.compile("(?i)excluded\\.([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+(\\S+)\\s+ON\\s+(\\S+)\\s*\\((.*)\\)\\s*$");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+(\\S+)\\s*\\((.*)\\)\\s*$");

    @Override
    public String name() {
        return "mysql";
    }

    // ---- DML (repositories) ------------------------------------------------------------------

    @Override
    public String rewrite(String canonicalSqliteSql) {
        String sql = INSERT_OR_IGNORE.matcher(canonicalSqliteSql).replaceAll("INSERT IGNORE INTO");
        sql = ON_CONFLICT_DO_UPDATE.matcher(sql).replaceAll("ON DUPLICATE KEY UPDATE");
        sql = EXCLUDED_REF.matcher(sql).replaceAll("VALUES($1)");
        return sql;
    }

    // ---- DDL (migrations) -------------------------------------------------------------------

    @Override
    public String ddl(String canonicalSqliteDdl) {
        String trimmed = canonicalSqliteDdl.trim();
        String flat = trimmed.replaceAll("\\s+", " ");

        Matcher table = CREATE_TABLE.matcher(flat);
        if (table.matches()) {
            return translateCreateTable(table.group(1), table.group(2));
        }
        Matcher index = CREATE_INDEX.matcher(flat);
        if (index.matches()) {
            String unique = index.group(1) == null ? "" : "UNIQUE ";
            return "ALTER TABLE " + index.group(3) + " ADD " + unique + "INDEX IF NOT EXISTS "
                    + index.group(2) + " (" + index.group(4).trim() + ")";
        }
        if (flat.regionMatches(true, 0, "ALTER TABLE", 0, 11)) {
            return flat.replaceAll("(?i)\\bINTEGER\\b", "BIGINT");
        }
        return canonicalSqliteDdl;
    }

    private String translateCreateTable(String tableName, String rawBody) {
        List<String> parts = splitTopLevel(rawBody);

        Set<String> keyed = new LinkedHashSet<>();
        for (String part : parts) {
            String p = part.trim();
            String upper = p.toUpperCase(java.util.Locale.ROOT);
            if (upper.startsWith("PRIMARY KEY") || upper.startsWith("FOREIGN KEY")) {
                keyed.addAll(namesInFirstParens(p));
            } else if (upper.contains(" PRIMARY KEY")) {
                keyed.add(firstToken(p));
            }
        }

        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String p = part.trim();
            String upper = p.toUpperCase(java.util.Locale.ROOT);
            if (upper.startsWith("PRIMARY KEY") || upper.startsWith("FOREIGN KEY")
                    || upper.startsWith("UNIQUE") || upper.startsWith("CONSTRAINT") || upper.startsWith("CHECK")) {
                out.add(p);
                continue;
            }
            out.add(translateColumn(p, keyed));
        }

        return "CREATE TABLE IF NOT EXISTS " + tableName + " (\n    "
                + String.join(",\n    ", out) + "\n)" + TABLE_SUFFIX;
    }

    private String translateColumn(String columnDef, Set<String> keyed) {
        String[] tokens = columnDef.split("\\s+", 3);
        String name = tokens[0];
        String type = tokens.length > 1 ? tokens[1].toUpperCase(java.util.Locale.ROOT) : "";
        String rest = tokens.length > 2 ? tokens[2] : "";
        String restUpper = rest.toUpperCase(java.util.Locale.ROOT);

        return switch (type) {
            case "INTEGER" -> {
                if (restUpper.contains("PRIMARY KEY") && restUpper.contains("AUTOINCREMENT")) {
                    yield name + " BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
                }
                yield trim(name + " BIGINT " + rest);
            }
            case "BLOB" -> trim(name + " LONGBLOB " + rest);
            case "TEXT" -> {
                boolean asVarchar = restUpper.contains("NOT NULL")
                        || restUpper.contains("PRIMARY KEY")
                        || keyed.contains(name);
                yield trim(name + " " + (asVarchar ? KEYED_TEXT_TYPE : "TEXT") + " " + rest);
            }
            default -> columnDef; // laisser tel quel (aucun cas dans le schéma RPGQuest)
        };
    }

    // ---- schéma / colonnes ---------------------------------------------------------------

    @Override
    public boolean columnExists(Connection connection, String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Override
    public String upsert(String table, List<String> insertColumns, List<String> conflictColumns,
                         List<String> updateColumns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                .append(" (").append(SqlDialect.join(insertColumns)).append(") VALUES (")
                .append(SqlDialect.placeholders(insertColumns.size())).append(")")
                .append(" ON DUPLICATE KEY UPDATE ");
        for (int i = 0; i < updateColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(updateColumns.get(i)).append(" = VALUES(").append(updateColumns.get(i)).append(")");
        }
        return sql.toString();
    }

    @Override
    public String insertOrIgnore(String table, List<String> columns) {
        return "INSERT IGNORE INTO " + table + " (" + SqlDialect.join(columns) + ") VALUES ("
                + SqlDialect.placeholders(columns.size()) + ")";
    }

    @Override
    public String autoIncrementPrimaryKey(String name) {
        return name + " BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
    }

    // ---- utilitaires de parsing DDL -------------------------------------------------------

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    private static List<String> namesInFirstParens(String clause) {
        int open = clause.indexOf('(');
        int close = clause.indexOf(')', open);
        if (open < 0 || close < 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String raw : clause.substring(open + 1, close).split(",")) {
            String n = raw.trim();
            if (!n.isEmpty()) {
                names.add(n);
            }
        }
        return names;
    }

    private static String firstToken(String s) {
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    private static String trim(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }
}
