package com.lodygames.rpgquest.database;

import java.sql.SQLException;

/**
 * Une migration de schéma n'a pas pu être appliquée (issue #40). Le message identifie la migration
 * fautive ; la base n'avance pas au-delà de la dernière migration réussie. À traiter comme un échec
 * de démarrage : le plugin ne doit pas servir de gameplay sur un schéma incohérent.
 */
public final class SchemaMigrationException extends SQLException {

    public SchemaMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
