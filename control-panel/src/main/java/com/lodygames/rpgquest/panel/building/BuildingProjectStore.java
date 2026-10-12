package com.lodygames.rpgquest.panel.building;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Les projets de l'Atelier de création (issue #267), dans la base du panel.
 *
 * <p>Additif : une table nouvelle, aucune table existante modifiée. Les brouillons de plan (#256)
 * pointent vers leur projet par une colonne {@code project_id} ajoutée à côté de {@code site_id}
 * (voir {@link BuildingPlanDraftStore}) — l'ancien rattachement à un emplacement reste lisible.</p>
 */
public final class BuildingProjectStore {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS building_project (
                id TEXT PRIMARY KEY,
                agent_id TEXT NOT NULL,
                name TEXT NOT NULL,
                brief TEXT NOT NULL,
                building_id TEXT NOT NULL,
                front TEXT NOT NULL,
                max_width INTEGER NOT NULL,
                max_height INTEGER NOT NULL,
                source_site_id TEXT,
                version INTEGER NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0,
                created_by TEXT,
                created_at TEXT NOT NULL,
                updated_by TEXT,
                updated_at TEXT NOT NULL
            )
            """;
    private static final String CREATE_SOURCE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_building_project_source ON building_project(agent_id, source_site_id)";

    /** Ce qu'on sait d'un projet à sa création ou à sa modification. */
    public record Fields(String name, String brief, String buildingId, String front, int maxWidth,
                         int maxHeight) {

        public Fields {
            name = name == null ? "" : name.trim();
            brief = brief == null ? "" : brief.trim();
            buildingId = buildingId == null ? "" : buildingId.trim().toLowerCase(java.util.Locale.ROOT);
            front = BuildingProject.normaliseFront(front);
            maxWidth = Math.min(64, Math.max(5, maxWidth));
            maxHeight = Math.min(64, Math.max(4, maxHeight));
        }

        /** Ce qui empêche d'enregistrer ces champs, ou vide. Jamais de troncature silencieuse. */
        public Optional<String> refusal() {
            if (name.isEmpty()) {
                return Optional.of("Donnez un nom au projet.");
            }
            if (name.length() > BuildingProject.MAX_NAME) {
                return Optional.of("Nom trop long : " + name.length() + " caractères pour "
                        + BuildingProject.MAX_NAME + " au plus.");
            }
            if (brief.length() > BuildingProject.MAX_BRIEF) {
                return Optional.of("Description trop longue : " + brief.length() + " caractères pour "
                        + BuildingProject.MAX_BRIEF + " au plus. Rien n'a été coupé ni enregistré.");
            }
            if (!BuildingProject.BUILDING_ID.matcher(buildingId).matches()) {
                return Optional.of("Identifiant de bibliothèque invalide : « " + buildingId + " » "
                        + "(3 à 48 caractères : minuscules, chiffres ou « _ »).");
            }
            return Optional.empty();
        }
    }

    /** Issue d'une modification. */
    public enum UpdateOutcome { SAVED, STALE, NOT_FOUND }

    private final String jdbcUrl;

    public BuildingProjectStore(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(CREATE);
            s.execute(CREATE_SOURCE_INDEX);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser les projets d'atelier dans " + dbPath, e);
        }
    }

    public BuildingProject create(String agentId, Fields f, String sourceSiteId, String by) {
        String id = UUID.randomUUID().toString();
        String now = STAMP.format(Instant.now());
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO building_project (id, agent_id, name, brief, building_id, front, max_width,
                    max_height, source_site_id, version, archived, created_by, created_at, updated_by,
                    updated_at) VALUES (?,?,?,?,?,?,?,?,?,1,0,?,?,?,?)
                """)) {
            int i = 1;
            ps.setString(i++, id);
            ps.setString(i++, agentId);
            ps.setString(i++, f.name());
            ps.setString(i++, f.brief());
            ps.setString(i++, f.buildingId());
            ps.setString(i++, f.front());
            ps.setInt(i++, f.maxWidth());
            ps.setInt(i++, f.maxHeight());
            ps.setString(i++, sourceSiteId == null ? "" : sourceSiteId);
            ps.setString(i++, by);
            ps.setString(i++, now);
            ps.setString(i++, by);
            ps.setString(i, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Création du projet impossible", e);
        }
        return find(id).orElseThrow();
    }

    /** Modifie les champs, seulement si {@code expectedVersion} est encore la version courante. */
    public UpdateOutcome update(String id, int expectedVersion, Fields f, String by) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
                UPDATE building_project SET name = ?, brief = ?, building_id = ?, front = ?,
                    max_width = ?, max_height = ?, version = version + 1, updated_by = ?, updated_at = ?
                WHERE id = ? AND version = ? AND archived = 0
                """)) {
            int i = 1;
            ps.setString(i++, f.name());
            ps.setString(i++, f.brief());
            ps.setString(i++, f.buildingId());
            ps.setString(i++, f.front());
            ps.setInt(i++, f.maxWidth());
            ps.setInt(i++, f.maxHeight());
            ps.setString(i++, by);
            ps.setString(i++, STAMP.format(Instant.now()));
            ps.setString(i++, id);
            ps.setInt(i, expectedVersion);
            if (ps.executeUpdate() == 1) {
                return UpdateOutcome.SAVED;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Modification du projet impossible (" + id + ")", e);
        }
        return find(id).filter(p -> !p.archived()).isPresent() ? UpdateOutcome.STALE : UpdateOutcome.NOT_FOUND;
    }

    public Optional<BuildingProject> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return one("SELECT * FROM building_project WHERE id = ?", id.trim());
    }

    /** Le projet déjà créé depuis cet emplacement : l'import est idempotent. */
    public Optional<BuildingProject> importedFrom(String agentId, String siteId) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM building_project WHERE agent_id = ? AND source_site_id = ? "
                        + "ORDER BY created_at LIMIT 1")) {
            ps.setString(1, agentId);
            ps.setString(2, siteId == null ? "" : siteId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture d'un import impossible", e);
        }
    }

    /** Les projets non archivés, le plus récemment modifié d'abord. */
    public List<BuildingProject> list(String agentId) {
        List<BuildingProject> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM building_project WHERE agent_id = ? AND archived = 0 "
                        + "ORDER BY updated_at DESC, created_at DESC")) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des projets impossible", e);
        }
        return out;
    }

    public boolean archive(String id, String by) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE building_project SET archived = 1, updated_by = ?, updated_at = ? "
                        + "WHERE id = ? AND archived = 0")) {
            ps.setString(1, by);
            ps.setString(2, STAMP.format(Instant.now()));
            ps.setString(3, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Archivage du projet impossible (" + id + ")", e);
        }
    }

    /** Marque le projet comme modifié (un brouillon y a été ajouté) : il remonte dans la liste. */
    public void touch(String id, String by) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE building_project SET updated_by = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, by);
            ps.setString(2, STAMP.format(Instant.now()));
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Mise à jour du projet impossible (" + id + ")", e);
        }
    }

    private Optional<BuildingProject> one(String sql, String arg) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture du projet impossible", e);
        }
    }

    private static BuildingProject read(ResultSet rs) throws SQLException {
        return new BuildingProject(rs.getString("id"), rs.getString("agent_id"), rs.getString("name"),
                rs.getString("brief"), rs.getString("building_id"), rs.getString("front"),
                rs.getInt("max_width"), rs.getInt("max_height"), rs.getString("source_site_id"),
                rs.getInt("version"), rs.getInt("archived") != 0, rs.getString("created_by"),
                instant(rs.getString("created_at")), rs.getString("updated_by"),
                instant(rs.getString("updated_at")));
    }

    private static Instant instant(String raw) {
        try {
            return raw == null ? Instant.EPOCH : Instant.parse(raw);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }
}
