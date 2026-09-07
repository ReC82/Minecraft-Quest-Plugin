package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Dialecte MySQL/MariaDB (issue #40 : modélisé et testé ; branché par #41).
 *
 * <ul>
 *   <li>upsert : {@code INSERT … ON DUPLICATE KEY UPDATE col = VALUES(col)} (la contrainte
 *       d'unicité doit exister sur {@code conflictColumns} — c'est le cas des tables RPGQuest,
 *       toutes à clé primaire composite) ;</li>
 *   <li>insert-or-ignore : {@code INSERT IGNORE INTO …} ;</li>
 *   <li>identité : {@code BIGINT PRIMARY KEY AUTO_INCREMENT} ;</li>
 *   <li>existence de colonne : {@code information_schema.columns}.</li>
 * </ul>
 *
 * <p>La traduction des types SQLite ({@code TEXT}, {@code BLOB}) vers des types MySQL indexables
 * ({@code VARCHAR(n)}, {@code LONGBLOB}) est faite <strong>migration par migration</strong> dans
 * #41 (voir le rapport #40, section « Travail restant pour #41 »).</p>
 */
public final class MySqlDialect implements SqlDialect {

    @Override
    public String name() {
        return "mysql";
    }

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
        return name + " BIGINT PRIMARY KEY AUTO_INCREMENT";
    }
}
