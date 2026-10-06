package com.lodygames.rpgquest.panel.users;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Liaisons compte PlugAdmin ↔ joueur Minecraft, et droits Minecraft par groupe (issue #200).
 * Persistées dans {@code control-panel.db} — la même base que les comptes et les groupes, jamais
 * {@code data.db} du plugin.
 *
 * <p>Migration : deux {@code CREATE TABLE IF NOT EXISTS} <strong>additifs</strong>. Aucune table
 * existante n'est modifiée. Une base existante démarre donc sans aucune liaison et sans aucun droit
 * Minecraft géré : rien ne change en jeu tant qu'un administrateur n'a rien configuré.</p>
 *
 * <p>Deux unicités sont imposées <strong>en base</strong> et pas seulement dans le code :
 * un compte PlugAdmin n'a qu'un joueur lié, et un joueur n'est lié qu'à un compte. Sans elles,
 * deux administrateurs agissant en même temps pourraient créer une double liaison, et on ne saurait
 * plus à qui appliquer quels droits.</p>
 */
public final class McBridgeRepository {

    private static final String CREATE_LINK = """
            CREATE TABLE IF NOT EXISTS panel_user_minecraft (
                user_id TEXT PRIMARY KEY,
                mc_uuid TEXT NOT NULL UNIQUE,
                mc_name TEXT,
                linked_at TEXT NOT NULL,
                linked_by TEXT NOT NULL
            )
            """;
    private static final String CREATE_GROUP_NODE = """
            CREATE TABLE IF NOT EXISTS panel_group_mc_node (
                group_id TEXT NOT NULL,
                node TEXT NOT NULL,
                world TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (group_id, node, world)
            )
            """;

    /**
     * @param mcName dernier pseudonyme connu — <strong>informatif</strong>. L'identité est l'UUID :
     *               un pseudonyme change, et il ne prouve rien.
     */
    public record Link(String userId, String mcUuid, String mcName, Instant linkedAt, String linkedBy) {
    }

    /** Un droit Minecraft accordé par un groupe. {@code world} vide = partout. */
    public record GroupNode(String groupId, String node, String world) {

        public boolean isGlobal() {
            return world == null || world.isBlank();
        }
    }

    private final String jdbcUrl;

    public McBridgeRepository(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(CREATE_LINK);
            st.execute(CREATE_GROUP_NODE);
            st.execute("CREATE INDEX IF NOT EXISTS idx_panel_group_mc_node_group "
                    + "ON panel_group_mc_node (group_id)");
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser les tables du pont Minecraft dans " + dbPath, e);
        }
    }

    // ---- Liaisons ------------------------------------------------------------------------------

    public Optional<Link> linkOfUser(String userId) {
        return queryLink("SELECT * FROM panel_user_minecraft WHERE user_id = ?", userId);
    }

    public Optional<Link> linkOfPlayer(String mcUuid) {
        return queryLink("SELECT * FROM panel_user_minecraft WHERE mc_uuid = ?",
                mcUuid == null ? null : mcUuid.toLowerCase(Locale.ROOT));
    }

    public List<Link> allLinks() {
        List<Link> out = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM panel_user_minecraft ORDER BY linked_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapLink(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des liaisons impossible", e);
        }
        return out;
    }

    /**
     * Pose ou remplace la liaison d'un compte. L'unicité en base lève si le joueur est déjà lié à
     * un <strong>autre</strong> compte — le conflit est donc impossible à créer, pas seulement
     * déconseillé.
     */
    public void link(Link link) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO panel_user_minecraft (user_id, mc_uuid, mc_name, linked_at, linked_by)
                     VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT (user_id) DO UPDATE SET mc_uuid = excluded.mc_uuid,
                         mc_name = excluded.mc_name, linked_at = excluded.linked_at,
                         linked_by = excluded.linked_by
                     """)) {
            ps.setString(1, link.userId());
            ps.setString(2, link.mcUuid().toLowerCase(Locale.ROOT));
            ps.setString(3, link.mcName());
            ps.setString(4, link.linkedAt().toString());
            ps.setString(5, link.linkedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new PlayerAlreadyLinkedException(link.mcUuid());
            }
            throw new IllegalStateException("Liaison impossible", e);
        }
    }

    public void unlink(String userId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM panel_user_minecraft WHERE user_id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Dissociation impossible", e);
        }
    }

    // ---- Droits Minecraft par groupe -----------------------------------------------------------

    public List<GroupNode> nodesOfGroup(String groupId) {
        List<GroupNode> out = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT group_id, node, world FROM panel_group_mc_node WHERE group_id = ? "
                             + "ORDER BY node, world")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GroupNode(rs.getString("group_id"), rs.getString("node"),
                            rs.getString("world")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des droits Minecraft du groupe impossible", e);
        }
        return out;
    }

    /** Remplacement atomique : jamais un groupe à moitié vidé de ses droits Minecraft. */
    public void replaceNodesOfGroup(String groupId, Set<GroupNode> nodes) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM panel_group_mc_node WHERE group_id = ?")) {
                    ps.setString(1, groupId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO panel_group_mc_node (group_id, node, world) VALUES (?, ?, ?)")) {
                    for (GroupNode node : nodes) {
                        ps.setString(1, groupId);
                        ps.setString(2, node.node().toLowerCase(Locale.ROOT));
                        ps.setString(3, node.world() == null ? "" : node.world());
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
            throw new IllegalStateException("Mise à jour des droits Minecraft du groupe impossible", e);
        }
    }

    public void deleteNodesOfGroup(String groupId) {
        replaceNodesOfGroup(groupId, Set.of());
    }

    /** Tous les groupes ayant au moins un droit Minecraft configuré. */
    public Set<String> groupsWithNodes() {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT group_id FROM panel_group_mc_node");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString("group_id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des groupes avec droits Minecraft impossible", e);
        }
        return out;
    }

    // ---- interne -------------------------------------------------------------------------------

    private Optional<Link> queryLink(String sql, String key) {
        if (key == null) {
            return Optional.empty();
        }
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapLink(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture de liaison impossible", e);
        }
    }

    private static Link mapLink(ResultSet rs) throws SQLException {
        return new Link(rs.getString("user_id"), rs.getString("mc_uuid"), rs.getString("mc_name"),
                Instant.parse(rs.getString("linked_at")), rs.getString("linked_by"));
    }

    private static boolean isUniqueViolation(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("unique");
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /** Levée quand le joueur visé est déjà lié à un autre compte PlugAdmin. */
    public static final class PlayerAlreadyLinkedException extends RuntimeException {
        public PlayerAlreadyLinkedException(String mcUuid) {
            super("Ce joueur est déjà lié à un autre compte PlugAdmin : " + mcUuid);
        }
    }
}
