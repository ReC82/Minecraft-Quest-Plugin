package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Encapsule les <strong>différences SQL réelles</strong> entre moteurs (issue #40) : le reste de la
 * couche de persistance et le mécanisme de migrations parlent à cette abstraction, jamais à un
 * moteur concret via des {@code if (type == MYSQL) … else …} dispersés.
 *
 * <p>Deux implémentations : {@link SqliteDialect} (câblée) et {@link MySqlDialect} (branchée par
 * #41). Les repositories écrivent leur SQL en <strong>SQLite canonique</strong> et le passent par
 * {@link #rewrite(String)} ; les migrations écrivent leur DDL en SQLite canonique et le passent par
 * {@link #ddl(String)}. Pour {@link SqliteDialect}, les deux sont l'<strong>identité</strong> —
 * aucun changement de comportement possible sur SQLite.</p>
 */
public interface SqlDialect {

    /** Nom lisible ({@code sqlite} / {@code mariadb}). */
    String name();

    /**
     * Adapte une requête DML écrite en <strong>SQLite canonique</strong> au moteur cible :
     * {@code INSERT OR IGNORE} → {@code INSERT IGNORE} ; {@code ON CONFLICT (…) DO UPDATE SET
     * col = excluded.col} → {@code ON DUPLICATE KEY UPDATE col = VALUES(col)}. Identité pour SQLite.
     */
    default String rewrite(String canonicalSqliteSql) {
        return canonicalSqliteSql;
    }

    /**
     * Adapte une instruction DDL écrite en <strong>SQLite canonique</strong> au moteur cible
     * (types {@code TEXT}/{@code INTEGER}/{@code BLOB}, {@code AUTOINCREMENT}, moteur InnoDB,
     * index). Identité pour SQLite.
     */
    default String ddl(String canonicalSqliteDdl) {
        return canonicalSqliteDdl;
    }

    /** Requête triviale de vérification de vie de la connexion. */
    default String healthQuery() {
        return "SELECT 1";
    }

    /**
     * Vrai si la colonne {@code column} existe dans {@code table}. Utilisé par les migrations qui
     * font un {@code ALTER TABLE ADD COLUMN} idempotent.
     */
    boolean columnExists(Connection connection, String table, String column) throws SQLException;

    /**
     * Construit un ordre d'<em>upsert</em> : insère une ligne, ou met à jour {@code updateColumns}
     * si la clé {@code conflictColumns} entre déjà en conflit.
     *
     * @param table          table cible
     * @param insertColumns  colonnes fournies à l'INSERT (dans l'ordre des {@code ?})
     * @param conflictColumns colonnes formant la contrainte d'unicité / clé primaire
     * @param updateColumns  colonnes à réécrire depuis la ligne proposée en cas de conflit
     */
    String upsert(String table, List<String> insertColumns, List<String> conflictColumns, List<String> updateColumns);

    /** Insère une ligne, ou ne fait rien si la clé existe déjà (aucune erreur). */
    String insertOrIgnore(String table, List<String> columns);

    /**
     * Définition d'une colonne identité auto-incrémentée servant de clé primaire.
     * SQLite : {@code <name> INTEGER PRIMARY KEY AUTOINCREMENT}. MySQL : {@code BIGINT … AUTO_INCREMENT}.
     */
    String autoIncrementPrimaryKey(String name);

    // ---- helpers communs ------------------------------------------------------------------

    static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    static String join(List<String> columns) {
        return String.join(", ", columns);
    }
}
