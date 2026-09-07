package com.lodygames.rpgquest.panel.agent;

import com.lodygames.rpgquest.panel.json.Json;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistance de l'état des agents (issue #51, phase 6) : dernier heartbeat par agent et file
 * d'actions/résultats. Base <strong>propre à PlugAdmin</strong> — {@code control-panel.db}, la même
 * que le journal d'audit — jamais {@code data.db}. Migrations idempotentes
 * ({@code CREATE TABLE IF NOT EXISTS}).
 *
 * <p>Un {@link Connection} par opération, comme {@code SqliteAuditLog} : volume faible, simplicité
 * de fermeture.</p>
 */
public final class AgentStore {

    private static final String CREATE_HEARTBEAT = """
            CREATE TABLE IF NOT EXISTS agent_heartbeat (
                agent_id TEXT PRIMARY KEY,
                environment TEXT,
                received_at TEXT NOT NULL,
                generated_at TEXT,
                protocol TEXT,
                plugin_name TEXT,
                plugin_version TEXT,
                server_state TEXT,
                players_online INTEGER,
                max_players INTEGER,
                uptime_seconds INTEGER,
                worlds_json TEXT,
                raw_json TEXT
            )
            """;
    private static final String CREATE_ACTION = """
            CREATE TABLE IF NOT EXISTS agent_action (
                id TEXT PRIMARY KEY,
                agent_id TEXT NOT NULL,
                type TEXT NOT NULL,
                params_json TEXT,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                created_by TEXT,
                delivered_at TEXT,
                deliver_count INTEGER NOT NULL DEFAULT 0,
                completed_at TEXT,
                result_status TEXT,
                result_value TEXT,
                result_message TEXT,
                result_json TEXT
            )
            """;
    private static final String CREATE_ACTION_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_agent_action_agent_status ON agent_action(agent_id, status)";

    private final String jdbcUrl;

    public AgentStore(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_HEARTBEAT);
            statement.execute(CREATE_ACTION);
            statement.execute(CREATE_ACTION_INDEX);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser les tables agent dans " + dbPath, e);
        }
    }

    // ---- Heartbeat -------------------------------------------------------------------

    public void saveHeartbeat(HeartbeatRecord hb) {
        String sql = """
                INSERT INTO agent_heartbeat
                    (agent_id, environment, received_at, generated_at, protocol, plugin_name, plugin_version,
                     server_state, players_online, max_players, uptime_seconds, worlds_json, raw_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(agent_id) DO UPDATE SET
                    environment=excluded.environment, received_at=excluded.received_at,
                    generated_at=excluded.generated_at, protocol=excluded.protocol,
                    plugin_name=excluded.plugin_name, plugin_version=excluded.plugin_version,
                    server_state=excluded.server_state, players_online=excluded.players_online,
                    max_players=excluded.max_players, uptime_seconds=excluded.uptime_seconds,
                    worlds_json=excluded.worlds_json, raw_json=excluded.raw_json
                """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, hb.agentId());
            ps.setString(2, hb.environment());
            ps.setString(3, hb.receivedAt().toString());
            ps.setString(4, hb.generatedAt());
            ps.setString(5, hb.protocol());
            ps.setString(6, hb.pluginName());
            ps.setString(7, hb.pluginVersion());
            ps.setString(8, hb.serverState());
            ps.setLong(9, hb.playersOnline());
            ps.setLong(10, hb.maxPlayers());
            ps.setLong(11, hb.uptimeSeconds());
            ps.setString(12, hb.worldsJson());
            ps.setString(13, hb.rawJson());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Écriture du heartbeat impossible (" + hb.agentId() + ")", e);
        }
    }

    public Optional<HeartbeatRecord> latestHeartbeat(String agentId) {
        String sql = "SELECT * FROM agent_heartbeat WHERE agent_id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readHeartbeat(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture du heartbeat impossible (" + agentId + ")", e);
        }
    }

    // ---- Actions --------------------------------------------------------------------

    /** Crée une action PENDING pour une cible précise. Renvoie l'identifiant généré. */
    public String createAction(String agentId, String type, Map<String, String> params, String createdBy) {
        String id = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO agent_action (id, agent_id, type, params_json, status, created_at, created_by, deliver_count)
                VALUES (?,?,?,?,?,?,?,0)
                """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, agentId);
            ps.setString(3, type);
            ps.setString(4, Json.write(new LinkedHashMap<String, Object>(params == null ? Map.of() : params)));
            ps.setString(5, AgentActionStatus.PENDING.name());
            ps.setString(6, isoSeconds(Instant.now()));
            ps.setString(7, createdBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Création d'action impossible", e);
        }
        return id;
    }

    /**
     * Actions à livrer à {@code agentId} : PENDING ou DELIVERED non terminales et non expirées.
     * Effet de bord : PENDING → DELIVERED (horodatage + compteur), et les actions plus vieilles que
     * {@code expiry} sans résultat passent EXPIRED (et ne sont pas renvoyées).
     */
    public List<AgentActionRow> deliverableActions(String agentId, Instant now, Duration expiry) {
        expireStale(agentId, now, expiry);
        // Marquer d'abord (DELIVERED + horodatage + compteur), puis relire : les lignes renvoyées
        // reflètent la livraison courante.
        String markSql = """
                UPDATE agent_action
                   SET status='DELIVERED',
                       delivered_at=COALESCE(delivered_at, ?),
                       deliver_count=deliver_count+1
                 WHERE agent_id = ? AND status IN ('PENDING','DELIVERED')
                """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(markSql)) {
            ps.setString(1, now.toString());
            ps.setString(2, agentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Marquage DELIVERED impossible (" + agentId + ")", e);
        }
        String select = "SELECT * FROM agent_action WHERE agent_id = ? AND status = 'DELIVERED' "
                + "AND completed_at IS NULL ORDER BY created_at";
        List<AgentActionRow> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(select)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(readAction(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture de la file d'actions impossible (" + agentId + ")", e);
        }
        return rows;
    }

    /**
     * Enregistre le résultat d'une action. Refuse si l'action est inconnue ou n'appartient pas à
     * {@code agentId}. Si l'action est déjà terminale, ne fait rien et renvoie {@code true}
     * (idempotence : un renvoi de résultat après timeout est accepté sans re-traitement).
     *
     * @return {@code true} si le résultat a été accepté (ou déjà présent), {@code false} si l'action
     *     n'existe pas ou vise un autre agent.
     */
    public boolean recordResult(String actionId, String agentId, AgentActionStatus status,
                                String value, String message, String rawJson, Instant now) {
        Optional<AgentActionRow> existing = action(actionId);
        if (existing.isEmpty() || !existing.get().agentId().equals(agentId)) {
            return false;
        }
        if (existing.get().status().terminal()) {
            return true;
        }
        String sql = """
                UPDATE agent_action
                   SET status = ?, result_status = ?, result_value = ?, result_message = ?,
                       result_json = ?, completed_at = ?
                 WHERE id = ? AND agent_id = ?
                """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, status.name());
            ps.setString(3, value);
            ps.setString(4, message);
            ps.setString(5, rawJson);
            ps.setString(6, now.toString());
            ps.setString(7, actionId);
            ps.setString(8, agentId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Écriture du résultat d'action impossible (" + actionId + ")", e);
        }
    }

    public Optional<AgentActionRow> action(String id) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("SELECT * FROM agent_action WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readAction(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture d'action impossible (" + id + ")", e);
        }
    }

    /** Dernière action d'un type donné pour un agent (tous statuts) — pour réafficher un résultat de liste/état. */
    public Optional<AgentActionRow> latestActionOfType(String agentId, String type) {
        String sql = "SELECT * FROM agent_action WHERE agent_id = ? AND type = ? ORDER BY created_at DESC LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readAction(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture de la dernière action « " + type + " » impossible (" + agentId + ")", e);
        }
    }

    public List<AgentActionRow> recentActions(String agentId, int limit) {
        String sql = "SELECT * FROM agent_action WHERE agent_id = ? ORDER BY created_at DESC LIMIT ?";
        List<AgentActionRow> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(readAction(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture de l'historique d'actions impossible (" + agentId + ")", e);
        }
        return rows;
    }

    private void expireStale(String agentId, Instant now, Duration expiry) {
        String sql = "UPDATE agent_action SET status='EXPIRED', completed_at=? "
                + "WHERE agent_id=? AND status IN ('PENDING','DELIVERED') AND created_at < ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, isoSeconds(now));
            ps.setString(2, agentId);
            // Borne normalisée à la seconde : created_at l'est aussi, la comparaison de chaînes
            // ISO reste alors lexicographiquement correcte (pas de mélange de précisions).
            ps.setString(3, isoSeconds(now.minus(expiry)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Expiration des actions impossible (" + agentId + ")", e);
        }
    }

    // ---- Mapping ------------------------------------------------------------------

    private static HeartbeatRecord readHeartbeat(ResultSet rs) throws SQLException {
        return new HeartbeatRecord(
                rs.getString("agent_id"),
                rs.getString("environment"),
                Instant.parse(rs.getString("received_at")),
                rs.getString("generated_at"),
                rs.getString("protocol"),
                rs.getString("plugin_name"),
                rs.getString("plugin_version"),
                rs.getString("server_state"),
                rs.getLong("players_online"),
                rs.getLong("max_players"),
                rs.getLong("uptime_seconds"),
                rs.getString("worlds_json"),
                rs.getString("raw_json"));
    }

    private static AgentActionRow readAction(ResultSet rs) throws SQLException {
        return new AgentActionRow(
                rs.getString("id"),
                rs.getString("agent_id"),
                rs.getString("type"),
                parseParams(rs.getString("params_json")),
                parseStatus(rs.getString("status")),
                Instant.parse(rs.getString("created_at")),
                rs.getString("created_by"),
                instantOrNull(rs.getString("delivered_at")),
                rs.getInt("deliver_count"),
                instantOrNull(rs.getString("completed_at")),
                rs.getString("result_status"),
                rs.getString("result_value"),
                rs.getString("result_message"),
                rs.getString("result_json"));
    }

    private static Map<String, String> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = Json.parseObject(json);
            Map<String, String> params = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (v != null) {
                    params.put(k, String.valueOf(v));
                }
            });
            return params;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static AgentActionStatus parseStatus(String raw) {
        try {
            return AgentActionStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return AgentActionStatus.FAILED;
        }
    }

    private static Instant instantOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Instant tronqué à la seconde → chaîne ISO de largeur fixe, lexicographiquement ordonnable. */
    private static String isoSeconds(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
