package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Détail d'infrastructure : <strong>comment</strong> RPGQuest obtient des connexions et
 * <strong>quel dialecte / historique de migrations</strong> s'applique (issue #40).
 *
 * <p>{@link DatabaseManager} ne connaît que cette abstraction : il n'ouvre jamais une URL JDBC en
 * dur, n'exécute jamais un {@code PRAGMA} spécifique, ne choisit jamais un dialecte. Le code
 * métier, lui, ne voit même pas cette interface — il passe par les repositories et
 * {@link DatabaseManager#execute}.</p>
 *
 * <p>Implémentations : {@link SqliteDatabaseEngine} (câblée) et {@link MySqlDatabaseEngine}
 * (reconnue, corps réel = issue #41).</p>
 */
public interface DatabaseEngine {

    DatabaseType type();

    /** Description sûre pour les logs — <strong>jamais</strong> de mot de passe ni d'URL avec secret. */
    String describe();

    /**
     * Ouvre une connexion prête à l'emploi. Pour SQLite : la connexion unique du fichier. Pour
     * MySQL (#41) : une connexion empruntée à un pool. Doit créer les répertoires nécessaires.
     */
    Connection openConnection() throws SQLException;

    /** Applique les réglages de session propres au moteur (ex. {@code PRAGMA foreign_keys = ON}). */
    void configureSession(Connection connection) throws SQLException;

    /** Différences SQL du moteur (upsert, existence de colonne, identité auto-incrémentée…). */
    SqlDialect dialect();

    /** Mécanisme de suivi de la version de schéma pour ce moteur. */
    SchemaHistory schemaHistory();

    /** Libère les ressources du moteur (pool de connexions…). Sans objet pour SQLite. */
    default void close() {
        // no-op par défaut
    }
}
