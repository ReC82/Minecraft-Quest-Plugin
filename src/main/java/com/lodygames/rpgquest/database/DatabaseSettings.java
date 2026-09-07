package com.lodygames.rpgquest.database;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configuration <strong>abstraite</strong> de la persistance RPGQuest (issue #40) : quel moteur, et
 * ses paramètres. Produite par {@code ConfigValidator} à partir de la section {@code database:} de
 * {@code config.yml}. Immuable, sans dépendance Bukkit.
 *
 * <p>Le mot de passe MySQL n'est <strong>jamais</strong> lu depuis {@code config.yml} : seul le
 * <em>nom</em> d'une variable d'environnement est configuré ({@link MySqlSettings#passwordEnv()}),
 * résolu à l'ouverture de la connexion. Aucun secret dans le dépôt, les logs ou les rapports.</p>
 *
 * @param type   moteur sélectionné
 * @param sqlite paramètres SQLite (toujours présents : SQLite reste le défaut et le mode test)
 * @param mysql  paramètres MySQL/MariaDB (utilisés uniquement si {@code type == MYSQL})
 */
public record DatabaseSettings(DatabaseType type, SqliteSettings sqlite, MySqlSettings mysql) {

    public DatabaseSettings {
        if (type == null) {
            type = DatabaseType.SQLITE;
        }
        if (sqlite == null) {
            sqlite = SqliteSettings.defaults();
        }
        if (mysql == null) {
            mysql = MySqlSettings.defaults();
        }
    }

    /** Défaut historique : SQLite, fichier {@code data.db} dans le dossier du plugin. */
    public static DatabaseSettings sqliteDefault() {
        return new DatabaseSettings(DatabaseType.SQLITE, SqliteSettings.defaults(), MySqlSettings.defaults());
    }

    public static DatabaseSettings sqlite(String file) {
        return new DatabaseSettings(DatabaseType.SQLITE, new SqliteSettings(file), MySqlSettings.defaults());
    }

    /** Description sûre pour les logs — <strong>jamais</strong> de secret. */
    public String describe() {
        return switch (type) {
            case SQLITE -> "sqlite (" + sqlite.file() + ")";
            case MYSQL -> "mysql " + mysql.username() + "@" + mysql.host() + ":" + mysql.port()
                    + "/" + mysql.database();
        };
    }

    /** @param file nom de fichier SQLite (simple nom, jamais un chemin) */
    public record SqliteSettings(String file) {
        public SqliteSettings {
            if (file == null || file.isBlank()) {
                file = "data.db";
            }
        }

        public static SqliteSettings defaults() {
            return new SqliteSettings("data.db");
        }
    }

    /**
     * @param host                hôte du serveur MySQL/MariaDB
     * @param port                port TCP (1..65535)
     * @param database            nom de la base
     * @param username            utilisateur
     * @param passwordEnv         <strong>nom</strong> de la variable d'environnement contenant le mot de passe
     * @param pool                paramètres du pool de connexions (consommés par #41)
     */
    public record MySqlSettings(
            String host, int port, String database, String username, String passwordEnv, PoolSettings pool) {

        public MySqlSettings {
            if (host == null || host.isBlank()) {
                host = "localhost";
            }
            if (port <= 0 || port > 65535) {
                port = 3306;
            }
            if (database == null || database.isBlank()) {
                database = "rpgquest";
            }
            if (username == null || username.isBlank()) {
                username = "rpgquest";
            }
            if (passwordEnv == null || passwordEnv.isBlank()) {
                passwordEnv = "RPGQUEST_DB_PASSWORD";
            }
            if (pool == null) {
                pool = PoolSettings.defaults();
            }
        }

        public static MySqlSettings defaults() {
            return new MySqlSettings("localhost", 3306, "rpgquest", "rpgquest", "RPGQUEST_DB_PASSWORD",
                    PoolSettings.defaults());
        }

        /** Résout le mot de passe depuis l'environnement fourni. Vide si la variable n'est pas définie. */
        public Optional<String> resolvePassword(UnaryOperator<String> env) {
            String value = env.apply(passwordEnv);
            return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
        }

        /** URL JDBC (sans identifiants). Utilisée par #41 ; ici pour la journalisation/diagnostic. */
        public String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + database;
        }
    }

    /**
     * @param maxSize             taille maximale du pool
     * @param connectionTimeoutMs délai d'obtention d'une connexion du pool
     * @param maxLifetimeMs       durée de vie maximale d'une connexion avant recyclage
     */
    public record PoolSettings(int maxSize, long connectionTimeoutMs, long maxLifetimeMs) {
        public PoolSettings {
            if (maxSize <= 0) {
                maxSize = 10;
            }
            if (connectionTimeoutMs <= 0) {
                connectionTimeoutMs = 10_000L;
            }
            if (maxLifetimeMs <= 0) {
                maxLifetimeMs = 1_800_000L;
            }
        }

        public static PoolSettings defaults() {
            return new PoolSettings(10, 10_000L, 1_800_000L);
        }
    }
}
