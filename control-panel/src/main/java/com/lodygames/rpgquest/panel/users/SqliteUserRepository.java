package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.Role;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Comptes PlugAdmin persistés dans {@code control-panel.db} (SQLite) — la <strong>même</strong>
 * base que le journal d'audit et l'état des agents, jamais {@code data.db} du plugin ni
 * {@code store.db} de {@code web-api}. Aucun rapport avec MariaDB (#42).
 *
 * <p>Migration : un seul {@code CREATE TABLE IF NOT EXISTS} idempotent, additif — aucune table
 * existante n'est modifiée. Une connexion par opération, comme {@code SqliteAuditLog} et
 * {@code AgentStore} (volume faible).</p>
 */
public final class SqliteUserRepository implements UserRepository {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS panel_user (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                username_lower TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL,
                active INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL,
                last_login_at TEXT
            )
            """;

    private final String jdbcUrl;

    public SqliteUserRepository(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(CREATE);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser la table panel_user dans " + dbPath, e);
        }
    }

    @Override
    public Optional<PanelUser> findById(String id) {
        return queryOne("SELECT * FROM panel_user WHERE id = ?", id);
    }

    @Override
    public Optional<PanelUser> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return queryOne("SELECT * FROM panel_user WHERE username_lower = ?",
                username.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public List<PanelUser> all() {
        List<PanelUser> users = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM panel_user ORDER BY created_at ASC, username ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(read(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des comptes PlugAdmin impossible", e);
        }
        return users;
    }

    @Override
    public void insert(PanelUser user) {
        String sql = "INSERT INTO panel_user "
                + "(id, username, username_lower, password_hash, role, active, created_at, last_login_at) "
                + "VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.id());
            ps.setString(2, user.username());
            ps.setString(3, user.username().toLowerCase(Locale.ROOT));
            ps.setString(4, user.passwordHash());
            ps.setString(5, user.role().name());
            ps.setInt(6, user.active() ? 1 : 0);
            ps.setString(7, user.createdAt().toString());
            ps.setString(8, user.lastLoginAt() == null ? null : user.lastLoginAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new DuplicateUsernameException(user.username());
            }
            throw new IllegalStateException("Création de compte impossible (" + user.username() + ")", e);
        }
    }

    @Override
    public void updateRole(String id, Role role) {
        exec("UPDATE panel_user SET role = ? WHERE id = ?", role.name(), id);
    }

    @Override
    public void updateActive(String id, boolean active) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("UPDATE panel_user SET active = ? WHERE id = ?")) {
            ps.setInt(1, active ? 1 : 0);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Mise à jour du statut de compte impossible (" + id + ")", e);
        }
    }

    @Override
    public void updatePasswordHash(String id, String passwordHash) {
        exec("UPDATE panel_user SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    @Override
    public void recordLogin(String id, Instant when) {
        exec("UPDATE panel_user SET last_login_at = ? WHERE id = ?", when.toString(), id);
    }

    @Override
    public int countActiveOwners() {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM panel_user WHERE role = 'OWNER' AND active = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Comptage des OWNER actifs impossible", e);
        }
    }

    // ---- interne ------------------------------------------------------------------------

    private Optional<PanelUser> queryOne(String sql, String key) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture d'un compte PlugAdmin impossible", e);
        }
    }

    private void exec(String sql, String a, String b) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Écriture panel_user impossible", e);
        }
    }

    private static PanelUser read(ResultSet rs) throws SQLException {
        String lastLogin = rs.getString("last_login_at");
        Role role = Role.byNameOrNull(rs.getString("role"));
        return new PanelUser(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                role == null ? Role.READ_ONLY : role,
                rs.getInt("active") != 0,
                Instant.parse(rs.getString("created_at")),
                lastLogin == null || lastLogin.isBlank() ? null : Instant.parse(lastLogin));
    }

    private static boolean isUniqueViolation(SQLException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return m.contains("unique") || m.contains("constraint");
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
