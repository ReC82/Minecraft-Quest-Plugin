package com.lodygames.rpgquest.panel.building;

import com.lodygames.rpgquest.panel.json.Json;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Les sessions de « conception automatique » d'un projet d'Atelier (issue #282), dans la base du
 * panel.
 *
 * <h2>Pourquoi persister une boucle</h2>
 *
 * <p>Une conception automatique enchaîne plusieurs appels payants sur une ou deux minutes. Si le
 * panel redémarre au milieu, il faut savoir ce qui a été dépensé, quelles versions ont été
 * produites, et <strong>ne pas</strong> reprendre seul : une session interrompue est marquée comme
 * telle au démarrage, et seule une décision humaine en relance une autre. Chaque passe est aussi
 * écrite au fil de l'eau — c'est le journal que l'écran montre, avec les jetons consommés.</p>
 *
 * <p>Le jeton de demande est unique : un double clic ou un rechargement ne lance jamais deux
 * sessions, donc jamais deux enveloppes.</p>
 */
public final class BuildingDesignRunStore {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS building_design_run (
                id TEXT PRIMARY KEY,
                agent_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                request_token TEXT NOT NULL UNIQUE,
                provider_id TEXT NOT NULL,
                quality TEXT NOT NULL,
                max_calls INTEGER NOT NULL,
                spent_calls INTEGER NOT NULL DEFAULT 0,
                input_tokens INTEGER NOT NULL DEFAULT 0,
                output_tokens INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                outcome TEXT NOT NULL DEFAULT '',
                final_draft_id TEXT NOT NULL DEFAULT '',
                passes TEXT NOT NULL DEFAULT '[]',
                created_by TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """;
    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_design_run_project ON building_design_run(project_id, created_at)";

    /** L'état d'une session. */
    public enum Status {
        RUNNING("En cours"),
        SUCCEEDED("Terminée : plan conforme"),
        STOPPED("Arrêtée"),
        INTERRUPTED("Interrompue par un redémarrage du panel");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static Status parse(String raw) {
            for (Status s : values()) {
                if (s.name().equals(raw)) {
                    return s;
                }
            }
            return STOPPED;
        }
    }

    /**
     * Une passe de la session, telle que l'écran la restitue.
     *
     * @param kind    CONCEPTION, CORRECTION, CRITIQUE, MOTEUR ou VÉRIFICATION
     * @param draftId la version produite ou jugée
     * @param verdict ce que le serveur en a dit, en français
     * @param tokensIn  jetons d'entrée facturés par cette passe (0 si gratuite)
     */
    public record Pass(String kind, String draftId, int version, String model, int tokensIn,
                       int tokensOut, String verdict) {
    }

    public record Run(String id, String agentId, String projectId, String providerId, String quality,
                      int maxCalls, int spentCalls, int inputTokens, int outputTokens, Status status,
                      String outcome, String finalDraftId, List<Pass> passes, String createdBy,
                      Instant createdAt, Instant updatedAt) {

        public Run {
            passes = List.copyOf(passes);
        }

        public boolean running() {
            return status == Status.RUNNING;
        }
    }

    private final String jdbcUrl;

    public BuildingDesignRunStore(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(CREATE);
            s.execute(CREATE_INDEX);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser les conceptions automatiques dans "
                    + dbPath, e);
        }
    }

    /**
     * Réserve une session. Vide si ce jeton a déjà servi : rien n'est lancé deux fois.
     */
    public Optional<Run> start(String agentId, String projectId, String token, String providerId,
                               String quality, int maxCalls, String by) {
        String id = UUID.randomUUID().toString();
        String now = STAMP.format(Instant.now());
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO building_design_run (id, agent_id, project_id, request_token, "
                        + "provider_id, quality, max_calls, status, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, agentId);
            ps.setString(3, projectId);
            ps.setString(4, token);
            ps.setString(5, providerId);
            ps.setString(6, quality);
            ps.setInt(7, maxCalls);
            ps.setString(8, Status.RUNNING.name());
            ps.setString(9, by);
            ps.setString(10, now);
            ps.setString(11, now);
            if (ps.executeUpdate() == 0) {
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Démarrage d'une conception impossible", e);
        }
        return find(id);
    }

    public Optional<Run> byToken(String token) {
        return one("SELECT * FROM building_design_run WHERE request_token = ?", token);
    }

    public Optional<Run> find(String id) {
        return one("SELECT * FROM building_design_run WHERE id = ?", id);
    }

    /** La session la plus récente d'un projet. */
    public Optional<Run> latest(String projectId) {
        return one("SELECT * FROM building_design_run WHERE project_id = ? ORDER BY created_at DESC, "
                + "rowid DESC LIMIT 1", projectId);
    }

    /** Une passe de plus, et la dépense qu'elle représente — écrite au fil de l'eau. */
    public void addPass(String runId, Pass pass, boolean paid) {
        Run run = find(runId).orElseThrow();
        List<Pass> passes = new ArrayList<>(run.passes());
        passes.add(pass);
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE building_design_run SET passes = ?, spent_calls = spent_calls + ?, "
                        + "input_tokens = input_tokens + ?, output_tokens = output_tokens + ?, "
                        + "updated_at = ? WHERE id = ?")) {
            ps.setString(1, writePasses(passes));
            ps.setInt(2, paid ? 1 : 0);
            ps.setInt(3, Math.max(0, pass.tokensIn()));
            ps.setInt(4, Math.max(0, pass.tokensOut()));
            ps.setString(5, STAMP.format(Instant.now()));
            ps.setString(6, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Journal de conception impossible à écrire", e);
        }
    }

    public void finish(String runId, Status status, String outcome, String finalDraftId) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE building_design_run SET status = ?, outcome = ?, final_draft_id = ?, "
                        + "updated_at = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, outcome == null ? "" : outcome);
            ps.setString(3, finalDraftId == null ? "" : finalDraftId);
            ps.setString(4, STAMP.format(Instant.now()));
            ps.setString(5, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Fin de conception impossible à écrire", e);
        }
    }

    /**
     * Au démarrage du panel : toute session encore « en cours » a perdu son fil. Elle est marquée
     * interrompue, et <strong>pas</strong> relancée — une dépense ne reprend jamais sans un humain.
     */
    public int interruptOrphans() {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE building_design_run SET status = ?, outcome = ?, updated_at = ? WHERE status = ?")) {
            ps.setString(1, Status.INTERRUPTED.name());
            ps.setString(2, "Le panel a redémarré pendant la conception : rien n'a été relancé "
                    + "automatiquement. Les versions déjà produites sont conservées.");
            ps.setString(3, STAMP.format(Instant.now()));
            ps.setString(4, Status.RUNNING.name());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Reprise des conceptions impossible", e);
        }
    }

    private Optional<Run> one(String sql, String arg) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg == null ? "" : arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture d'une conception impossible", e);
        }
    }

    private static Run read(ResultSet rs) throws SQLException {
        return new Run(rs.getString("id"), rs.getString("agent_id"), rs.getString("project_id"),
                rs.getString("provider_id"), rs.getString("quality"), rs.getInt("max_calls"),
                rs.getInt("spent_calls"), rs.getInt("input_tokens"), rs.getInt("output_tokens"),
                Status.parse(rs.getString("status")), rs.getString("outcome"),
                rs.getString("final_draft_id"), readPasses(rs.getString("passes")),
                rs.getString("created_by"), parse(rs.getString("created_at")),
                parse(rs.getString("updated_at")));
    }

    private static String writePasses(List<Pass> passes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pass p : passes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", p.kind());
            m.put("draftId", p.draftId());
            m.put("version", p.version());
            m.put("model", p.model());
            m.put("in", p.tokensIn());
            m.put("out", p.tokensOut());
            m.put("verdict", p.verdict());
            out.add(m);
        }
        return Json.write(Map.of("passes", out));
    }

    private static List<Pass> readPasses(String raw) {
        List<Pass> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.startsWith("[")) {
            return out;
        }
        Object list = Json.parseObject(raw).get("passes");
        if (list instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> m) {
                    out.add(new Pass(str(m.get("kind")), str(m.get("draftId")), num(m.get("version")),
                            str(m.get("model")), num(m.get("in")), num(m.get("out")),
                            str(m.get("verdict"))));
                }
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static Instant parse(String raw) {
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
