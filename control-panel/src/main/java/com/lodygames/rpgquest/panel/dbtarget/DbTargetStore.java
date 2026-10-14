package com.lodygames.rpgquest.panel.dbtarget;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Enregistrement, dans {@code control-panel.db}, des paramètres d'une base MariaDB : la cible d'une
 * future migration RPGQuest (issue #42) ou la base propre au panel (issue #43), chacune dans sa
 * table ({@link DbTargetKind}). Une seule cible par table (ligne {@code id = 1}).
 *
 * <p>Le mot de passe est stocké <strong>chiffré</strong> ({@link SecretBox}, AES-256-GCM, clé
 * dérivée de {@code RPGQUEST_PANEL_SECRET} qui n'est jamais dans la base). Règles :</p>
 * <ul>
 *   <li>{@link #save} avec un mot de passe vide <strong>conserve</strong> le secret existant ;</li>
 *   <li>{@link #clearPassword} est une action séparée et explicite ;</li>
 *   <li>{@link #get} ne renvoie que « configuré / non configuré » ;</li>
 *   <li>seul {@link #password()} déchiffre, pour le testeur de connexion.</li>
 * </ul>
 *
 * <p>Changer un paramètre de connexion invalide le dernier résultat de test : un « OK » ne doit
 * jamais s'appliquer à une autre cible que celle testée.</p>
 */
public final class DbTargetStore {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS %s (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                host TEXT NOT NULL DEFAULT '',
                port INTEGER NOT NULL DEFAULT 3306,
                database_name TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL DEFAULT '',
                ssl_mode TEXT NOT NULL DEFAULT 'disable',
                password_sealed TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL DEFAULT '',
                updated_by TEXT NOT NULL DEFAULT '',
                last_test_at TEXT NOT NULL DEFAULT '',
                last_test_ok INTEGER NOT NULL DEFAULT 0,
                last_test_summary TEXT NOT NULL DEFAULT ''
            )
            """;

    private final String jdbcUrl;
    private final String table;
    private final SecretBox box;

    /** Cible de migration RPGQuest (issue #42). */
    public DbTargetStore(String dbPath, SecretBox box) {
        this(dbPath, box, DbTargetKind.MIGRATION);
    }

    /**
     * @param box chiffreur dont l'usage doit être {@link DbTargetKind#secretPurpose()} de {@code kind}
     */
    public DbTargetStore(String dbPath, SecretBox box, DbTargetKind kind) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        this.table = kind.table();
        this.box = box;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(CREATE.formatted(table));
            st.execute("INSERT OR IGNORE INTO " + table + " (id) VALUES (1)");
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser " + table + " dans " + dbPath, e);
        }
    }

    public DbTargetSettings get() {
        String sql = "SELECT host, port, database_name, username, ssl_mode, password_sealed, updated_at, "
                + "updated_by, last_test_at, last_test_ok, last_test_summary FROM " + table + " WHERE id = 1";
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return DbTargetSettings.empty();
            }
            String sealed = rs.getString("password_sealed");
            boolean configured = !sealed.isEmpty();
            boolean unreadable = configured && box.open(sealed).isEmpty();
            return new DbTargetSettings(rs.getString("host"), rs.getInt("port"), rs.getString("database_name"),
                    rs.getString("username"), rs.getString("ssl_mode"), configured, unreadable,
                    rs.getString("updated_at"), rs.getString("updated_by"), rs.getString("last_test_at"),
                    rs.getInt("last_test_ok") == 1, rs.getString("last_test_summary"));
        } catch (SQLException e) {
            return DbTargetSettings.empty();
        }
    }

    /**
     * Enregistre les paramètres (déjà validés par {@link DbTargetSettings#validate}).
     *
     * @param newPassword nouveau mot de passe ; vide ou {@code null} = conserver l'actuel
     */
    public void save(String host, int port, String database, String username, String sslMode,
                     String newPassword, String actor) {
        DbTargetSettings before = get();
        boolean targetChanged = !host.equals(before.host()) || port != before.port()
                || !database.equals(before.database()) || !username.equals(before.username())
                || !sslMode.equalsIgnoreCase(before.sslMode());
        boolean replacePassword = newPassword != null && !newPassword.isEmpty();
        String sql = "UPDATE " + table + " SET host = ?, port = ?, database_name = ?, username = ?, "
                + "ssl_mode = ?, updated_at = ?, updated_by = ?"
                + (replacePassword ? ", password_sealed = ?" : "")
                + (targetChanged || replacePassword ? ", last_test_at = '', last_test_ok = 0, last_test_summary = ''" : "")
                + " WHERE id = 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, host);
            ps.setInt(2, port);
            ps.setString(3, database);
            ps.setString(4, username);
            ps.setString(5, sslMode.toLowerCase(Locale.ROOT));
            ps.setString(6, Instant.now().toString());
            ps.setString(7, actor == null ? "" : actor);
            if (replacePassword) {
                ps.setString(8, box.seal(newPassword));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'enregistrer la cible " + table, e);
        }
    }

    /** Efface le mot de passe (action explicite, distincte de l'enregistrement). */
    public void clearPassword(String actor) {
        update("UPDATE " + table + " SET password_sealed = '', updated_at = ?, updated_by = ?, "
                + "last_test_at = '', last_test_ok = 0, last_test_summary = '' WHERE id = 1",
                Instant.now().toString(), actor == null ? "" : actor);
    }

    /** Mot de passe déchiffré — réservé au testeur de connexion ; vide si absent ou illisible. */
    Optional<String> password() {
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT password_sealed FROM " + table + " WHERE id = 1")) {
            return rs.next() ? box.open(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    /** Teste la cible enregistrée avec son mot de passe, et mémorise le résultat. */
    public DbConnectionTester.Result test(DbConnectionTester tester) {
        DbTargetSettings settings = get();
        Optional<String> password = password();
        DbConnectionTester.Result result;
        if (!settings.configured()) {
            result = DbConnectionTester.Result.failure("Cible incomplète : hôte, base et compte sont obligatoires.");
        } else if (password.isEmpty()) {
            result = DbConnectionTester.Result.failure(settings.passwordUnreadable()
                    ? "Mot de passe illisible (secret du panel changé ?) : le ressaisir."
                    : "Aucun mot de passe enregistré.");
        } else {
            result = tester.test(settings, password.get());
        }
        update("UPDATE " + table + " SET last_test_at = ?, last_test_ok = ?, last_test_summary = ? WHERE id = 1",
                Instant.now().toString(), result.ok() ? "1" : "0", result.summary());
        return result;
    }

    private void update(String sql, String... values) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setString(i + 1, values[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible de mettre à jour la cible " + table, e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
