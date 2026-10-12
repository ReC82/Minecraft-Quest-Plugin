package com.lodygames.rpgquest.panel.building;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistance des brouillons de plan (issue #256), dans la base <strong>du panel</strong>
 * ({@code control-panel.db}), jamais dans {@code data.db} ni dans le dépôt.
 *
 * <h2>Rien ne s'écrase</h2>
 *
 * <p>Chaque modification du plan crée une <em>révision</em> numérotée ; l'ancienne reste lisible.
 * Une correction demandée à l'IA crée un <em>nouveau</em> brouillon qui pointe vers son parent.
 * L'archivage masque un brouillon de la liste, il ne le supprime pas.</p>
 *
 * <h2>Concurrence et versions</h2>
 *
 * <ul>
 *   <li>Une sauvegarde porte la version que l'écran avait sous les yeux, et n'aboutit que si c'est
 *       encore la version courante (comparer-et-échanger dans une seule requête SQL). Deux onglets
 *       ne peuvent donc pas s'écraser en silence.</li>
 *   <li>Un audit, un aperçu ou une génération est rattaché au brouillon <strong>avec la version
 *       sur laquelle il porte</strong>. Un résultat lancé sur la version 2 ne sera jamais présenté
 *       comme celui de la version 3, même s'il arrive après.</li>
 *   <li>Une demande payante porte un jeton à usage unique, réservé <em>avant</em> l'appel par une
 *       clé primaire : un double clic ou un « renvoyer le formulaire » ne paie pas deux fois.</li>
 * </ul>
 *
 * <p>Migrations idempotentes ({@code CREATE TABLE IF NOT EXISTS}), comme les autres tables du
 * panel. Un {@link Connection} par opération.</p>
 */
public final class BuildingPlanDraftStore {

    /** Les trois gestes qui envoient un plan au serveur, et qu'on rattache à un brouillon. */
    public enum Check {
        AUDIT("building.plan.audit"),
        PREVIEW("building.plan.preview"),
        GENERATE("building.generate");

        private final String actionType;

        Check(String actionType) {
            this.actionType = actionType;
        }

        public String actionType() {
            return actionType;
        }

        public static Optional<Check> ofActionType(String type) {
            for (Check check : values()) {
                if (check.actionType.equals(type)) {
                    return Optional.of(check);
                }
            }
            return Optional.empty();
        }
    }

    /** Une action rattachée à un brouillon, et la version du plan sur laquelle elle porte. */
    public record Link(String actionId, String draftId, Check check, int version, Instant linkedAt) {
    }

    /** Une version passée ou courante du plan. */
    public record Revision(String draftId, int version, String plan, String planSha,
                           String createdBy, Instant createdAt) {
    }

    /** Issue d'une sauvegarde. */
    public enum SaveOutcome {
        /** Nouvelle révision enregistrée. */
        SAVED,
        /** Plan identique à la version courante : rien à enregistrer. */
        UNCHANGED,
        /** La version affichée n'est plus la version courante : rien n'a été écrit. */
        STALE,
        /** Brouillon inconnu ou archivé. */
        NOT_FOUND
    }

    /** Résultat d'une sauvegarde, avec le brouillon tel qu'il est désormais. */
    public record SaveResult(SaveOutcome outcome, Optional<BuildingPlanDraft> draft) {
    }

    /** Réservation d'un jeton de demande payante. */
    public record Reservation(boolean fresh, String draftId) {
    }

    /** Ce qu'on sait au moment de créer un brouillon. */
    public record NewDraft(String agentId, String siteId, String plan,
                           BuildingPlanDraft.Origin origin, String parentId, String sourceActionId,
                           String providerId, String providerLabel, String model, String usage,
                           String rawResponse, String extractionNote, int maxWidth, int maxHeight,
                           String createdBy) {
    }

    private static final String CREATE_DRAFT = """
            CREATE TABLE IF NOT EXISTS building_plan_draft (
                id TEXT PRIMARY KEY,
                agent_id TEXT NOT NULL,
                site_id TEXT NOT NULL,
                plan TEXT NOT NULL,
                plan_sha TEXT NOT NULL,
                version INTEGER NOT NULL,
                origin TEXT NOT NULL,
                parent_id TEXT,
                source_action_id TEXT,
                provider_id TEXT,
                provider_label TEXT,
                model TEXT,
                usage TEXT,
                raw_response TEXT,
                extraction_note TEXT,
                max_width INTEGER NOT NULL DEFAULT 19,
                max_height INTEGER NOT NULL DEFAULT 14,
                archived INTEGER NOT NULL DEFAULT 0,
                created_by TEXT,
                created_at TEXT NOT NULL,
                updated_by TEXT,
                updated_at TEXT NOT NULL
            )
            """;
    private static final String CREATE_DRAFT_SITE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_plan_draft_site ON building_plan_draft(agent_id, site_id)";
    private static final String CREATE_REVISION = """
            CREATE TABLE IF NOT EXISTS building_plan_draft_revision (
                draft_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                plan TEXT NOT NULL,
                plan_sha TEXT NOT NULL,
                created_by TEXT,
                created_at TEXT NOT NULL,
                PRIMARY KEY (draft_id, version)
            )
            """;
    private static final String CREATE_LINK = """
            CREATE TABLE IF NOT EXISTS building_plan_draft_action (
                action_id TEXT PRIMARY KEY,
                draft_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                version INTEGER NOT NULL,
                linked_at TEXT NOT NULL
            )
            """;
    private static final String CREATE_LINK_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_plan_draft_action_draft ON building_plan_draft_action(draft_id)";
    private static final String CREATE_REQUEST = """
            CREATE TABLE IF NOT EXISTS building_plan_request (
                token TEXT PRIMARY KEY,
                draft_id TEXT,
                created_by TEXT,
                created_at TEXT NOT NULL
            )
            """;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final String jdbcUrl;

    public BuildingPlanDraftStore(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(CREATE_DRAFT);
            s.execute(CREATE_DRAFT_SITE_INDEX);
            s.execute(CREATE_REVISION);
            s.execute(CREATE_LINK);
            s.execute(CREATE_LINK_INDEX);
            s.execute(CREATE_REQUEST);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Impossible d'initialiser les tables de brouillons de plan dans " + dbPath, e);
        }
    }

    // ---- Création ---------------------------------------------------------------------------

    /** Crée un brouillon en version 1, avec sa première révision, dans une seule transaction. */
    public BuildingPlanDraft create(NewDraft d) {
        String id = UUID.randomUUID().toString();
        String now = now();
        String plan = d.plan() == null ? "" : d.plan();
        String sha = sha256(plan);
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO building_plan_draft (id, agent_id, site_id, plan, plan_sha, version,
                        origin, parent_id, source_action_id, provider_id, provider_label, model, usage,
                        raw_response, extraction_note, max_width, max_height, archived, created_by,
                        created_at, updated_by, updated_at)
                    VALUES (?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)
                    """)) {
                int i = 1;
                ps.setString(i++, id);
                ps.setString(i++, d.agentId());
                ps.setString(i++, d.siteId());
                ps.setString(i++, plan);
                ps.setString(i++, sha);
                ps.setString(i++, d.origin().name());
                ps.setString(i++, nz(d.parentId()));
                ps.setString(i++, nz(d.sourceActionId()));
                ps.setString(i++, nz(d.providerId()));
                ps.setString(i++, nz(d.providerLabel()));
                ps.setString(i++, nz(d.model()));
                ps.setString(i++, nz(d.usage()));
                ps.setString(i++, nz(d.rawResponse()));
                ps.setString(i++, nz(d.extractionNote()));
                ps.setInt(i++, d.maxWidth());
                ps.setInt(i++, d.maxHeight());
                ps.setString(i++, nz(d.createdBy()));
                ps.setString(i++, now);
                ps.setString(i++, nz(d.createdBy()));
                ps.setString(i, now);
                ps.executeUpdate();
            }
            insertRevision(c, id, 1, plan, sha, d.createdBy(), now);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Création du brouillon de plan impossible", e);
        }
        return find(id).orElseThrow();
    }

    // ---- Lecture ----------------------------------------------------------------------------

    public Optional<BuildingPlanDraft> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM building_plan_draft WHERE id = ?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture du brouillon de plan impossible (" + id + ")", e);
        }
    }

    /** Les brouillons non archivés d'un emplacement, le plus récemment modifié d'abord. */
    public List<BuildingPlanDraft> forSite(String agentId, String siteId) {
        List<BuildingPlanDraft> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM building_plan_draft WHERE agent_id = ? AND site_id = ? AND archived = 0 "
                        + "ORDER BY updated_at DESC, created_at DESC")) {
            ps.setString(1, agentId);
            ps.setString(2, siteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des brouillons de " + siteId + " impossible", e);
        }
        return out;
    }

    /** Le brouillon déjà créé depuis cette action, s'il existe : la récupération est idempotente. */
    public Optional<BuildingPlanDraft> recoveredFrom(String actionId) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM building_plan_draft WHERE source_action_id = ? "
                        + "ORDER BY created_at LIMIT 1")) {
            ps.setString(1, nz(actionId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture d'une récupération impossible (" + actionId + ")", e);
        }
    }

    public List<Revision> revisions(String draftId) {
        List<Revision> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM building_plan_draft_revision WHERE draft_id = ? ORDER BY version DESC")) {
            ps.setString(1, draftId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Revision(rs.getString("draft_id"), rs.getInt("version"),
                            rs.getString("plan"), rs.getString("plan_sha"),
                            rs.getString("created_by"), instant(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des révisions impossible (" + draftId + ")", e);
        }
        return out;
    }

    // ---- Modification -----------------------------------------------------------------------

    /**
     * Enregistre un nouveau texte de plan, <strong>seulement</strong> si {@code expectedVersion}
     * est encore la version courante.
     */
    public SaveResult save(String draftId, int expectedVersion, String newPlan, String by) {
        Optional<BuildingPlanDraft> current = find(draftId).filter(d -> !d.archived());
        if (current.isEmpty()) {
            return new SaveResult(SaveOutcome.NOT_FOUND, Optional.empty());
        }
        if (current.get().version() != expectedVersion) {
            return new SaveResult(SaveOutcome.STALE, current);
        }
        String plan = newPlan == null ? "" : newPlan;
        if (plan.equals(current.get().plan())) {
            return new SaveResult(SaveOutcome.UNCHANGED, current);
        }
        String sha = sha256(plan);
        String now = now();
        int next = expectedVersion + 1;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            int changed;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE building_plan_draft SET plan = ?, plan_sha = ?, version = ?, updated_by = ?, "
                            + "updated_at = ? WHERE id = ? AND version = ? AND archived = 0")) {
                ps.setString(1, plan);
                ps.setString(2, sha);
                ps.setInt(3, next);
                ps.setString(4, nz(by));
                ps.setString(5, now);
                ps.setString(6, draftId);
                ps.setInt(7, expectedVersion);
                changed = ps.executeUpdate();
            }
            if (changed != 1) {
                // Quelqu'un est passé entre la lecture et l'écriture : c'est sa version qui compte.
                c.rollback();
                return new SaveResult(SaveOutcome.STALE, find(draftId));
            }
            insertRevision(c, draftId, next, plan, sha, by, now);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Sauvegarde du brouillon impossible (" + draftId + ")", e);
        }
        return new SaveResult(SaveOutcome.SAVED, find(draftId));
    }

    /** Retire un brouillon de la liste, sans rien effacer. */
    public boolean archive(String draftId, String by) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE building_plan_draft SET archived = 1, updated_by = ?, updated_at = ? "
                        + "WHERE id = ? AND archived = 0")) {
            ps.setString(1, nz(by));
            ps.setString(2, now());
            ps.setString(3, nz(draftId));
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Archivage du brouillon impossible (" + draftId + ")", e);
        }
    }

    // ---- Actions rattachées -----------------------------------------------------------------

    /**
     * Rattache une action à un brouillon, pour la version sur laquelle elle a été lancée.
     *
     * <p>Une même action n'appartient qu'à un brouillon : un second rattachement est ignoré.</p>
     */
    public void link(String draftId, String actionId, Check check, int version) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO building_plan_draft_action (action_id, draft_id, kind, version, "
                        + "linked_at) VALUES (?,?,?,?,?)")) {
            ps.setString(1, actionId);
            ps.setString(2, draftId);
            ps.setString(3, check.name());
            ps.setInt(4, version);
            ps.setString(5, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Rattachement de l'action impossible (" + actionId + ")", e);
        }
    }

    /** Le brouillon auquel appartient une action, s'il y en a un. */
    public Optional<Link> linkOf(String actionId) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM building_plan_draft_action WHERE action_id = ?")) {
            ps.setString(1, nz(actionId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readLink(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture du rattachement impossible (" + actionId + ")", e);
        }
    }

    /** Les actions d'un type rattachées à un brouillon, la plus récente d'abord. */
    public List<Link> links(String draftId, Check check) {
        List<Link> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM building_plan_draft_action WHERE draft_id = ? AND kind = ? "
                        + "ORDER BY linked_at DESC, rowid DESC")) {
            ps.setString(1, draftId);
            ps.setString(2, check.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readLink(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des actions du brouillon impossible", e);
        }
        return out;
    }

    // ---- Demandes payantes ------------------------------------------------------------------

    /**
     * Réserve le jeton d'une demande payante <strong>avant</strong> l'appel.
     *
     * <p>La clé primaire fait le travail : la seconde soumission du même formulaire (double clic,
     * rechargement, « renvoyer ») échoue à l'insertion et reçoit le brouillon déjà produit — ou
     * rien s'il est encore en cours — sans jamais rappeler le modèle.</p>
     */
    public Reservation reserve(String token, String by) {
        if (token == null || token.isBlank()) {
            return new Reservation(false, "");
        }
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO building_plan_request (token, draft_id, created_by, created_at) "
                        + "VALUES (?, NULL, ?, ?)")) {
            ps.setString(1, token.trim());
            ps.setString(2, nz(by));
            ps.setString(3, now());
            if (ps.executeUpdate() == 1) {
                return new Reservation(true, "");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Réservation de la demande impossible", e);
        }
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT draft_id FROM building_plan_request WHERE token = ?")) {
            ps.setString(1, token.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return new Reservation(false, rs.next() ? nz(rs.getString(1)) : "");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture de la demande impossible", e);
        }
    }

    /** Note le brouillon produit par une demande, pour qu'une resoumission y mène. */
    public void fulfil(String token, String draftId) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE building_plan_request SET draft_id = ? WHERE token = ?")) {
            ps.setString(1, nz(draftId));
            ps.setString(2, nz(token).trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Clôture de la demande impossible", e);
        }
    }

    // ---- Outils -----------------------------------------------------------------------------

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static void insertRevision(Connection c, String draftId, int version, String plan,
                                       String sha, String by, String at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO building_plan_draft_revision (draft_id, version, plan, plan_sha, created_by, "
                        + "created_at) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, draftId);
            ps.setInt(2, version);
            ps.setString(3, plan);
            ps.setString(4, sha);
            ps.setString(5, nz(by));
            ps.setString(6, at);
            ps.executeUpdate();
        }
    }

    private static BuildingPlanDraft read(ResultSet rs) throws SQLException {
        return new BuildingPlanDraft(rs.getString("id"), rs.getString("agent_id"),
                rs.getString("site_id"), rs.getString("plan"), rs.getString("plan_sha"),
                rs.getInt("version"), BuildingPlanDraft.Origin.parse(rs.getString("origin")),
                rs.getString("parent_id"), rs.getString("source_action_id"),
                rs.getString("provider_id"), rs.getString("provider_label"), rs.getString("model"),
                rs.getString("usage"), rs.getString("raw_response"), rs.getString("extraction_note"),
                rs.getInt("max_width"), rs.getInt("max_height"), rs.getInt("archived") != 0,
                rs.getString("created_by"), instant(rs.getString("created_at")),
                rs.getString("updated_by"), instant(rs.getString("updated_at")));
    }

    private static Link readLink(ResultSet rs) throws SQLException {
        Check check;
        try {
            check = Check.valueOf(rs.getString("kind"));
        } catch (IllegalArgumentException unknown) {
            check = Check.AUDIT;
        }
        return new Link(rs.getString("action_id"), rs.getString("draft_id"), check,
                rs.getInt("version"), instant(rs.getString("linked_at")));
    }

    /**
     * Horodatage à la milliseconde, à largeur fixe : deux gestes dans la même seconde (sauvegarde
     * puis audit) doivent rester ordonnés, et un tri SQL sur le texte doit suivre le temps —
     * {@code Instant.toString()} omet les millisecondes nulles, ce qui casserait ce tri.
     */
    private static String now() {
        return STAMP.format(Instant.now());
    }

    private static Instant instant(String raw) {
        try {
            return raw == null ? Instant.EPOCH : Instant.parse(raw);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement s = c.createStatement()) {
            // Le panel sert plusieurs requêtes à la fois : attendre un verrou plutôt qu'échouer.
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }
}
