package com.lodygames.rpgquest.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Suppression d'un fichier de définition de quête ou de story <strong>sur le serveur</strong>
 * (issue #194).
 *
 * <p><strong>Pourquoi cette classe existe.</strong> Supprimer un contenu de la source éditable ne
 * le retire pas du serveur : le fichier déployé reste en place, le plugin continue de le charger,
 * et le contenu « réapparaît » au prochain rafraîchissement du catalogue. C'était exactement le
 * symptôme rapporté. Le Control Panel a donc besoin de pouvoir supprimer aussi la copie serveur.</p>
 *
 * <p><strong>Deux précautions non négociables.</strong> Une <strong>sauvegarde horodatée</strong>
 * est prise avant toute suppression, hors des dossiers de contenu pour ne jamais être relue comme
 * du contenu. Et le fichier est retrouvé par l'<strong>identifiant déclaré dedans</strong>, jamais
 * par un nom de fichier deviné : un nom de fichier n'a pas à correspondre à l'id, et supprimer le
 * mauvais fichier serait irréparable.</p>
 */
public final class ContentDefinitionDeleter {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path questsDirectory;
    private final Path storiesDirectory;
    private final Path backupRoot;

    public ContentDefinitionDeleter(Path questsDirectory, Path storiesDirectory, Path backupRoot) {
        this.questsDirectory = questsDirectory;
        this.storiesDirectory = storiesDirectory;
        this.backupRoot = backupRoot;
    }

    /**
     * @param ok         le fichier a été supprimé
     * @param code       {@code DELETED}, {@code NOT_FOUND}, {@code INVALID_KIND},
     *                   {@code BACKUP_FAILED}, {@code ERROR}
     * @param message    message lisible destiné au journal d'actions du panel
     * @param file       nom du fichier supprimé, ou {@code null}
     * @param backupPath chemin de la sauvegarde, pour qu'une restauration ne se devine pas
     */
    public record Result(boolean ok, String code, String message, String file, String backupPath) {

        static Result fail(String code, String message) {
            return new Result(false, code, message, null, null);
        }
    }

    /** @param kind {@code quests} ou {@code stories} */
    public Result delete(String kind, String rawId) {
        Path directory = directoryFor(kind);
        if (directory == null) {
            return Result.fail("INVALID_KIND", "Type de contenu non supprimable : « " + kind
                    + " ». Seuls « quests » et « stories » le sont.");
        }
        String wanted = normalize(rawId);
        if (wanted == null) {
            return Result.fail("NOT_FOUND", "Identifiant invalide : « " + rawId + " ».");
        }
        if (!Files.isDirectory(directory)) {
            return Result.fail("NOT_FOUND", "Aucun dossier « " + kind + " » sur le serveur.");
        }

        Path target = findByDeclaredId(directory, wanted);
        if (target == null) {
            return Result.fail("NOT_FOUND", "Aucun fichier de « " + kind + " » ne déclare "
                    + "l'identifiant « " + rawId + " » sur le serveur : rien à supprimer.");
        }

        Path backup;
        try {
            Path dir = backupRoot.resolve(STAMP.format(Instant.now()) + "-" + kind);
            Files.createDirectories(dir);
            backup = dir.resolve(target.getFileName().toString());
            Files.copy(target, backup);
        } catch (IOException e) {
            // Pas de sauvegarde ⇒ pas de suppression. Une suppression irréversible non sauvegardée
            // n'est jamais un compromis acceptable.
            return Result.fail("BACKUP_FAILED", "Sauvegarde impossible avant suppression ("
                    + e.getMessage() + ") : le fichier n'a pas été touché.");
        }

        try {
            Files.delete(target);
        } catch (IOException e) {
            return Result.fail("ERROR", "Suppression impossible (" + e.getMessage()
                    + "). Une sauvegarde existe : " + backup);
        }
        return new Result(true, "DELETED",
                "Fichier « " + target.getFileName() + " » supprimé du serveur (sauvegarde : "
                        + backup.getFileName() + ").",
                target.getFileName().toString(), backup.toString());
    }

    /**
     * Retrouve le fichier dont la clé {@code id} vaut l'identifiant demandé. Le nom du fichier
     * n'est jamais présumé : c'est le contenu qui décide.
     */
    private static Path findByDeclaredId(Path directory, String wanted) {
        try (var stream = Files.newDirectoryStream(directory, "*.y*ml")) {
            for (Path file : stream) {
                org.bukkit.configuration.file.YamlConfiguration yaml =
                        new org.bukkit.configuration.file.YamlConfiguration();
                try {
                    yaml.load(file.toFile());
                } catch (Exception unreadable) {
                    // Un fichier illisible n'est jamais supprimé « au cas où » : on l'ignore.
                    continue;
                }
                if (wanted.equals(normalize(yaml.getString("id")))) {
                    return file;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private Path directoryFor(String kind) {
        return switch (kind == null ? "" : kind) {
            case "quests" -> questsDirectory;
            case "stories" -> storiesDirectory;
            default -> null;
        };
    }

    /** Identifiant comparable : minuscules, sans le namespace {@code rpgquest:} implicite. */
    static String normalize(String rawId) {
        if (rawId == null) {
            return null;
        }
        String id = rawId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return null;
        }
        int colon = id.indexOf(':');
        String key = colon < 0 ? id : id.substring(colon + 1);
        return key.isEmpty() ? null : key;
    }
}
