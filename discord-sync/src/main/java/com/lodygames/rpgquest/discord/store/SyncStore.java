package com.lodygames.rpgquest.discord.store;

import java.io.IOException;
import java.io.UncheckedIOException;
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

/**
 * État durable de la synchronisation (issue #202) : base SQLite dédiée,
 * <strong>totalement séparée</strong> de {@code data.db}, {@code store.db} et
 * {@code control-panel.db}.
 *
 * <p><strong>C'est cette classe qui garantit « un sujet = une issue ».</strong> L'ordre des
 * écritures est la garantie, pas un verrou : l'<em>intention</em> de créer est enregistrée et
 * validée <strong>avant</strong> l'appel réseau. Donc, si le service meurt pendant la création —
 * ou si l'appel se termine par une coupure sans réponse — il reste au redémarrage une ligne à
 * l'état {@link Link#STATE_CREATING} avec {@code attempts > 0}. Le service sait alors qu'une
 * création a <em>peut-être</em> abouti et doit <strong>réconcilier avant de recréer</strong>, ce
 * qu'il fait en relisant les issues et en cherchant son marqueur de sujet.</p>
 *
 * <p>Les migrations sont <strong>séquentielles et idempotentes</strong>, comme ailleurs dans le
 * projet : relancer le service sur une base déjà à jour ne fait rien.</p>
 */
public final class SyncStore implements AutoCloseable {

    /** Version de schéma attendue par ce code. */
    public static final int SCHEMA_VERSION = 1;

    private final Connection connection;

    /**
     * Lien durable entre un sujet du forum et une issue.
     *
     * @param threadId    identifiant du sujet Discord (clé)
     * @param issueNumber numéro d'issue, ou {@code null} tant que la création n'est pas confirmée
     * @param state       {@link #STATE_CREATING} ou {@link #STATE_LINKED}
     * @param titleHash   empreinte du dernier titre synchronisé, pour détecter une modification
     * @param bodyHash    empreinte du dernier message initial synchronisé
     * @param lastStatus  dernier statut <em>annoncé dans le sujet</em>, pour ne pas répéter
     * @param linkPosted  le message contenant le lien de l'issue a-t-il déjà été publié
     * @param attempts    nombre de tentatives de création engagées
     * @param lastError   dernière erreur lisible, ou {@code null}
     * @param scanFrom    plus grand numéro d'issue connu <em>juste avant</em> la tentative de
     *                    création. L'issue éventuellement créée porte forcément un numéro
     *                    supérieur : c'est le point de départ sûr du balayage de réconciliation.
     *                    Le relever après coup ne marcherait pas, car un relevé ultérieur peut
     *                    avoir déjà intégré l'issue cherchée et donc l'avoir dépassée.
     * @param lastAttemptAt instant de la dernière tentative de création engagée, ou {@code null}.
     *                    Sert au délai de prudence avant de conclure qu'aucune issue n'existe :
     *                    la liste des issues de GitHub n'est pas cohérente juste après une
     *                    création (constat mesuré, voir {@code docs/discord-sync/README.md}).
     */
    public record Link(
            String threadId,
            Integer issueNumber,
            String state,
            String titleHash,
            String bodyHash,
            String lastStatus,
            boolean linkPosted,
            int attempts,
            String lastError,
            Instant lastAttemptAt,
            Integer scanFrom) {

        /** Création engagée mais non confirmée : état ambigu, à réconcilier. */
        public static final String STATE_CREATING = "CREATING";

        /** Sujet et issue durablement appariés. */
        public static final String STATE_LINKED = "LINKED";

        public boolean linked() {
            return STATE_LINKED.equals(state) && issueNumber != null;
        }

        /** Une création a-t-elle déjà été engagée (donc peut-être aboutie) ? */
        public boolean creationAmbiguous() {
            return !linked() && attempts > 0;
        }
    }

    public SyncStore(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Création impossible du dossier de " + databasePath, e);
        }
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
            }
            migrate();
        } catch (SQLException e) {
            throw new StoreException("Ouverture impossible de la base d'état " + databasePath
                    + " : " + e.getMessage(), e);
        }
    }

    // ---- Schéma ----------------------------------------------------------------------------

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sync_state (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS forum_link (
                        thread_id    TEXT PRIMARY KEY,
                        guild_id     TEXT NOT NULL,
                        channel_id   TEXT NOT NULL,
                        issue_number INTEGER,
                        state        TEXT NOT NULL,
                        title_hash   TEXT,
                        body_hash    TEXT,
                        last_status  TEXT,
                        link_posted  INTEGER NOT NULL DEFAULT 0,
                        attempts     INTEGER NOT NULL DEFAULT 0,
                        last_error   TEXT,
                        last_attempt_at TEXT,
                        scan_from    INTEGER,
                        created_at   TEXT NOT NULL,
                        updated_at   TEXT NOT NULL
                    )""");
            // Une issue ne peut être appariée qu'à un seul sujet : la contrainte est portée par la
            // base, pas seulement par le code.
            st.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS forum_link_issue
                        ON forum_link (issue_number) WHERE issue_number IS NOT NULL""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS adopted_thread (
                        thread_id  TEXT PRIMARY KEY,
                        adopted_at TEXT NOT NULL,
                        reason     TEXT
                    )""");
        }
        putState("schema_version", String.valueOf(SCHEMA_VERSION));
    }

    // ---- État global -----------------------------------------------------------------------

    public Optional<String> state(String key) {
        try (PreparedStatement ps =
                     connection.prepareStatement("SELECT value FROM sync_state WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("Lecture impossible de l'état « " + key + " ».", e);
        }
    }

    public void putState(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sync_state (key, value) VALUES (?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Écriture impossible de l'état « " + key + " ».", e);
        }
    }

    // ---- Sélection explicite des anciens sujets --------------------------------------------

    /**
     * Marque un sujet comme explicitement adopté. Sert au sujet TEST et à toute reprise ciblée :
     * <strong>aucun import massif</strong> des anciens sujets n'a lieu sans ce geste.
     */
    public void adopt(String threadId, String reason) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO adopted_thread (thread_id, adopted_at, reason) VALUES (?, ?, ?) "
                        + "ON CONFLICT(thread_id) DO UPDATE SET reason = excluded.reason")) {
            ps.setString(1, threadId);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Adoption impossible du sujet " + threadId + ".", e);
        }
    }

    public boolean adopted(String threadId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM adopted_thread WHERE thread_id = ?")) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new StoreException("Lecture impossible des sujets adoptés.", e);
        }
    }

    public List<String> adoptedThreads() {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT thread_id FROM adopted_thread ORDER BY adopted_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new StoreException("Lecture impossible des sujets adoptés.", e);
        }
        return out;
    }

    // ---- Liens sujet / issue ---------------------------------------------------------------

    public Optional<Link> link(String threadId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT thread_id, issue_number, state, title_hash, body_hash, last_status, "
                        + "link_posted, attempts, last_error, last_attempt_at, scan_from FROM forum_link WHERE thread_id = ?")) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("Lecture impossible du lien du sujet " + threadId + ".", e);
        }
    }

    public Optional<Link> linkByIssue(int issueNumber) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT thread_id, issue_number, state, title_hash, body_hash, last_status, "
                        + "link_posted, attempts, last_error, last_attempt_at, scan_from FROM forum_link WHERE issue_number = ?")) {
            ps.setInt(1, issueNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("Lecture impossible du lien de l'issue #" + issueNumber + ".", e);
        }
    }

    public List<Link> allLinks() {
        List<Link> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT thread_id, issue_number, state, title_hash, body_hash, last_status, "
                        + "link_posted, attempts, last_error, last_attempt_at, scan_from FROM forum_link ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(read(rs));
            }
        } catch (SQLException e) {
            throw new StoreException("Lecture impossible des liens.", e);
        }
        return out;
    }

    /**
     * Enregistre l'<strong>intention</strong> de créer une issue et incrémente le compteur de
     * tentatives, <strong>avant</strong> tout appel réseau. C'est le point exact qui rend
     * l'anti-doublon possible : si le processus disparaît maintenant, la trace existe déjà.
     *
     * @return le lien enregistré, avec son compteur à jour
     */
    public Link beginCreation(String guildId, String channelId, String threadId,
                              int scanFrom) {
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO forum_link (thread_id, guild_id, channel_id, state, attempts, "
                        + "created_at, updated_at, last_attempt_at, scan_from) "
                        + "VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?) "
                        + "ON CONFLICT(thread_id) DO UPDATE SET "
                        + "attempts = forum_link.attempts + 1, updated_at = excluded.updated_at, "
                        + "last_attempt_at = excluded.last_attempt_at, "
                        // Le plancher n'est JAMAIS rabaissé ni relevé par une nouvelle tentative :
                        // la première valeur est la seule sûre.
                        + "scan_from = COALESCE(forum_link.scan_from, excluded.scan_from)")) {
            ps.setString(1, threadId);
            ps.setString(2, guildId);
            ps.setString(3, channelId);
            ps.setString(4, Link.STATE_CREATING);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.setString(7, now);
            ps.setInt(8, scanFrom);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Enregistrement impossible de l'intention de création pour le "
                    + "sujet " + threadId + ".", e);
        }
        return link(threadId).orElseThrow(() ->
                new StoreException("Lien introuvable juste après son enregistrement : " + threadId, null));
    }

    /** Confirme l'appariement : la création a abouti, ou a été retrouvée par réconciliation. */
    public void confirmLink(String threadId, int issueNumber, String titleHash, String bodyHash) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE forum_link SET issue_number = ?, state = ?, title_hash = ?, body_hash = ?, "
                        + "last_error = NULL, updated_at = ? WHERE thread_id = ?")) {
            ps.setInt(1, issueNumber);
            ps.setString(2, Link.STATE_LINKED);
            ps.setString(3, titleHash);
            ps.setString(4, bodyHash);
            ps.setString(5, Instant.now().toString());
            ps.setString(6, threadId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Confirmation impossible du lien " + threadId + " ↔ #"
                    + issueNumber + " : " + e.getMessage(), e);
        }
    }

    public void recordContentHashes(String threadId, String titleHash, String bodyHash) {
        update("UPDATE forum_link SET title_hash = ?, body_hash = ?, updated_at = ? "
                + "WHERE thread_id = ?", ps -> {
            ps.setString(1, titleHash);
            ps.setString(2, bodyHash);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, threadId);
        }, threadId);
    }

    public void recordAnnouncedStatus(String threadId, String status) {
        update("UPDATE forum_link SET last_status = ?, updated_at = ? WHERE thread_id = ?", ps -> {
            ps.setString(1, status);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, threadId);
        }, threadId);
    }

    public void recordLinkPosted(String threadId) {
        update("UPDATE forum_link SET link_posted = 1, updated_at = ? WHERE thread_id = ?", ps -> {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, threadId);
        }, threadId);
    }

    public void recordError(String threadId, String error) {
        update("UPDATE forum_link SET last_error = ?, updated_at = ? WHERE thread_id = ?", ps -> {
            ps.setString(1, error);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, threadId);
        }, threadId);
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void update(String sql, Binder binder, String threadId) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Mise à jour impossible du lien du sujet " + threadId + ".", e);
        }
    }

    private static Link read(ResultSet rs) throws SQLException {
        // Lecture dans des variables locales : wasNull() se rapporte à la DERNIÈRE colonne lue,
        // donc l'appeler au milieu d'une liste d'arguments testerait la mauvaise colonne.
        int rawIssueNumber = rs.getInt("issue_number");
        Integer issueNumber = rs.wasNull() ? null : rawIssueNumber;
        return new Link(
                rs.getString("thread_id"),
                issueNumber,
                rs.getString("state"),
                rs.getString("title_hash"),
                rs.getString("body_hash"),
                rs.getString("last_status"),
                rs.getInt("link_posted") != 0,
                rs.getInt("attempts"),
                rs.getString("last_error"),
                parseInstant(rs.getString("last_attempt_at")),
                readNullableInt(rs, "scan_from"));
    }

    /**
     * Plus grand numéro d'issue déjà apparié. Point de départ du balayage de réconciliation, qui
     * interroge les issues une par une — seul accès dont GitHub garantit la fraîcheur.
     */
    public Optional<Integer> maxKnownIssueNumber() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MAX(issue_number) FROM forum_link WHERE issue_number IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int value = rs.getInt(1);
                return rs.wasNull() ? Optional.empty() : Optional.of(value);
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new StoreException("Lecture impossible du plus grand numéro d'issue apparié.", e);
        }
    }

    private static Integer readNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            return null;  // valeur illisible : traitée comme absente, jamais comme « maintenant »
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // Fermeture au mieux : un échec ici n'a aucune conséquence utile à propager.
        }
    }
}
