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

    /**
     * Ordres DDL qui élargissent en texte non borné les colonnes de texte libre qu'une version
     * antérieure du dialecte avait créées trop courtes (issue #354, migration V35). Idempotents.
     * Vide pour SQLite, dont {@code TEXT} n'a jamais de longueur maximale.
     */
    default List<String> freeTextWidenings() {
        return List.of();
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

    /**
     * Prochaine valeur que le moteur attribuera à la colonne {@code AUTOINCREMENT} de
     * {@code table} (issue #42 : un identifiant « jamais réutilisé » doit le rester après une
     * migration de moteur). SQLite : {@code sqlite_sequence.seq + 1} ; MariaDB :
     * {@code information_schema.tables.AUTO_INCREMENT}. {@code 1} si rien n'a encore été attribué.
     */
    long nextAutoIncrement(Connection connection, String table) throws SQLException;

    /**
     * Garantit que la prochaine valeur auto-incrémentée de {@code table} vaut au moins
     * {@code next} — jamais de recul. MariaDB : {@code ALTER TABLE … AUTO_INCREMENT}, qui
     * <strong>valide implicitement</strong> la transaction en cours : à appeler hors transaction.
     *
     * @param table nom de table RPGQuest (identifiant contrôlé, jamais une saisie libre)
     */
    void raiseAutoIncrement(Connection connection, String table, long next) throws SQLException;

    // ---- helpers communs ------------------------------------------------------------------

    static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    static String join(List<String> columns) {
        return String.join(", ", columns);
    }
}
