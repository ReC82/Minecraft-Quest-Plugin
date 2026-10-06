package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.PanelGroup;
import com.lodygames.rpgquest.panel.authz.Permission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Groupes et appartenances persistés dans {@code control-panel.db} (SQLite) — la même base que les
 * comptes, le journal d'audit et l'état des agents, jamais {@code data.db} du plugin.
 *
 * <p>Migration : trois {@code CREATE TABLE IF NOT EXISTS} idempotents et <strong>additifs</strong>.
 * Aucune table existante n'est modifiée, et en particulier {@code panel_user} n'est pas touché : un
 * compte garde exactement le rôle et les droits qu'il avait avant cette mise à jour. Une base
 * existante démarre donc avec <strong>zéro groupe</strong>, c'est-à-dire des droits effectifs
 * strictement égaux à ceux du rôle.</p>
 */
public final class SqliteGroupRepository implements GroupRepository {

    private static final String CREATE_GROUP = """
            CREATE TABLE IF NOT EXISTS panel_group (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                name_lower TEXT NOT NULL UNIQUE,
                description TEXT,
                created_at TEXT NOT NULL
            )
            """;
    private static final String CREATE_GROUP_PERMISSION = """
            CREATE TABLE IF NOT EXISTS panel_group_permission (
                group_id TEXT NOT NULL,
                permission TEXT NOT NULL,
                PRIMARY KEY (group_id, permission)
            )
            """;
    private static final String CREATE_MEMBERSHIP = """
            CREATE TABLE IF NOT EXISTS panel_user_group (
                user_id TEXT NOT NULL,
                group_id TEXT NOT NULL,
                PRIMARY KEY (user_id, group_id)
            )
            """;
    private static final String INDEX_MEMBERSHIP_USER =
            "CREATE INDEX IF NOT EXISTS idx_panel_user_group_user ON panel_user_group (user_id)";

    private final String jdbcUrl;

    public SqliteGroupRepository(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(CREATE_GROUP);
            st.execute(CREATE_GROUP_PERMISSION);
            st.execute(CREATE_MEMBERSHIP);
            st.execute(INDEX_MEMBERSHIP_USER);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser les tables de groupes dans " + dbPath, e);
        }
    }

    @Override
    public List<PanelGroup> all() {
        List<PanelGroup> groups = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, name, description, created_at FROM panel_group ORDER BY name COLLATE NOCASE");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                groups.add(withPermissions(c, rs.getString("id"), rs.getString("name"),
                        rs.getString("description"), rs.getString("created_at")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des groupes impossible", e);
        }
        return groups;
    }

    @Override
    public Optional<PanelGroup> findById(String id) {
        return queryOne("SELECT id, name, description, created_at FROM panel_group WHERE id = ?", id);
    }

    @Override
    public Optional<PanelGroup> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return queryOne("SELECT id, name, description, created_at FROM panel_group WHERE name_lower = ?",
                name.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public void insert(PanelGroup group) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO panel_group (id, name, name_lower, description, created_at) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, group.id());
                    ps.setString(2, group.name());
                    ps.setString(3, group.name().trim().toLowerCase(Locale.ROOT));
                    ps.setString(4, group.description());
                    ps.setString(5, group.createdAt().toString());
                    ps.executeUpdate();
                }
                writePermissions(c, group.id(), group.permissions());
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                if (isUniqueViolation(e)) {
                    throw new DuplicateGroupNameException(group.name());
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Création de groupe impossible", e);
        }
    }

    @Override
    public void updateDetails(String id, String name, String description) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE panel_group SET name = ?, name_lower = ?, description = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setString(2, name.trim().toLowerCase(Locale.ROOT));
            ps.setString(3, description);
            ps.setString(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new DuplicateGroupNameException(name);
            }
            throw new IllegalStateException("Modification de groupe impossible", e);
        }
    }

    /**
     * Remplacement en <strong>une transaction</strong> : il n'existe aucun instant où le groupe
     * aurait perdu ses anciennes permissions sans avoir les nouvelles. Une requête concurrente lit
     * donc toujours un ensemble cohérent, jamais un groupe à moitié vidé.
     */
    @Override
    public void replacePermissions(String id, Set<Permission> permissions) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                deletePermissions(c, id);
                writePermissions(c, id, permissions);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Mise à jour des permissions du groupe impossible", e);
        }
    }

    @Override
    public void delete(String id) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                deletePermissions(c, id);
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM panel_user_group WHERE group_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM panel_group WHERE id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Suppression de groupe impossible", e);
        }
    }

    @Override
    public List<PanelGroup> groupsOf(String userId) {
        List<PanelGroup> groups = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT g.id, g.name, g.description, g.created_at
                       FROM panel_group g
                       JOIN panel_user_group m ON m.group_id = g.id
                      WHERE m.user_id = ?
                      ORDER BY g.name COLLATE NOCASE
                     """)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    groups.add(withPermissions(c, rs.getString("id"), rs.getString("name"),
                            rs.getString("description"), rs.getString("created_at")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des groupes de l'utilisateur impossible", e);
        }
        return groups;
    }

    @Override
    public List<String> membersOf(String groupId) {
        List<String> members = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM panel_user_group WHERE group_id = ?")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.add(rs.getString("user_id"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des membres du groupe impossible", e);
        }
        return members;
    }

    @Override
    public void replaceMemberships(String userId, Set<String> groupIds) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM panel_user_group WHERE user_id = ?")) {
                    ps.setString(1, userId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO panel_user_group (user_id, group_id) VALUES (?, ?)")) {
                    for (String groupId : groupIds) {
                        ps.setString(1, userId);
                        ps.setString(2, groupId);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Mise à jour des appartenances impossible", e);
        }
    }

    @Override
    public void removeAllMemberships(String userId) {
        replaceMemberships(userId, Set.of());
    }

    // ---- interne -------------------------------------------------------------------------------

    private Optional<PanelGroup> queryOne(String sql, String key) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(withPermissions(c, rs.getString("id"), rs.getString("name"),
                        rs.getString("description"), rs.getString("created_at")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture de groupe impossible", e);
        }
    }

    private PanelGroup withPermissions(Connection c, String id, String name, String description,
                                        String createdAt) throws SQLException {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT permission FROM panel_group_permission WHERE group_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Une permission disparue du code (renommée, retirée) est IGNORÉE et non une
                    // erreur : un groupe doit rester lisible et modifiable après une évolution du
                    // catalogue, et une permission inconnue n'accorde rien.
                    Permission.byNameOrNull(rs.getString("permission")).ifPresent(permissions::add);
                }
            }
        }
        return new PanelGroup(id, name, description == null ? "" : description, permissions,
                Instant.parse(createdAt));
    }

    private void writePermissions(Connection c, String id, Set<Permission> permissions) throws SQLException {
        if (permissions.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO panel_group_permission (group_id, permission) VALUES (?, ?)")) {
            for (Permission permission : permissions) {
                ps.setString(1, id);
                ps.setString(2, permission.name());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void deletePermissions(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM panel_group_permission WHERE group_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("unique");
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
