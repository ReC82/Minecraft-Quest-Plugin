package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Suivi de la <strong>version de schéma appliquée</strong> à une base (issue #40). Abstrait pour
 * que le mécanisme diffère par moteur sans conditionnelle dans le runner de migrations :
 *
 * <ul>
 *   <li>{@link PragmaUserVersionHistory} — SQLite natif ({@code PRAGMA user_version}), comportement
 *       <strong>strictement inchangé</strong> par rapport à aujourd'hui (les bases {@code data.db}
 *       existantes restent reconnues telles quelles) ;</li>
 *   <li>{@link MigrationTableHistory} — table {@code rpgquest_schema_migrations} portable, pour
 *       MySQL/MariaDB (#41) et tout moteur sans registre de version natif.</li>
 * </ul>
 */
public interface SchemaHistory {

    /** Crée le support de suivi si nécessaire (no-op pour {@code PRAGMA user_version}). */
    void ensureInitialised(Connection connection) throws SQLException;

    /** Version actuellement appliquée ({@code 0} sur une base neuve). */
    int currentVersion(Connection connection) throws SQLException;

    /** Enregistre {@code version} comme appliquée. Appelé après chaque migration réussie. */
    void recordApplied(Connection connection, int version, String name) throws SQLException;
}
