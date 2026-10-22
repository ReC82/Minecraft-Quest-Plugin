package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.json.Json;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Le journal IA persistant (issue #739) : chaque requête réellement envoyée à un fournisseur, sa
 * réponse, et les événements qui les relient (validation, réparation, note), rangés par
 * <em>sujet</em> dans la base du panel.
 *
 * <h2>Réutilisable</h2>
 *
 * <p>Le store ne connaît pas les bâtiments : un événement appartient à un {@code scope} (« building »
 * pour l'Atelier) et à un sujet (l'identifiant du projet). Un autre atelier IA — dialogues, contenu —
 * n'a qu'à choisir son scope ; le format, la séquence, la rétention et la persistance sont communs.</p>
 *
 * <h2>Ordre et horodatage</h2>
 *
 * <p>Chaque événement reçoit un numéro de séquence propre à son sujet, attribué à l'écriture sous
 * verrou : l'ordre d'écriture est donc total, même si deux appels se terminent en même temps. Les
 * horodatages (début et fin réels de l'appel) sont conservés à la milliseconde, en UTC.</p>
 *
 * <h2>Rétention sans perte silencieuse</h2>
 *
 * <p>Au-delà de {@link #maxEventsPerSubject()} événements pour un sujet, les plus anciens sont
 * retirés — et un événement {@code RETENTION} dit lesquels, quand et pourquoi. Le plafond est
 * réglable ({@code -Drpgquest.ai.journal.maxEvents}).</p>
 *
 * <p>Table créée si absente : migration additive, aucune table existante n'est modifiée.</p>
 */
public final class AiJournalStore {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS ai_journal_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scope TEXT NOT NULL,
                subject_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                kind TEXT NOT NULL,
                call_id TEXT NOT NULL DEFAULT '',
                run_id TEXT NOT NULL DEFAULT '',
                operation TEXT NOT NULL DEFAULT '',
                attempt INTEGER NOT NULL DEFAULT 0,
                draft_id TEXT NOT NULL DEFAULT '',
                parent_ref TEXT NOT NULL DEFAULT '',
                at TEXT NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT -1,
                actor TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '{}',
                UNIQUE (scope, subject_id, seq)
            )
            """;
    private static final String CREATE_META = """
            CREATE TABLE IF NOT EXISTS ai_journal_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """;

    /** Plafond par défaut : des centaines de sessions de conception avant toute rotation. */
    public static final int DEFAULT_MAX_EVENTS = 5_000;

    /**
     * Un événement à écrire.
     *
     * @param at         l'instant de l'événement (début réel d'une requête, fin réelle d'une réponse)
     * @param durationMs durée de l'appel ou de l'opération, -1 si sans objet
     * @param parentRef  le lien explicite vers ce qui l'a provoqué (version corrigée, appel précédent)
     * @param body       le détail, sérialisé en JSON tel quel
     */
    public record NewEvent(String kind, String callId, String runId, String operation, int attempt,
                           String draftId, String parentRef, Instant at, long durationMs, String actor,
                           String summary, Map<String, Object> body) {
    }

    /** Un événement relu. */
    public record Event(long id, String scope, String subjectId, long seq, String kind, String callId,
                        String runId, String operation, int attempt, String draftId, String parentRef,
                        Instant at, long durationMs, String actor, String summary, Map<String, Object> body) {
    }

    private final String jdbcUrl;
    private final int maxEvents;

    public AiJournalStore(String dbPath) {
        this(dbPath, Integer.getInteger("rpgquest.ai.journal.maxEvents", DEFAULT_MAX_EVENTS));
    }

    public AiJournalStore(String dbPath, int maxEventsPerSubject) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        this.maxEvents = Math.max(50, maxEventsPerSubject);
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(CREATE);
            s.execute(CREATE_META);
            s.execute("CREATE INDEX IF NOT EXISTS idx_ai_journal_subject ON ai_journal_event(scope, subject_id, seq)");
            // La date d'installation : tout ce qui précède n'a jamais été journalisé, et l'export le dit.
            s.execute("INSERT OR IGNORE INTO ai_journal_meta (key, value) VALUES ('installed_at', '"
                    + Instant.now() + "')");
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser le journal IA dans " + dbPath, e);
        }
    }

    public int maxEventsPerSubject() {
        return maxEvents;
    }

    /** L'instant où le journal a été installé dans cette base. */
    public Instant installedAt() {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM ai_journal_meta WHERE key = 'installed_at'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Instant.parse(rs.getString(1)) : Instant.EPOCH;
            }
        } catch (SQLException | RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    /**
     * Ajoute un événement et rend son numéro de séquence. Sérialisé : deux écritures concurrentes
     * sur le même sujet ne peuvent pas obtenir le même numéro.
     */
    public synchronized long append(String scope, String subjectId, NewEvent e) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            long seq = insert(c, scope, subjectId, e);
            long count = count(c, scope, subjectId);
            if (count > maxEvents) {
                rotate(c, scope, subjectId, count - maxEvents);
            }
            c.commit();
            return seq;
        } catch (SQLException ex) {
            throw new IllegalStateException("Écriture impossible dans le journal IA", ex);
        }
    }

    private long insert(Connection c, String scope, String subjectId, NewEvent e) throws SQLException {
        long seq;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM ai_journal_event WHERE scope = ? AND subject_id = ?")) {
            ps.setString(1, scope);
            ps.setString(2, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                seq = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ai_journal_event (scope, subject_id, seq, "
                + "kind, call_id, run_id, operation, attempt, draft_id, parent_ref, at, duration_ms, actor, summary, "
                + "body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, scope);
            ps.setString(2, subjectId);
            ps.setLong(3, seq);
            ps.setString(4, nz(e.kind()));
            ps.setString(5, nz(e.callId()));
            ps.setString(6, nz(e.runId()));
            ps.setString(7, nz(e.operation()));
            ps.setInt(8, e.attempt());
            ps.setString(9, nz(e.draftId()));
            ps.setString(10, nz(e.parentRef()));
            ps.setString(11, (e.at() == null ? Instant.now() : e.at()).toString());
            ps.setLong(12, e.durationMs());
            ps.setString(13, nz(e.actor()));
            ps.setString(14, nz(e.summary()));
            ps.setString(15, Json.write(e.body() == null ? Map.of() : e.body()));
            ps.executeUpdate();
        }
        return seq;
    }

    /** Retire les plus anciens événements d'un sujet, et le DIT par un événement RETENTION. */
    private void rotate(Connection c, String scope, String subjectId, long excess) throws SQLException {
        long first;
        long last;
        try (PreparedStatement ps = c.prepareStatement("SELECT MIN(seq), MAX(seq) FROM (SELECT seq FROM "
                + "ai_journal_event WHERE scope = ? AND subject_id = ? ORDER BY seq LIMIT ?)")) {
            ps.setString(1, scope);
            ps.setString(2, subjectId);
            ps.setLong(3, excess);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                first = rs.getLong(1);
                last = rs.getLong(2);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM ai_journal_event WHERE scope = ? AND subject_id = ? AND seq BETWEEN ? AND ?")) {
            ps.setString(1, scope);
            ps.setString(2, subjectId);
            ps.setLong(3, first);
            ps.setLong(4, last);
            ps.executeUpdate();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("removedFromSeq", first);
        body.put("removedToSeq", last);
        body.put("maxEvents", maxEvents);
        insert(c, scope, subjectId, new NewEvent("RETENTION", "", "", "", 0, "", "", Instant.now(), -1, "panel",
                "Rétention : événements n° " + first + " à " + last + " retirés (plafond de " + maxEvents
                        + " événements par sujet).", body));
    }

    private long count(Connection c, String scope, String subjectId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM ai_journal_event WHERE scope = ? AND subject_id = ?")) {
            ps.setString(1, scope);
            ps.setString(2, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Tous les événements d'un sujet, dans l'ordre de séquence. */
    public List<Event> events(String scope, String subjectId) {
        List<Event> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM ai_journal_event WHERE scope = ? AND subject_id = ? ORDER BY seq")) {
            ps.setString(1, scope);
            ps.setString(2, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture impossible du journal IA", e);
        }
        return out;
    }

    /** Le dernier événement d'un sujet, s'il y en a. */
    public Optional<Event> last(String scope, String subjectId) {
        List<Event> all = events(scope, subjectId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    private static Event read(ResultSet rs) throws SQLException {
        Map<String, Object> body;
        try {
            body = Json.parseObject(rs.getString("body"));
        } catch (RuntimeException unreadable) {
            body = Map.of("unreadable", rs.getString("body"));
        }
        return new Event(rs.getLong("id"), rs.getString("scope"), rs.getString("subject_id"), rs.getLong("seq"),
                rs.getString("kind"), rs.getString("call_id"), rs.getString("run_id"), rs.getString("operation"),
                rs.getInt("attempt"), rs.getString("draft_id"), rs.getString("parent_ref"),
                Instant.parse(rs.getString("at")), rs.getLong("duration_ms"), rs.getString("actor"),
                rs.getString("summary"), body);
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
