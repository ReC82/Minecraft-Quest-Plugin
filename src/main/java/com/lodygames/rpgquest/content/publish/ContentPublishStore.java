package com.lodygames.rpgquest.content.publish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lecture, sauvegarde et écriture des fichiers de contenu <strong>sur le serveur</strong>
 * (issue #47).
 *
 * <h2>Le seul endroit qui transforme une famille + un identifiant en chemin</h2>
 *
 * <p>Et il le fait en trois temps : la famille vient d'une {@link PublishKind énumération}, le slug
 * doit matcher un motif sans séparateur, et le chemin obtenu doit <strong>rester confiné</strong>
 * dans le dossier de sa famille après normalisation. Les trois sont vérifiés même si les deux
 * premiers rendent le troisième théoriquement inutile : une vérification de confinement qui ne sert
 * jamais ne coûte rien, et celle qui manque coûte un serveur.</p>
 *
 * <h2>Les sauvegardes vivent HORS des dossiers de contenu</h2>
 *
 * <p>Sous {@code plugins/RPGQuest/content-backups/<horodatage>/<famille>/}. C'est la règle déjà
 * posée par #194, et elle a une raison précise : une sauvegarde déposée dans {@code quests/} serait
 * relue comme une définition au prochain rechargement, et ferait apparaître des doublons de quêtes
 * portant le même identifiant.</p>
 *
 * <h2>Écriture atomique</h2>
 *
 * <p>Écriture dans un fichier temporaire du même dossier, puis {@code move} avec
 * {@code REPLACE_EXISTING}. Un plantage au milieu laisse donc soit l'ancien fichier intact, soit le
 * nouveau complet — jamais un YAML tronqué, qui ferait échouer le chargement de toute la famille.</p>
 */
public final class ContentPublishStore {

    private final Path dataFolder;
    private final Path backupRoot;

    public ContentPublishStore(Path dataFolder) {
        this(dataFolder, dataFolder.resolve("content-backups"));
    }

    public ContentPublishStore(Path dataFolder, Path backupRoot) {
        this.dataFolder = dataFolder.normalize();
        this.backupRoot = backupRoot.normalize();
    }

    /** Un fichier de contenu présent sur le serveur. */
    public record DevFile(PublishKind kind, String slug, String sha256, long bytes) {
    }

    /** Une sauvegarde prise avant remplacement. */
    public record Backup(PublishKind kind, String slug, String relativePath, String sha256) {
    }

    /**
     * Résout le fichier d'une ressource, ou {@link Optional#empty()} si quoi que ce soit clochait.
     *
     * <p>Renvoyer un {@code Optional} vide plutôt que de lever : un identifiant forgé est une
     * donnée d'entrée invalide, pas une anomalie du serveur, et l'appelant doit pouvoir répondre
     * « refusé » proprement.</p>
     */
    public Optional<Path> resolve(PublishKind kind, String slug) {
        if (kind == null || !PublishKind.validSlug(slug)) {
            return Optional.empty();
        }
        Path directory = dataFolder.resolve(kind.directory()).normalize();
        if (!directory.startsWith(dataFolder)) {
            return Optional.empty();
        }
        Path file = directory.resolve(kind.fileName(slug)).normalize();
        // Troisième verrou : même si le motif du slug a changé un jour, le fichier doit rester
        // DANS le dossier de sa famille.
        return file.startsWith(directory) ? Optional.of(file) : Optional.empty();
    }

    /** Vrai si la ressource existe déjà sur le serveur. */
    public boolean exists(PublishKind kind, String slug) {
        return resolve(kind, slug).filter(Files::isRegularFile).isPresent();
    }

    /** Le texte du fichier présent sur le serveur, ou vide s'il n'existe pas. */
    public Optional<String> read(PublishKind kind, String slug) {
        Optional<Path> file = resolve(kind, slug);
        if (file.isEmpty() || !Files.isRegularFile(file.get())) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file.get(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    /**
     * L'empreinte du fichier présent sur le serveur, ou {@code ""} s'il est absent.
     *
     * <p>La chaîne vide est volontairement l'empreinte de l'absence : elle permet au panel
     * d'annoncer « cette ressource n'existe pas encore sur DEV » avec la même mécanique de
     * comparaison que pour un contenu différent, sans cas particulier.</p>
     */
    public String sha256(PublishKind kind, String slug) {
        return read(kind, slug).map(ContentPublishStore::sha256Of).orElse("");
    }

    /** Toutes les ressources présentes sur le serveur pour une famille, triées par identifiant. */
    public List<DevFile> list(PublishKind kind) {
        List<DevFile> out = new ArrayList<>();
        Path directory = dataFolder.resolve(kind.directory());
        if (!Files.isDirectory(directory)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.yml")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String name = file.getFileName().toString();
                String slug = name.substring(0, name.length() - ".yml".length());
                // Un fichier déposé à la main dont le nom ne respecte pas la forme attendue est
                // listé quand même, sous son nom réel : le cacher laisserait croire qu'il n'existe
                // pas, alors que le moteur le chargera.
                try {
                    out.add(new DevFile(kind, slug, sha256Of(
                            Files.readString(file, StandardCharsets.UTF_8)), Files.size(file)));
                } catch (IOException ignored) {
                    out.add(new DevFile(kind, slug, "", -1L));
                }
            }
        } catch (IOException error) {
            return out;
        }
        out.sort(Comparator.comparing(DevFile::slug));
        return out;
    }

    /**
     * Sauvegarde le fichier existant avant remplacement.
     *
     * <p>Vide si la ressource n'existe pas encore : il n'y a alors <strong>rien</strong> à
     * sauvegarder, et fabriquer un faux fichier de sauvegarde donnerait l'illusion qu'un retour
     * arrière est possible. L'appelant distingue donc « pas de sauvegarde parce qu'inutile » de
     * « sauvegarde impossible », qui est un refus.</p>
     *
     * @throws IOException si la sauvegarde était nécessaire mais a échoué — la publication doit
     *                     alors être abandonnée
     */
    public Optional<Backup> backup(PublishKind kind, String slug, String stamp) throws IOException {
        Optional<Path> file = resolve(kind, slug);
        if (file.isEmpty() || !Files.isRegularFile(file.get())) {
            return Optional.empty();
        }
        Path target = backupRoot.resolve(stamp).resolve(kind.directory())
                .resolve(kind.fileName(slug));
        Files.createDirectories(target.getParent());
        Files.copy(file.get(), target, StandardCopyOption.REPLACE_EXISTING);
        String sha = sha256Of(Files.readString(target, StandardCharsets.UTF_8));
        return Optional.of(new Backup(kind, slug,
                dataFolder.relativize(target).toString().replace('\\', '/'), sha));
    }

    /**
     * Écrit le contenu, de façon atomique.
     *
     * @return l'empreinte réellement écrite, relue depuis le disque
     * @throws IOException si l'écriture a échoué
     */
    public String write(PublishKind kind, String slug, String yaml) throws IOException {
        Path file = resolve(kind, slug)
                .orElseThrow(() -> new IOException("Chemin refusé pour " + kind + "/" + slug));
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".publishing");
        Files.writeString(temp, yaml, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
            // Certains systèmes de fichiers ne garantissent pas le déplacement atomique. Mieux vaut
            // un remplacement non atomique qu'un refus de publier.
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return sha256Of(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Restaure une sauvegarde par-dessus le fichier courant. */
    public String restore(Backup backup) throws IOException {
        Path source = backupRoot.resolve(stampOf(backup)).resolve(backup.kind().directory())
                .resolve(backup.kind().fileName(backup.slug()));
        if (!Files.isRegularFile(source)) {
            throw new IOException("Sauvegarde introuvable : " + backup.relativePath());
        }
        return write(backup.kind(), backup.slug(),
                Files.readString(source, StandardCharsets.UTF_8));
    }

    /** Le texte d'une sauvegarde, pour comparaison avant restauration. */
    public Optional<String> readBackup(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")) {
            return Optional.empty();
        }
        Path file = dataFolder.resolve(relativePath).normalize();
        if (!file.startsWith(backupRoot) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    /**
     * Supprime une ressource publiée.
     *
     * <p>Sert au retrait d'une ressource <strong>nouvelle</strong> : il n'existe aucune sauvegarde à
     * restaurer puisqu'il n'y avait rien avant, donc le seul retour arrière honnête est de retirer
     * le fichier qu'on vient d'ajouter.</p>
     *
     * @return vrai si un fichier a réellement été retiré
     */
    public boolean delete(PublishKind kind, String slug) throws IOException {
        Optional<Path> file = resolve(kind, slug);
        if (file.isEmpty()) {
            throw new IOException("Chemin refusé pour " + kind + "/" + slug);
        }
        return Files.deleteIfExists(file.get());
    }

    /** Horodatage d'une sauvegarde, pour nommer son dossier. */
    public static String stamp(Instant when) {
        return when.toString().replaceAll("[^0-9A-Za-z]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static String stampOf(Backup backup) {
        // content-backups/<stamp>/<kind>/<slug>.yml — le deuxième segment est l'horodatage.
        String[] parts = backup.relativePath().split("/");
        return parts.length >= 2 ? parts[1] : "";
    }

    /** SHA-256 hexadécimal du texte, en UTF-8. La même fonction des deux côtés du transfert. */
    public static String sha256Of(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 absent de la JVM", impossible);
        }
    }
}
