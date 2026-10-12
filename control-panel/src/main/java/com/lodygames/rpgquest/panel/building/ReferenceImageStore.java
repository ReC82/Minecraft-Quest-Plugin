package com.lodygames.rpgquest.panel.building;

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
 * Les images de référence d'un projet d'Atelier (issue #283), dans la base du panel.
 *
 * <h2>Ce qu'une image est, et n'est pas</h2>
 *
 * <p>Une <strong>référence de style</strong> : silhouette, toiture, matériaux, ambiance. Le modèle
 * la voit — ses pixels lui sont réellement transmis — mais le plan reste un plan du vocabulaire
 * fermé, aux dimensions du projet, en blocs vanilla. Jamais une conversion pixel pour bloc.</p>
 *
 * <h2>Garde-fous</h2>
 *
 * <ul>
 *   <li><strong>type détecté sur les octets</strong> (signature PNG, JPEG, WebP, GIF), jamais sur
 *       le nom de fichier ni sur l'en-tête envoyé par le navigateur : un « .png » qui est un HTML ou
 *       un SVG — donc du script — est refusé ;</li>
 *   <li><strong>bornes</strong> : {@value #MAX_IMAGES} images par projet, {@value #MAX_BYTES}
 *       octets chacune — au-delà, chaque appel payant enverrait des mégaoctets ;</li>
 *   <li><strong>stockage local</strong> dans {@code control-panel.db} (mode 600, hors Git), jamais
 *       sur un hébergement public ; une image retirée est <em>effacée</em>, pas masquée ;</li>
 *   <li>les images d'un projet ne sont servies qu'à une session autorisée sur l'Atelier.</li>
 * </ul>
 */
public final class ReferenceImageStore {

    /** Images par projet : de quoi montrer un style sous plusieurs angles, sans plus. */
    public static final int MAX_IMAGES = 4;

    /** 4 Mio par image : une photo de téléphone réduite y tient ; un original de 20 Mio non. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS building_project_image (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                data BLOB NOT NULL,
                created_by TEXT,
                created_at TEXT NOT NULL
            )
            """;
    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_project_image ON building_project_image(project_id, created_at)";

    /** Une image, sans ses octets — ce que l'écran liste. */
    public record Info(String id, String projectId, String name, String mime, int size, String sha256,
                       String createdBy, Instant createdAt) {
    }

    /** Issue d'un ajout : l'image enregistrée, ou le motif du refus, en français. */
    public record Added(Optional<Info> image, String refusal) {

        static Added refused(String why) {
            return new Added(Optional.empty(), why);
        }
    }

    private final String jdbcUrl;

    public ReferenceImageStore(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(CREATE);
            s.execute(CREATE_INDEX);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'initialiser les images de référence dans " + dbPath, e);
        }
    }

    /**
     * Le type réel d'une image, lu sur ses premiers octets ; vide si ce n'est pas une image admise.
     */
    public static Optional<String> sniff(byte[] data) {
        if (data == null || data.length < 12) {
            return Optional.empty();
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return Optional.of("image/png");
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return Optional.of("image/webp");
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8'
                && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
            return Optional.of("image/gif");
        }
        return Optional.empty();
    }

    /** Ajoute une image au projet, après tous les contrôles. N'écrit rien en cas de refus. */
    public Added add(String projectId, String fileName, byte[] data, String by) {
        if (data == null || data.length == 0) {
            return Added.refused("Aucun fichier reçu : choisissez une image.");
        }
        if (data.length > MAX_BYTES) {
            return Added.refused("Image trop lourde : " + (data.length / 1024) + " Kio pour "
                    + (MAX_BYTES / 1024) + " Kio au plus. Réduisez-la (par exemple 1600 pixels de "
                    + "large) puis recommencez. Rien n'a été enregistré.");
        }
        Optional<String> mime = sniff(data);
        if (mime.isEmpty()) {
            return Added.refused("Ce fichier n'est pas une image PNG, JPEG, WebP ou GIF (son contenu a "
                    + "été vérifié, pas seulement son nom). Rien n'a été enregistré.");
        }
        if (list(projectId).size() >= MAX_IMAGES) {
            return Added.refused("Ce projet a déjà " + MAX_IMAGES + " images de référence : retirez-en "
                    + "une avant d'en ajouter une autre.");
        }
        String sha = sha256(data);
        if (list(projectId).stream().anyMatch(i -> i.sha256().equals(sha))) {
            return Added.refused("Cette image est déjà jointe au projet.");
        }
        String id = UUID.randomUUID().toString();
        String name = cleanName(fileName);
        Instant now = Instant.now();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO building_project_image (id, project_id, name, mime, size, sha256, data, "
                        + "created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, projectId);
            ps.setString(3, name);
            ps.setString(4, mime.get());
            ps.setInt(5, data.length);
            ps.setString(6, sha);
            ps.setBytes(7, data);
            ps.setString(8, by);
            ps.setString(9, STAMP.format(now));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Enregistrement de l'image impossible", e);
        }
        return new Added(Optional.of(new Info(id, projectId, name, mime.get(), data.length, sha, by, now)), "");
    }

    public List<Info> list(String projectId) {
        List<Info> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, project_id, name, mime, size, sha256, created_by, created_at "
                        + "FROM building_project_image WHERE project_id = ? ORDER BY created_at, rowid")) {
            ps.setString(1, projectId == null ? "" : projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Info(rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                            rs.getString("mime"), rs.getInt("size"), rs.getString("sha256"),
                            rs.getString("created_by"), parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture des images impossible", e);
        }
        return out;
    }

    /** Les octets d'une image, seulement si elle appartient bien à ce projet. */
    public Optional<byte[]> data(String projectId, String imageId) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT data FROM building_project_image WHERE id = ? AND project_id = ?")) {
            ps.setString(1, imageId == null ? "" : imageId);
            ps.setString(2, projectId == null ? "" : projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBytes("data")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture de l'image impossible", e);
        }
    }

    public Optional<Info> find(String projectId, String imageId) {
        return list(projectId).stream().filter(i -> i.id().equals(imageId)).findFirst();
    }

    /** Efface réellement l'image (pas un masquage). Vrai si elle existait dans ce projet. */
    public boolean remove(String projectId, String imageId) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM building_project_image WHERE id = ? AND project_id = ?")) {
            ps.setString(1, imageId == null ? "" : imageId);
            ps.setString(2, projectId == null ? "" : projectId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Suppression de l'image impossible", e);
        }
    }

    /** Un nom affichable : sans chemin, sans caractère de contrôle, borné. */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}<>\"]", "").trim();
        if (name.isEmpty()) {
            name = "image";
        }
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
