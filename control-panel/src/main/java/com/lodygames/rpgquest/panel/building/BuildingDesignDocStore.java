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
 * La conception détaillée d'un projet d'Atelier (#289), avant tout plan technique.
 *
 * <h2>Pourquoi deux étapes</h2>
 *
 * <p>La Maison de Lily décrivait en 5 793 caractères une chambre, un lit rose, une cuisine avec
 * four, un coin lecture, des tapis, des fleurs, des vitraux, un porche et un décor extérieur. Le plan
 * final, écrit d'un seul jet en YAML, en omettait la plus grande partie : le modèle devait inventer
 * l'organisation, la palette et le mobilier <em>en même temps</em> qu'il calculait des coordonnées.
 * La conception les fixe d'abord, en clair ; le plan technique la traduit ensuite.</p>
 *
 * <h2>Ce qui est garanti</h2>
 *
 * <ul>
 *   <li>chaque conception est une <strong>version</strong> : rien n'est écrasé, l'administrateur peut
 *       la relire, la corriger à la main, et c'est la dernière qui sert ;</li>
 *   <li>une modification se fait <strong>sous contrôle de version</strong> : deux onglets ne
 *       s'écrasent pas en silence ;</li>
 *   <li>bornée à {@value #MAX_TEXT} caractères, jamais tronquée : refus chiffré.</li>
 * </ul>
 */
public final class BuildingDesignDocStore {

    /** Une conception tient en quelques pages ; au-delà, c'est un plan déguisé. */
    public static final int MAX_TEXT = 20_000;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS building_design_doc (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                text TEXT NOT NULL,
                origin TEXT NOT NULL,
                provider_id TEXT,
                model TEXT,
                usage TEXT,
                created_by TEXT,
                created_at TEXT NOT NULL,
                UNIQUE (project_id, version)
            )
            """;

    /** D'où vient une version de la conception. */
    public enum Origin {
        AI("Rédigée par l'IA"),
        MANUAL("Modifiée à la main");

        private final String label;

        Origin(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static Origin of(String raw) {
            return "MANUAL".equals(raw) ? MANUAL : AI;
        }
    }

    /** Une version de la conception. */
    public record Doc(String id, String projectId, int version, String text, Origin origin,
                      String providerId, String model, String usage, String createdBy, Instant createdAt) {
    }

    /** L'issue d'un enregistrement : la version créée, ou le motif du refus. */
    public record Saved(Optional<Doc> doc, String refusal) {
    }

    private final String jdbcUrl;

    public BuildingDesignDocStore(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(CREATE);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser les conceptions dans " + dbPath, e);
        }
    }

    /** La conception en vigueur : la plus récente. */
    public Optional<Doc> latest(String projectId) {
        List<Doc> all = history(projectId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Toutes les versions, de la plus récente à la plus ancienne. */
    public List<Doc> history(String projectId) {
        List<Doc> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM building_design_doc WHERE project_id = ? ORDER BY version DESC")) {
            ps.setString(1, projectId == null ? "" : projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Doc(rs.getString("id"), rs.getString("project_id"), rs.getInt("version"),
                            rs.getString("text"), Origin.of(rs.getString("origin")), rs.getString("provider_id"),
                            rs.getString("model"), rs.getString("usage"), rs.getString("created_by"),
                            parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des conceptions impossible", e);
        }
        return out;
    }

    /**
     * Ajoute une version.
     *
     * @param expectedVersion la version affichée quand l'administrateur a commencé à modifier ;
     *                        -1 pour une version de l'IA, qui ne remplace rien de ce qu'on édite
     */
    public synchronized Saved save(String projectId, String text, Origin origin, String providerId,
                                   String model, String usage, String by, int expectedVersion) {
        String clean = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (clean.isEmpty()) {
            return new Saved(Optional.empty(), "La conception est vide : rien n'a été enregistré.");
        }
        if (clean.length() > MAX_TEXT) {
            return new Saved(Optional.empty(), "Conception trop longue : " + clean.length() + " caractères pour "
                    + MAX_TEXT + " au plus. Rien n'a été coupé ni enregistré.");
        }
        int current = latest(projectId).map(Doc::version).orElse(0);
        if (expectedVersion >= 0 && expectedVersion != current) {
            return new Saved(Optional.empty(), "La conception a changé depuis l'affichage de la page (version "
                    + current + ") : rien n'a été enregistré. Rechargez-la, puis reportez vos modifications.");
        }
        Doc doc = new Doc(UUID.randomUUID().toString(), projectId, current + 1, clean, origin, providerId,
                model, usage, by, Instant.now());
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO building_design_doc (id, project_id, version, text, origin, provider_id, model, "
                        + "usage, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, doc.id());
            ps.setString(2, projectId);
            ps.setInt(3, doc.version());
            ps.setString(4, clean);
            ps.setString(5, origin.name());
            ps.setString(6, providerId);
            ps.setString(7, model);
            ps.setString(8, usage);
            ps.setString(9, by);
            ps.setString(10, STAMP.format(doc.createdAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Enregistrement de la conception impossible", e);
        }
        return new Saved(Optional.of(doc), "");
    }

    private static Instant parse(String raw) {
        try {
            return Instant.parse(raw);
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
