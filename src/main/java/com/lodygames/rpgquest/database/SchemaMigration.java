package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Une étape de migration de schéma numérotée (issue #40). Les étapes sont appliquées dans l'ordre
 * strict des {@link #version()} par {@link SchemaMigrationRunner}, exactement une fois.
 *
 * @param version numéro strictement croissant (1, 2, 3, …)
 * @param name    libellé court pour les logs et l'historique
 * @param step    l'action DDL/DML ; reçoit la connexion et le {@link SqlDialect} du moteur cible
 */
public record SchemaMigration(int version, String name, Step step) {

    public SchemaMigration {
        if (version < 1) {
            throw new IllegalArgumentException("version de migration invalide : " + version);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("nom de migration obligatoire (V" + version + ")");
        }
        if (step == null) {
            throw new IllegalArgumentException("action de migration obligatoire (V" + version + ")");
        }
    }

    @FunctionalInterface
    public interface Step {
        void apply(Connection connection, SqlDialect dialect) throws SQLException;
    }
}
