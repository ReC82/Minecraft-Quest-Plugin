package com.lodygames.rpgquest.panel.ai;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stockage de la configuration des fournisseurs d'IA (issue #146), dans la base locale du panel.
 *
 * <p><strong>Pourquoi la base et pas un fichier de configuration.</strong> Le ticket demande une
 * configuration depuis le panel, donc modifiable sans accès SSH ni redéploiement. La base du panel
 * vit dans {@code /var/lib/plugadmin/control-panel.db}, <strong>hors du dépôt Git</strong> et en
 * mode {@code 600} appartenant au seul utilisateur du service : la clé ne peut donc ni entrer dans
 * Git, ni être lue par un autre compte de la machine. Un fichier {@code .properties} aurait le même
 * niveau de protection mais ne serait pas éditable depuis l'interface.</p>
 *
 * <p>Migration : un seul {@code CREATE TABLE IF NOT EXISTS} idempotent et additif, comme
 * {@code SqliteUserRepository}, {@code AgentStore} et {@code SqliteAuditLog}. Une connexion par
 * opération — le volume est de l'ordre de trois lignes.</p>
 */
public final class AiSettingsStore {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS ai_provider (
                provider_id TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL DEFAULT 0,
                api_key TEXT NOT NULL DEFAULT '',
                base_url TEXT NOT NULL DEFAULT '',
                model TEXT NOT NULL DEFAULT '',
                max_output_tokens INTEGER NOT NULL DEFAULT 4000,
                timeout_seconds INTEGER NOT NULL DEFAULT 90,
                updated_at TEXT,
                updated_by TEXT
            )
            """;

    private final String jdbcUrl;

    public AiSettingsStore(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(CREATE);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser la table ai_provider dans " + dbPath, e);
        }
    }

    /** Réglages d'un fournisseur, ou des réglages vides s'il n'a jamais été configuré. */
    public AiProviderSettings get(String providerId) {
        String sql = "SELECT enabled, api_key, base_url, model, max_output_tokens, timeout_seconds "
                + "FROM ai_provider WHERE provider_id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, providerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return AiProviderSettings.empty(providerId);
                }
                return new AiProviderSettings(providerId, rs.getInt("enabled") == 1,
                        rs.getString("api_key"), rs.getString("base_url"), rs.getString("model"),
                        rs.getInt("max_output_tokens"), rs.getInt("timeout_seconds"));
            }
        } catch (SQLException e) {
            // Une base illisible ne doit pas casser la page : le fournisseur apparaît non configuré.
            return AiProviderSettings.empty(providerId);
        }
    }

    public Map<String, AiProviderSettings> all(Iterable<String> providerIds) {
        Map<String, AiProviderSettings> out = new LinkedHashMap<>();
        for (String id : providerIds) {
            out.put(id, get(id));
        }
        return out;
    }

    /**
     * Enregistre les réglages. {@code apiKey} vide ou {@code null} <strong>conserve la clé
     * existante</strong> : le formulaire ne réaffiche jamais la clé, donc un champ laissé vide
     * signifie « ne pas y toucher », jamais « effacer ». Effacer se fait par
     * {@link #clearKey(String, String)}, un geste explicite.
     */
    public void save(AiProviderSettings settings, String username) {
        String existingKey = get(settings.providerId()).apiKey();
        String key = settings.apiKey().isEmpty() ? existingKey : settings.apiKey();
        String sql = """
                INSERT INTO ai_provider (provider_id, enabled, api_key, base_url, model,
                                         max_output_tokens, timeout_seconds, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(provider_id) DO UPDATE SET
                    enabled = excluded.enabled,
                    api_key = excluded.api_key,
                    base_url = excluded.base_url,
                    model = excluded.model,
                    max_output_tokens = excluded.max_output_tokens,
                    timeout_seconds = excluded.timeout_seconds,
                    updated_at = excluded.updated_at,
                    updated_by = excluded.updated_by
                """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, settings.providerId());
            ps.setInt(2, settings.enabled() ? 1 : 0);
            ps.setString(3, key);
            ps.setString(4, settings.baseUrl());
            ps.setString(5, settings.model());
            ps.setInt(6, settings.maxOutputTokens());
            ps.setInt(7, settings.timeoutSeconds());
            ps.setString(8, java.time.Instant.now().toString());
            ps.setString(9, username == null ? "" : username);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'enregistrer les réglages du fournisseur "
                    + settings.providerId(), e);
        }
    }

    /** Efface la clé sans toucher au reste, et désactive le fournisseur — sans clé il est inutile. */
    public void clearKey(String providerId, String username) {
        AiProviderSettings current = get(providerId);
        String sql = """
                INSERT INTO ai_provider (provider_id, enabled, api_key, base_url, model,
                                         max_output_tokens, timeout_seconds, updated_at, updated_by)
                VALUES (?, 0, '', ?, ?, ?, ?, ?, ?)
                ON CONFLICT(provider_id) DO UPDATE SET
                    enabled = 0, api_key = '', updated_at = excluded.updated_at,
                    updated_by = excluded.updated_by
                """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, providerId);
            ps.setString(2, current.baseUrl());
            ps.setString(3, current.model());
            ps.setInt(4, current.maxOutputTokens());
            ps.setInt(5, current.timeoutSeconds());
            ps.setString(6, java.time.Instant.now().toString());
            ps.setString(7, username == null ? "" : username);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'effacer la clé du fournisseur " + providerId, e);
        }
    }

    /** Qui a modifié ce fournisseur, et quand — pour l'affichage, jamais la clé. */
    public String lastChange(String providerId) {
        String sql = "SELECT updated_at, updated_by FROM ai_provider WHERE provider_id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, providerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString("updated_at") == null) {
                    return "";
                }
                String by = rs.getString("updated_by");
                return rs.getString("updated_at") + (by == null || by.isBlank() ? "" : " par " + by);
            }
        } catch (SQLException e) {
            return "";
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
