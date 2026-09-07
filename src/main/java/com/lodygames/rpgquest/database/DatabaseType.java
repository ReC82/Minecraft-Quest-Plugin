package com.lodygames.rpgquest.database;

import java.util.Locale;

/**
 * Moteur de base de données supporté par la couche de persistance (issue #40).
 *
 * <p>{@link #SQLITE} est le seul moteur réellement câblé aujourd'hui. {@link #MYSQL} est
 * <strong>reconnu par la configuration</strong> et modélisé de bout en bout (paramètres, dialecte
 * SQL, historique de migrations portable), mais son moteur d'accès échoue proprement au démarrage :
 * l'implémentation réelle (driver, pool de connexions) est l'objet de l'issue #41.</p>
 */
public enum DatabaseType {

    SQLITE,
    MYSQL;

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Analyse une valeur de configuration ({@code sqlite} / {@code mysql} / {@code mariadb}).
     * MariaDB est un alias de MySQL (même protocole/dialecte pour nos besoins).
     */
    public static DatabaseType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SQLITE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "sqlite" -> SQLITE;
            case "mysql", "mariadb" -> MYSQL;
            default -> throw new IllegalArgumentException(
                    "moteur de base de données inconnu : « " + raw + " » (valides : sqlite, mysql/mariadb)");
        };
    }
}
