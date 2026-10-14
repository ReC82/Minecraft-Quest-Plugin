package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Dialecte SQLite — reproduit exactement la syntaxe utilisée aujourd'hui par les migrations et les
 * repositories ({@code INSERT OR IGNORE}, {@code ON CONFLICT … DO UPDATE SET … excluded.*},
 * {@code INTEGER PRIMARY KEY AUTOINCREMENT}, {@code PRAGMA table_info}).
 */
public final class SqliteDialect implements SqlDialect {

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equals(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public String upsert(String table, List<String> insertColumns, List<String> conflictColumns,
                         List<String> updateColumns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                .append(" (").append(SqlDialect.join(insertColumns)).append(") VALUES (")
                .append(SqlDialect.placeholders(insertColumns.size())).append(")")
                .append(" ON CONFLICT (").append(SqlDialect.join(conflictColumns)).append(") DO UPDATE SET ");
        for (int i = 0; i < updateColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(updateColumns.get(i)).append(" = excluded.").append(updateColumns.get(i));
        }
        return sql.toString();
    }

    @Override
    public String insertOrIgnore(String table, List<String> columns) {
        return "INSERT OR IGNORE INTO " + table + " (" + SqlDialect.join(columns) + ") VALUES ("
                + SqlDialect.placeholders(columns.size()) + ")";
    }

    @Override
    public String autoIncrementPrimaryKey(String name) {
        return name + " INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    public long nextAutoIncrement(Connection connection, String table) throws SQLException {
        if (!sequenceTableExists(connection)) {
            return 1L;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT seq FROM sqlite_sequence WHERE name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) + 1 : 1L;
            }
        }
    }

    @Override
    public void raiseAutoIncrement(Connection connection, String table, long next) throws SQLException {
        if (nextAutoIncrement(connection, table) >= next) {
            return;
        }
        // sqlite_sequence n'existe qu'après la création d'une table AUTOINCREMENT : toute table
        // RPGQuest concernée en a déjà créé une, sinon il n'y a rien à aligner.
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE sqlite_sequence SET seq = ? WHERE name = ?")) {
            update.setLong(1, next - 1);
            update.setString(2, table);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sqlite_sequence (name, seq) VALUES (?, ?)")) {
            insert.setString(1, table);
            insert.setLong(2, next - 1);
            insert.executeUpdate();
        }
    }

    private static boolean sequenceTableExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'")) {
            return resultSet.next();
        }
    }
}
