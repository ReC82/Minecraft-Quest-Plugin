package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Détail d'infrastructure : <strong>comment</strong> RPGQuest obtient des connexions et
 * <strong>quel dialecte / historique de migrations</strong> s'applique (issues #40 / #41).
 *
 * <p>{@link DatabaseManager} ne connaît que cette abstraction : il n'ouvre jamais une URL JDBC en
 * dur, n'exécute jamais un {@code PRAGMA} spécifique, ne choisit jamais un dialecte, ne connaît pas
 * le pool. Le code métier ne voit même pas cette interface — il passe par les repositories et
 * {@link DatabaseManager#execute}.</p>
 *
 * <p>Modèle de connexion : {@link #borrow()} / {@link #release(Connection)} par unité de travail.
 * Pour SQLite c'est <strong>toujours la même</strong> connexion unique (comportement historique) ;
 * pour MariaDB c'est une connexion empruntée à un pool HikariCP puis rendue. Comme
 * {@link DatabaseManager} sérialise tout sur un thread unique, une seule connexion est active à la
 * fois quel que soit le moteur — la garantie d'ordre FIFO des repositories est préservée.</p>
 *
 * <p>Implémentations : {@link SqliteDatabaseEngine}, {@link MySqlDatabaseEngine}.</p>
 */
public interface DatabaseEngine {

    DatabaseType type();

    /** Description sûre pour les logs — <strong>jamais</strong> de mot de passe ni d'URL avec secret. */
    String describe();

    /**
     * Ouvre les ressources du moteur (fichier SQLite / pool de connexions) et vérifie la
     * connectivité. Doit échouer par une {@link SQLException} claire si la base est injoignable ou
     * mal configurée — jamais de boucle de reconnexion agressive.
     */
    void start() throws SQLException;

    /**
     * Emprunte une connexion prête à l'emploi (session déjà configurée). SQLite : la connexion
     * unique. MariaDB : une connexion du pool.
     */
    Connection borrow() throws SQLException;

    /**
     * Rend une connexion empruntée par {@link #borrow()}. SQLite : sans effet. MariaDB : rendue au
     * pool ({@code close()}), état de transaction remis à zéro.
     */
    void release(Connection connection);

    /** Différences SQL du moteur (upsert, insert-ignore, DDL, existence de colonne…). */
    SqlDialect dialect();

    /** Mécanisme de suivi de la version de schéma pour ce moteur. */
    SchemaHistory schemaHistory();

    /** Libère toutes les ressources (connexion / pool). Réservé à l'arrêt du plugin. */
    void close();
}
