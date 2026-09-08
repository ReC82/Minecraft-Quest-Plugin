package com.lodygames.rpgquest.panel.content;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Accès <strong>strictement whitelisté</strong> du Control Panel au <em>checkout Git source</em>
 * du contenu RPGQuest (issue #46). SÉPARE bien « éditer » de « enregistrer dans la source » : cette
 * classe n'écrit QUE dans {@code <root>/quests/*.yml} et {@code <root>/stories/*.yml}, jamais
 * ailleurs, jamais un chemin fourni par le navigateur, jamais {@code data.db} / {@code .env} /
 * {@code config.yml} / mondes / Citizens / autres plugins. Aucun FTP, aucun déploiement.
 *
 * <p>Écriture sûre : hash SHA-256 du fichier source calculé à la lecture, revérifié au save
 * (conflit si le fichier a changé entre-temps), écriture atomique (fichier temporaire +
 * {@code ATOMIC_MOVE}), jamais d'écrasement silencieux.</p>
 *
 * <p>Si le service n'a pas les droits d'écriture sur ces dossiers, {@link #writable(String)}
 * renvoie {@code false} : l'éditeur reste alors en lecture seule (validation + aperçu + diff
 * fonctionnent), et {@link #write} renvoie {@code READONLY} — jamais de {@code chmod}/{@code sudo}
 * automatique.</p>
 */
public final class ContentWorkspace {

    /** Types de contenu éditables via #46 — rien d'autre n'est jamais exposé. */
    public static final List<String> KINDS = List.of("quests", "stories");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final Path root;

    /** @param root racine du contenu (typiquement {@code <repo>/src/main/resources}) ; {@code null} = non configuré. */
    public ContentWorkspace(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    public boolean configured() {
        return root != null && Files.isDirectory(root);
    }

    /** Le dossier {@code <root>/<kind>} existe et est accessible en écriture (jamais de chmod ici). */
    public boolean writable(String kind) {
        Path dir = dir(kind);
        return dir != null && Files.isDirectory(dir) && Files.isWritable(dir);
    }

    public Optional<Path> dirPath(String kind) {
        return Optional.ofNullable(dir(kind));
    }

    /** Un fichier de contenu : slug + chemin affichable (relatif au repo) + hash + texte. */
    public record ContentFile(String kind, String slug, String repoPath, String sha256, String text) {
    }

    public record WriteResult(boolean ok, String code, String message, String repoPath, String sha256) {
        static WriteResult fail(String code, String message) {
            return new WriteResult(false, code, message, null, null);
        }
    }

    public List<ContentFile> list(String kind) {
        Path dir = dir(kind);
        List<ContentFile> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yml")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String slug = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                if (!SLUG.matcher(slug).matches()) {
                    continue;
                }
                read(kind, slug).ifPresent(out::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du dossier « " + kind + " » impossible", e);
        }
        out.sort((a, b) -> a.slug().compareTo(b.slug()));
        return out;
    }

    public Optional<ContentFile> read(String kind, String slug) {
        Path file = resolve(kind, slug);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return Optional.of(new ContentFile(kind, slug, repoPath(kind, slug), sha256(bytes), text));
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture de « " + kind + "/" + slug + " » impossible", e);
        }
    }

    public boolean exists(String kind, String slug) {
        Path f = resolve(kind, slug);
        return f != null && Files.isRegularFile(f);
    }

    /**
     * Écrit {@code yaml} dans {@code <root>/<kind>/<slug>.yml}.
     *
     * @param expectedSha hash attendu du fichier existant (conflit si différent) ; {@code null}
     *                    ou vide = création (échoue si le fichier existe déjà)
     */
    public WriteResult write(String kind, String slug, String yaml, String expectedSha) {
        Path file = resolve(kind, slug);
        if (file == null) {
            return WriteResult.fail("INVALID", "Type ou identifiant de contenu invalide.");
        }
        Path dir = file.getParent();
        if (!Files.isDirectory(dir)) {
            return WriteResult.fail("NOT_FOUND", "Dossier « " + kind + " » introuvable dans l'espace de travail.");
        }
        if (!Files.isWritable(dir)) {
            return WriteResult.fail("READONLY", "Espace de travail en lecture seule : le service PlugAdmin "
                    + "n'a pas les droits d'écriture sur « " + kind + "/ ». Voir la documentation #46.");
        }
        boolean existsNow = Files.isRegularFile(file);
        String currentSha = null;
        if (existsNow) {
            try {
                currentSha = sha256(Files.readAllBytes(file));
            } catch (IOException e) {
                return WriteResult.fail("ERROR", "Lecture du fichier existant impossible : " + e.getMessage());
            }
        }
        if (expectedSha == null || expectedSha.isBlank()) {
            if (existsNow) {
                return WriteResult.fail("EXISTS", "Un contenu « " + slug + " » existe déjà — jamais d'écrasement "
                        + "silencieux. Ouvre-le pour le modifier.");
            }
        } else if (!existsNow) {
            return WriteResult.fail("CONFLICT", "Le fichier a disparu depuis l'ouverture de l'éditeur.");
        } else if (!expectedSha.equals(currentSha)) {
            return WriteResult.fail("CONFLICT", "Le fichier « " + slug + " » a été modifié depuis l'ouverture de "
                    + "l'éditeur (conflit de version). Recharge la page pour repartir de la version actuelle.");
        }

        byte[] out = yaml.getBytes(StandardCharsets.UTF_8);
        try {
            Path tmp = dir.resolve("." + slug + ".yml.tmp");
            Files.write(tmp, out);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            return WriteResult.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        return new WriteResult(true, existsNow ? "UPDATED" : "CREATED",
                existsNow ? "Contenu mis à jour dans la source." : "Contenu créé dans la source.",
                repoPath(kind, slug), sha256(out));
    }

    // ---- résolution de chemin (jamais un chemin brut du navigateur) --------------------------

    private Path dir(String kind) {
        if (!configured() || !KINDS.contains(kind)) {
            return null;
        }
        Path dir = root.resolve(kind).normalize();
        return dir.startsWith(root) ? dir : null;
    }

    private Path resolve(String kind, String slug) {
        Path dir = dir(kind);
        if (dir == null || slug == null) {
            return null;
        }
        String s = slug.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(s).matches()) {
            return null;
        }
        Path file = dir.resolve(s + ".yml").normalize();
        return file.startsWith(dir) ? file : null;
    }

    private String repoPath(String kind, String slug) {
        return "src/main/resources/" + kind + "/" + slug + ".yml";
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
