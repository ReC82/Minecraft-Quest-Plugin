package com.lodygames.rpgquest.panel.dbtarget;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Testeur réel (issue #42) : une connexion JDBC MariaDB unique, en <strong>lecture seule</strong>
 * ({@code setReadOnly} + {@code SET SESSION TRANSACTION READ ONLY}), qui ne lit que des
 * métadonnées : version du serveur, chiffrement TLS de la session, nombre de tables de la base,
 * présence et version d'un schéma RPGQuest. Aucune requête ne lit ni n'écrit de donnée métier.
 *
 * <p>Délais bornés : {@code connectTimeout} et {@code socketTimeout} — une cible injoignable ne
 * bloque jamais un fil du serveur HTTP au-delà de quelques secondes.</p>
 */
public final class MariaDbConnectionTester implements DbConnectionTester {

    private final int connectTimeoutMs;
    private final int socketTimeoutMs;

    public MariaDbConnectionTester() {
        this(5_000, 8_000);
    }

    public MariaDbConnectionTester(int connectTimeoutMs, int socketTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.socketTimeoutMs = socketTimeoutMs;
    }

    @Override
    public Result test(DbTargetSettings settings, String password) {
        String url = "jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                + "?sslMode=" + settings.sslMode() + "&connectTimeout=" + connectTimeoutMs
                + "&socketTimeout=" + socketTimeoutMs;
        Properties properties = new Properties();
        properties.setProperty("user", settings.username());
        properties.setProperty("password", password);
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(Math.max(1, socketTimeoutMs / 1000));
                statement.execute("SET SESSION TRANSACTION READ ONLY");
                String version = scalar(statement, "SELECT VERSION()");
                String cipher = sessionCipher(statement);
                long tables = Long.parseLong(scalar(statement,
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"));
                String schema = rpgquestSchema(statement);
                return Result.success("Connexion réussie (lecture seule) — serveur " + version
                        + ", TLS " + (cipher.isEmpty() ? "non utilisé" : "actif (" + cipher + ")")
                        + ", " + tables + " table(s) dans la base, " + schema + ".");
            }
        } catch (SQLException e) {
            return Result.failure("Échec de connexion : "
                    + DbConnectionTester.redact(e.getMessage(), settings, password));
        } catch (RuntimeException e) {
            return Result.failure("Échec : " + DbConnectionTester.redact(e.getMessage(), settings, password));
        }
    }

    private static String sessionCipher(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            return rs.next() && rs.getString(2) != null ? rs.getString(2) : "";
        }
    }

    private static String rpgquestSchema(Statement statement) throws SQLException {
        String present = scalar(statement, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'rpgquest_schema_migrations'");
        if ("0".equals(present)) {
            return "aucun schéma RPGQuest";
        }
        return "schéma RPGQuest V" + scalar(statement, "SELECT COALESCE(MAX(version), 0) FROM rpgquest_schema_migrations");
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? String.valueOf(rs.getString(1)) : "";
        }
    }
}
