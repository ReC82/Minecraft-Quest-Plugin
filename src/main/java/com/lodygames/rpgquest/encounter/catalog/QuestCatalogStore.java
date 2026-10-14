package com.lodygames.rpgquest.encounter.catalog;

import com.lodygames.rpgquest.encounter.EncounterLoadIssue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Écriture sûre des catalogues (issue #369), même discipline que {@code EncounterTemplateStore} :
 * fichier {@code <id>.yml}, création {@code CREATE_NEW} (le système de fichiers arbitre double clic
 * et rejeu), modification par fichier temporaire + {@code move}, relecture avant de déclarer le
 * succès, sauvegarde avant suppression. Purement IO ; méthodes d'écriture {@code synchronized}.
 */
public final class QuestCatalogStore {

    private final Path directory;
    private final QuestCatalogLoader loader = new QuestCatalogLoader();

    public QuestCatalogStore(Path directory) {
        this.directory = directory;
    }

    /** @param code {@code CREATED} / {@code UPDATED} / {@code DELETED} / {@code ABSENT} / {@code EXISTS} / {@code NOT_FOUND} / {@code ERROR}… */
    public record Result(boolean ok, String code, String message) {
        static Result fail(String code, String message) {
            return new Result(false, code, message);
        }
    }

    public synchronized Result create(QuestCatalog catalog) {
        Path target = directory.resolve(catalog.id() + ".yml");
        if (find(catalog.id()).isPresent() || Files.exists(target)) {
            return exists(catalog.id());
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(target, QuestCatalogYaml.render(catalog), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            return exists(catalog.id());
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        return verify(target, catalog.id(), "CREATED", "Catalogue « " + catalog.id() + " » créé.");
    }

    public synchronized Result update(QuestCatalog catalog) {
        Path target = directory.resolve(catalog.id() + ".yml");
        if (!Files.exists(target)) {
            return Result.fail("NOT_FOUND", "Aucun catalogue « " + catalog.id() + " » à modifier.");
        }
        try {
            Path tmp = directory.resolve("." + target.getFileName() + ".tmp");
            Files.writeString(tmp, QuestCatalogYaml.render(catalog), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        return verify(target, catalog.id(), "UPDATED", "Catalogue « " + catalog.id() + " » modifié.");
    }

    /**
     * Copie le fichier dans {@code backupDirectory} (hors du dossier des catalogues) puis le supprime.
     * Si la copie échoue, rien n'est supprimé. Absent = {@code ABSENT}, {@code ok() == true}.
     */
    public synchronized Result delete(String id, Path backupDirectory) {
        Path target = directory.resolve(id + ".yml");
        if (!Files.exists(target)) {
            return new Result(true, "ABSENT", "Aucun catalogue « " + id + " » : rien à supprimer.");
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path backup = backupDirectory.resolve(stamp + "-" + target.getFileName());
        try {
            Files.createDirectories(backupDirectory);
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("BACKUP_FAILED", "Sauvegarde de « " + id + " » impossible (" + e.getMessage()
                    + ") : suppression annulée, rien n'a été modifié.");
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            return Result.fail("DELETE_FAILED", "Catalogue « " + id + " » non supprimé : " + e.getMessage()
                    + " La sauvegarde reste dans " + backup + ".");
        }
        return new Result(true, "DELETED", "Catalogue « " + id + " » supprimé. Sauvegarde : " + backup + ".");
    }

    public Optional<QuestCatalog> find(String id) {
        return loader.loadDirectory(directory).loaded().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    private Result verify(Path target, String id, String code, String message) {
        QuestCatalogLoadReport report = loader.loadDirectory(directory);
        if (report.loaded().stream().noneMatch(c -> c.id().equals(id))) {
            String why = report.issues().stream()
                    .filter(i -> i.file().equals(target.getFileName().toString()))
                    .map(EncounterLoadIssue::message).findFirst().orElse("voir les fichiers refusés");
            return Result.fail("ERROR", "Catalogue « " + id + " » écrit mais non rechargeable : " + why);
        }
        return new Result(true, code, message);
    }

    private static Result exists(String id) {
        return Result.fail("EXISTS", "Un catalogue « " + id + " » existe déjà — utiliser la modification, "
                + "jamais l'écrasement.");
    }
}
