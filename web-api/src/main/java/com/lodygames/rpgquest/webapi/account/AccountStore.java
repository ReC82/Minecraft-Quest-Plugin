package com.lodygames.rpgquest.webapi.account;

import com.lodygames.rpgquest.webapi.privacy.ParentalConsentStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Base SQLite des comptes joueurs du portail ({@code accounts.db}, issue #53), distincte de
 * {@code data.db} (jeu), de {@code store.db} (boutique) et de toute base PlugAdmin.
 *
 * <p>Une seule connexion, toutes les méthodes {@code synchronized} : les écritures sont sérialisées,
 * et la consommation d'un code de liaison est une transaction unique (aucune double liaison même si
 * deux requêtes arrivent ensemble). Les instants sont stockés en millisecondes epoch.</p>
 *
 * <p>Jamais stockés en clair : mots de passe (hachés, {@link PasswordHasher}), jetons de session et
 * codes de liaison (empreinte SHA-256, {@link SecureTokens}).</p>
 */
public final class AccountStore implements AutoCloseable {

    private static final int CURRENT_VERSION = 1;

    public record Account(String id, String login, String passwordHash, ParentalConsentStatus consent, Instant createdAt) {
    }

    public record Session(String accountId, String csrfToken, Instant expiresAt) {
    }

    public record Link(UUID playerUuid, String playerName, Instant linkedAt) {
    }

    public record AuditEntry(String action, String playerName, Instant at) {
    }

    private final Connection connection;

    public AccountStore(Path databaseFile) throws IOException, SQLException {
        Path parent = databaseFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        migrate();
    }

    // --- comptes ---------------------------------------------------------------------------

    /** {@code false} si l'identifiant est déjà pris (comparaison insensible à la casse). */
    public synchronized boolean createAccount(Account account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO accounts (id, login, password_hash, consent_status, created_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, account.id());
            statement.setString(2, account.login());
            statement.setString(3, account.passwordHash());
            statement.setString(4, account.consent().name());
            statement.setLong(5, account.createdAt().toEpochMilli());
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                return false;
            }
            throw e;
        }
    }

    public synchronized Optional<Account> findAccountByLogin(String login) throws SQLException {
        return findAccount("SELECT * FROM accounts WHERE login = ? COLLATE NOCASE", login);
    }

    public synchronized Optional<Account> findAccount(String accountId) throws SQLException {
        return findAccount("SELECT * FROM accounts WHERE id = ?", accountId);
    }

    private Optional<Account> findAccount(String sql, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Account(rs.getString("id"), rs.getString("login"), rs.getString("password_hash"),
                        ParentalConsentStatus.valueOf(rs.getString("consent_status")),
                        Instant.ofEpochMilli(rs.getLong("created_at"))));
            }
        }
    }

    // --- sessions --------------------------------------------------------------------------

    public synchronized void createSession(String tokenHash, String accountId, String csrfToken, Instant now,
            Instant expiresAt) throws SQLException {
        try (PreparedStatement purge = connection.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?")) {
            purge.setLong(1, now.toEpochMilli());
            purge.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sessions (token_hash, account_id, csrf_token, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, tokenHash);
            statement.setString(2, accountId);
            statement.setString(3, csrfToken);
            statement.setLong(4, now.toEpochMilli());
            statement.setLong(5, expiresAt.toEpochMilli());
            statement.executeUpdate();
        }
    }

    public synchronized Optional<Session> findSession(String tokenHash, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT account_id, csrf_token, expires_at FROM sessions WHERE token_hash = ? AND expires_at > ?")) {
            statement.setString(1, tokenHash);
            statement.setLong(2, now.toEpochMilli());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Session(rs.getString("account_id"), rs.getString("csrf_token"),
                        Instant.ofEpochMilli(rs.getLong("expires_at"))));
            }
        }
    }

    public synchronized void deleteSession(String tokenHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE token_hash = ?")) {
            statement.setString(1, tokenHash);
            statement.executeUpdate();
        }
    }

    // --- défis de liaison ------------------------------------------------------------------

    public synchronized int countChallengesSince(String accountId, Instant since) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM link_challenges WHERE account_id = ? AND created_at > ?")) {
            statement.setString(1, accountId);
            statement.setLong(2, since.toEpochMilli());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Enregistre un nouveau défi et rend inutilisables les précédents défis du compte. */
    public synchronized void replaceChallenge(String accountId, String codeHash, Instant now, Instant expiresAt)
            throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement purge = connection.prepareStatement(
                    "DELETE FROM link_challenges WHERE expires_at <= ?")) {
                // Les défis de la dernière heure restent comptés pour la limite de création.
                purge.setLong(1, now.minusSeconds(3600).toEpochMilli());
                purge.executeUpdate();
            }
            try (PreparedStatement revoke = connection.prepareStatement(
                    "UPDATE link_challenges SET used_at = ? WHERE account_id = ? AND used_at IS NULL")) {
                revoke.setLong(1, now.toEpochMilli());
                revoke.setString(2, accountId);
                revoke.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO link_challenges (code_hash, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, codeHash);
                insert.setString(2, accountId);
                insert.setLong(3, now.toEpochMilli());
                insert.setLong(4, expiresAt.toEpochMilli());
                insert.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Consomme un code et crée la liaison, en une transaction. Le code est consommé dès qu'il est
     * valide, même si la liaison est ensuite refusée : il ne se rejoue jamais.
     */
    public synchronized LinkOutcome consumeChallenge(String codeHash, UUID playerUuid, String playerName, Instant now)
            throws SQLException {
        return inTransaction(() -> {
            String accountId;
            try (PreparedStatement consume = connection.prepareStatement(
                    "UPDATE link_challenges SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > ? "
                            + "RETURNING account_id")) {
                consume.setLong(1, now.toEpochMilli());
                consume.setString(2, codeHash);
                consume.setLong(3, now.toEpochMilli());
                try (ResultSet rs = consume.executeQuery()) {
                    if (!rs.next()) {
                        return LinkOutcome.INVALID_CODE;
                    }
                    accountId = rs.getString(1);
                }
            }
            Optional<Account> account = findAccount(accountId);
            if (account.isEmpty() || !account.get().consent().allowsPersonalFeatures()) {
                audit(accountId, playerUuid, playerName, "REFUSED_RESTRICTED", now);
                return LinkOutcome.ACCOUNT_RESTRICTED;
            }
            Optional<Link> existing = findLink(accountId);
            if (existing.isPresent()) {
                return existing.get().playerUuid().equals(playerUuid)
                        ? LinkOutcome.LINKED
                        : LinkOutcome.ACCOUNT_ALREADY_LINKED;
            }
            if (isUuidLinked(playerUuid)) {
                audit(accountId, playerUuid, playerName, "REFUSED_UUID_TAKEN", now);
                return LinkOutcome.UUID_ALREADY_LINKED;
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO minecraft_links (account_id, player_uuid, player_name, linked_at) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, accountId);
                insert.setString(2, playerUuid.toString());
                insert.setString(3, playerName);
                insert.setLong(4, now.toEpochMilli());
                insert.executeUpdate();
            }
            audit(accountId, playerUuid, playerName, "LINKED", now);
            return LinkOutcome.LINKED;
        });
    }

    // --- liaisons --------------------------------------------------------------------------

    public synchronized Optional<Link> findLink(String accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, player_name, linked_at FROM minecraft_links WHERE account_id = ?")) {
            statement.setString(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Link(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"),
                        Instant.ofEpochMilli(rs.getLong("linked_at"))));
            }
        }
    }

    /** {@code false} si le compte n'était relié à rien. */
    public synchronized boolean unlink(String accountId, Instant now) throws SQLException {
        return inTransaction(() -> {
            Optional<Link> existing = findLink(accountId);
            if (existing.isEmpty()) {
                return false;
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM minecraft_links WHERE account_id = ?")) {
                delete.setString(1, accountId);
                delete.executeUpdate();
            }
            audit(accountId, existing.get().playerUuid(), existing.get().playerName(), "UNLINKED", now);
            return true;
        });
    }

    public synchronized List<AuditEntry> auditOf(String accountId, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT action, player_name, at FROM link_audit WHERE account_id = ? ORDER BY id DESC LIMIT ?")) {
            statement.setString(1, accountId);
            statement.setInt(2, limit);
            List<AuditEntry> entries = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    entries.add(new AuditEntry(rs.getString("action"), rs.getString("player_name"),
                            Instant.ofEpochMilli(rs.getLong("at"))));
                }
            }
            return entries;
        }
    }

    private boolean isUuidLinked(UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM minecraft_links WHERE player_uuid = ?")) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void audit(String accountId, UUID playerUuid, String playerName, String action, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO link_audit (account_id, player_uuid, player_name, action, at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, accountId);
            statement.setString(2, playerUuid.toString());
            statement.setString(3, playerName);
            statement.setString(4, action);
            statement.setLong(5, now.toEpochMilli());
            statement.executeUpdate();
        }
    }

    // --- infrastructure --------------------------------------------------------------------

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void migrate() throws SQLException {
        int version;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version < 1) {
            applyV1();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + CURRENT_VERSION);
        }
    }

    private void applyV1() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Données minimales (#53, #55) : ni e-mail, ni âge, ni pays — seul le statut de
            // consentement qui découle de la politique d'âge est conservé.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id TEXT PRIMARY KEY,
                        login TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        password_hash TEXT NOT NULL,
                        consent_status TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        token_hash TEXT PRIMARY KEY,
                        account_id TEXT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
                        csrf_token TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS link_challenges (
                        code_hash TEXT PRIMARY KEY,
                        account_id TEXT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        used_at INTEGER
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_link_challenges_account ON link_challenges (account_id)");
            // Une ligne par compte (PRIMARY KEY) et par UUID (UNIQUE) : une identité Minecraft ne peut
            // jamais être reliée à deux comptes actifs à la fois.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS minecraft_links (
                        account_id TEXT PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
                        player_uuid TEXT NOT NULL UNIQUE,
                        player_name TEXT NOT NULL,
                        linked_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS link_audit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        account_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        action TEXT NOT NULL,
                        at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_link_audit_account ON link_audit (account_id)");
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
