package com.lodygames.rpgquest.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.UnaryOperator;

/**
 * Moteur MySQL/MariaDB (issue #41) : driver <strong>MariaDB Connector/J</strong> +
 * <strong>pool HikariCP</strong>.
 *
 * <ul>
 *   <li>{@link #start()} construit le pool et vérifie la connectivité (une connexion + {@code
 *       SELECT 1}). Échec propre ({@link SQLException}) si la base est injoignable, mal configurée,
 *       ou si le mot de passe (variable d'environnement) est absent — jamais de boucle de
 *       reconnexion agressive : Hikari borne l'attente à {@code connection-timeout-ms}.</li>
 *   <li>{@link #borrow()} emprunte une connexion du pool ; {@link #release(Connection)} la rend
 *       (rollback défensif d'une transaction laissée ouverte, puis {@code close()}).</li>
 *   <li>Réglages de session appliqués une fois par connexion physique via {@code connectionInitSql}
 *       ({@code time_zone='+00:00'}, {@code sql_mode} strict). RPGQuest stocke tous ses horodatages
 *       en chaînes ISO-8601, donc le fuseau n'a pas d'incidence fonctionnelle.</li>
 *   <li>Dialecte {@link MySqlDialect}, historique {@link MigrationTableHistory} (table portable
 *       {@code rpgquest_schema_migrations}).</li>
 * </ul>
 *
 * <p>Aucun secret dans les logs : {@link #describe()} et les messages d'erreur ne contiennent ni
 * mot de passe ni URL JDBC avec identifiants.</p>
 */
public final class MySqlDatabaseEngine implements DatabaseEngine {

    private final DatabaseSettings.MySqlSettings settings;
    private final UnaryOperator<String> env;
    private final SqlDialect dialect = new MySqlDialect();
    private final SchemaHistory schemaHistory = new MigrationTableHistory();

    private volatile HikariDataSource dataSource;

    public MySqlDatabaseEngine(DatabaseSettings.MySqlSettings settings, UnaryOperator<String> env) {
        this.settings = settings;
        this.env = env;
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.MYSQL;
    }

    @Override
    public String describe() {
        return settings.describe();
    }

    @Override
    public void start() throws SQLException {
        String password = settings.resolvePassword(env).orElseThrow(() -> new SQLException(
                "Backend MariaDB : la variable d'environnement « " + settings.passwordEnv()
                        + " » (mot de passe) n'est pas définie. Aucun mot de passe n'est lu depuis config.yml."));

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("RPGQuest-DB");
        hikari.setJdbcUrl(settings.jdbcUrl()
                + "?sslMode=" + settings.sslMode()
                + "&connectTimeout=" + settings.pool().connectionTimeoutMs()
                + "&tcpKeepAlive=true");
        hikari.setUsername(settings.username());
        hikari.setPassword(password);
        hikari.setMinimumIdle(settings.pool().minimumIdle());
        hikari.setMaximumPoolSize(settings.pool().maximumPoolSize());
        hikari.setConnectionTimeout(settings.pool().connectionTimeoutMs());
        hikari.setMaxLifetime(settings.pool().maxLifetimeMs());
        if (settings.pool().keepaliveMs() > 0) {
            hikari.setKeepaliveTime(settings.pool().keepaliveMs());
        }
        hikari.setValidationTimeout(Math.min(5_000L, settings.pool().connectionTimeoutMs()));
        hikari.setConnectionInitSql(
                "SET SESSION time_zone = '+00:00', "
                        + "sql_mode = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION,NO_ZERO_DATE'");
        // Échec RAPIDE au démarrage si la base est injoignable (une tentative, bornée) ; pas de
        // ré-essai infini. Après démarrage, Hikari remplace de lui-même les connexions cassées.
        hikari.setInitializationFailTimeout(settings.pool().connectionTimeoutMs());

        try {
            dataSource = new HikariDataSource(hikari);
        } catch (RuntimeException e) {
            throw new SQLException("Connexion au serveur MariaDB impossible ("
                    + settings.host() + ":" + settings.port() + "/" + settings.database() + ") : "
                    + rootMessage(e), e);
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT VERSION()")) {
            if (resultSet.next()) {
                // version tracée sans secret, utile au diagnostic de compatibilité
                System.getLogger(MySqlDatabaseEngine.class.getName()).log(System.Logger.Level.INFO,
                        "MariaDB/MySQL serveur : " + resultSet.getString(1));
            }
        }
    }

    @Override
    public Connection borrow() throws SQLException {
        HikariDataSource current = this.dataSource;
        if (current == null) {
            throw new SQLException("Moteur MariaDB non démarré.");
        }
        return current.getConnection();
    }

    @Override
    public void release(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // la connexion sera de toute façon évincée du pool à la fermeture
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    @Override
    public SqlDialect dialect() {
        return dialect;
    }

    @Override
    public SchemaHistory schemaHistory() {
        return schemaHistory;
    }

    @Override
    public void close() {
        HikariDataSource current = this.dataSource;
        if (current != null) {
            current.close();
            dataSource = null;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? cursor.getClass().getSimpleName() : message;
    }
}
