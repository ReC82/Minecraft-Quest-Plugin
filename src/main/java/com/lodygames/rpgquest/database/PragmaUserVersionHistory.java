package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Historique de schéma via {@code PRAGMA user_version} de SQLite — le mécanisme utilisé depuis
 * l'origine du projet. Aucun changement de comportement : une base {@code data.db} déjà à la
 * version 17 reste vue comme telle, aucune migration n'est rejouée.
 *
 * <p>Il n'y a pas de table dédiée : {@code user_version} est un entier stocké dans l'en-tête du
 * fichier SQLite. {@link #recordApplied} écrit simplement la nouvelle valeur ; le nom de la
 * migration n'est pas conservé (limitation assumée de ce backend, sans incidence fonctionnelle).</p>
 */
public final class PragmaUserVersionHistory implements SchemaHistory {

    @Override
    public void ensureInitialised(Connection connection) {
        // rien à faire : user_version existe toujours dans l'en-tête du fichier SQLite
    }

    @Override
    public int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    @Override
    public void recordApplied(Connection connection, int version, String name) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // PRAGMA n'accepte pas de paramètre lié ; version est un int contrôlé par le runner.
            statement.execute("PRAGMA user_version = " + version);
        }
    }
}
