package com.lodygames.rpgquest.database;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configuration <strong>abstraite</strong> de la persistance RPGQuest (issues #40 / #41) : quel
 * moteur, et ses paramètres. Produite par {@code ConfigValidator} à partir de la section
 * {@code database:} de {@code config.yml}. Immuable, sans dépendance Bukkit.
 *
 * <p>Le mot de passe MySQL/MariaDB n'est <strong>jamais</strong> lu depuis {@code config.yml} :
 * seul le <em>nom</em> d'une variable d'environnement est configuré
 * ({@link MySqlSettings#passwordEnv()}), résolu à l'ouverture de la connexion. Aucun secret dans le
 * dépôt, les logs ou les rapports.</p>
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
            case MYSQL -> mysql.describe();
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
     * @param host        hôte du serveur MySQL/MariaDB
     * @param port        port TCP (1..65535)
     * @param database    nom de la base
     * @param username    utilisateur (compte dédié RPGQuest, jamais admin global — voir docs)
     * @param passwordEnv <strong>nom</strong> de la variable d'environnement contenant le mot de passe
     * @param sslMode     mode TLS du driver MariaDB : {@code disable} (défaut), {@code trust},
     *                    {@code verify-ca}, {@code verify-full}
     * @param pool        paramètres du pool de connexions HikariCP
     */
    public record MySqlSettings(
            String host, int port, String database, String username, String passwordEnv,
            String sslMode, PoolSettings pool) {

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
            sslMode = normaliseSslMode(sslMode);
            if (pool == null) {
                pool = PoolSettings.defaults();
            }
        }

        public static MySqlSettings defaults() {
            return new MySqlSettings("localhost", 3306, "rpgquest", "rpgquest", "RPGQUEST_DB_PASSWORD",
                    "disable", PoolSettings.defaults());
        }

        /** Résout le mot de passe depuis l'environnement fourni. Vide si la variable n'est pas définie. */
        public Optional<String> resolvePassword(UnaryOperator<String> env) {
            String value = env.apply(passwordEnv);
            return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
        }

        /** URL JDBC (driver MariaDB), <strong>sans identifiants</strong>. */
        public String jdbcUrl() {
            return "jdbc:mariadb://" + host + ":" + port + "/" + database;
        }

        /** Description sûre pour les logs — jamais de mot de passe ni d'URL avec secret. */
        public String describe() {
            return "mariadb " + username + "@" + host + ":" + port + "/" + database
                    + " (pool " + pool.minimumIdle() + ".." + pool.maximumPoolSize()
                    + ", ssl=" + sslMode + ")";
        }

        private static String normaliseSslMode(String raw) {
            if (raw == null || raw.isBlank()) {
                return "disable";
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "disable", "disabled", "false", "off" -> "disable";
                case "trust" -> "trust";
                case "verify-ca", "verify_ca" -> "verify-ca";
                case "verify-full", "verify_full" -> "verify-full";
                default -> throw new IllegalArgumentException(
                        "ssl-mode inconnu : « " + raw + " » (valides : disable, trust, verify-ca, verify-full)");
            };
        }
    }

    /**
     * Paramètres du pool HikariCP (issue #41). Noms alignés sur HikariCP.
     *
     * @param minimumIdle         connexions maintenues au repos
     * @param maximumPoolSize     taille maximale du pool
     * @param connectionTimeoutMs délai d'obtention d'une connexion du pool avant échec
     * @param maxLifetimeMs       durée de vie maximale d'une connexion avant recyclage
     * @param keepaliveMs         intervalle de « ping » d'une connexion inactive (0 = désactivé)
     */
    public record PoolSettings(
            int minimumIdle, int maximumPoolSize, long connectionTimeoutMs, long maxLifetimeMs, long keepaliveMs) {

        public PoolSettings {
            if (maximumPoolSize <= 0) {
                maximumPoolSize = 10;
            }
            if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
                minimumIdle = Math.min(2, maximumPoolSize);
            }
            if (connectionTimeoutMs < 250) {
                connectionTimeoutMs = 10_000L;
            }
            if (maxLifetimeMs <= 0) {
                maxLifetimeMs = 1_800_000L;
            }
            if (keepaliveMs < 0 || (keepaliveMs > 0 && keepaliveMs >= maxLifetimeMs)) {
                keepaliveMs = 0L;
            }
        }

        public static PoolSettings defaults() {
            return new PoolSettings(2, 10, 10_000L, 1_800_000L, 0L);
        }
    }
}
