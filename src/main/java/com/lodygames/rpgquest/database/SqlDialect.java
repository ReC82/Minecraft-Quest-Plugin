package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Encapsule les <strong>différences SQL réelles</strong> entre moteurs (issue #40) : le reste de la
 * couche de persistance et le mécanisme de migrations parlent à cette abstraction, jamais à un
 * moteur concret via des {@code if (type == MYSQL) … else …} dispersés.
 *
 * <p>Deux implémentations : {@link SqliteDialect} (câblée aujourd'hui) et {@link MySqlDialect}
 * (prête pour #41). Les repositories métier existants portent encore leur SQL SQLite inline
 * (recensé dans le rapport #40) ; #41 les fera passer par ces helpers avant d'activer MySQL.</p>
 */
public interface SqlDialect {

    /** Nom lisible ({@code sqlite} / {@code mysql}). */
    String name();

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
