package com.lodygames.rpgquest.database;

import java.sql.Connection;
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
}
