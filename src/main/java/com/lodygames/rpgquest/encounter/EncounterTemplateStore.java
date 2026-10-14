package com.lodygames.rpgquest.encounter;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
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
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Écriture <strong>sûre</strong> des modèles de PNJ récurrents (issue #13 étape 3) — même
 * discipline que {@code NpcDefinitionStore} : un fichier par modèle sous {@code encounters/}, nom
 * toujours {@code <id>.yml} ({@code id} validé par {@link EncounterTemplate}), jamais de YAML brut
 * ({@link EncounterTemplateYaml} produit le texte), création {@code CREATE_NEW}, modification par
 * fichier temporaire + {@code move}, relecture avant de déclarer le succès, sauvegarde avant toute
 * suppression. Purement IO : testable avec {@code @TempDir}.
 *
 * <p>Les méthodes d'écriture sont {@code synchronized} : deux administrateurs qui modifient le
 * même modèle passent l'un après l'autre, jamais entrelacés.</p>
 */
public final class EncounterTemplateStore {

    private final Path directory;
    private final EncounterTemplateLoader loader = new EncounterTemplateLoader();

    public EncounterTemplateStore(Path directory) {
        this.directory = directory;
    }

    /** @param code {@code CREATED} / {@code UPDATED} / {@code DELETED} / {@code ABSENT} / {@code EXISTS} / {@code NOT_FOUND} / {@code ERROR}… */
    public record Result(boolean ok, String code, String message, String file) {
        static Result fail(String code, String message) {
            return new Result(false, code, message, null);
        }
    }

    /** Crée un modèle, en échouant s'il existe déjà : c'est le système de fichiers qui arbitre (double clic, rejeu). */
    public synchronized Result create(EncounterTemplate template) {
        if (existingFileFor(template.id()) != null) {
            return exists(template.id());
        }
        Path target = directory.resolve(template.id() + ".yml");
        try {
            Files.createDirectories(directory);
            Files.writeString(target, EncounterTemplateYaml.render(template), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            return exists(template.id());
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        return verify(target, template.id(), "CREATED", "Modèle « " + template.id() + " » créé.");
    }

    public synchronized Result update(EncounterTemplate template) {
        Path target = existingFileFor(template.id());
        if (target == null) {
            return Result.fail("NOT_FOUND", "Aucun modèle « " + template.id() + " » à modifier.");
        }
        try {
            Path tmp = directory.resolve("." + target.getFileName() + ".tmp");
            Files.writeString(tmp, EncounterTemplateYaml.render(template), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        return verify(target, template.id(), "UPDATED", "Modèle « " + template.id() + " » modifié.");
    }

    /**
     * Supprime le fichier d'un modèle après l'avoir copié dans {@code backupDirectory} (qui ne doit
     * pas être {@code encounters/} : le chargeur relirait la copie). Si la copie échoue, rien n'est
     * supprimé. Un modèle absent renvoie {@code ABSENT} avec {@code ok() == true} (double clic).
     * Ne touche à aucune occurrence : c'est à l'appelant de décider s'il accepte d'en laisser.
     */
    public synchronized Result delete(String id, Path backupDirectory) {
        Path target = existingFileFor(id);
        if (target == null) {
            return new Result(true, "ABSENT", "Aucun modèle « " + id + " » : rien à supprimer.", null);
        }
        if (backupDirectory == null) {
            return Result.fail("NO_BACKUP_DIR", "Aucun dossier de sauvegarde : suppression refusée.");
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
            return Result.fail("DELETE_FAILED", "Modèle « " + id + " » non supprimé : " + e.getMessage()
                    + " La sauvegarde reste dans " + backup + ".");
        }
        if (find(id).isPresent()) {
            return new Result(false, "STILL_PRESENT", "« " + id + " » est toujours défini après suppression de "
                    + target.getFileName() + " : un autre fichier porte le même identifiant. Sauvegarde dans "
                    + backup + ".", target.getFileName().toString());
        }
        return new Result(true, "DELETED", "Modèle « " + id + " » supprimé. Sauvegarde : " + backup + ".",
                target.getFileName().toString());
    }

    public Optional<EncounterTemplate> find(String id) {
        return loader.loadDirectory(directory).loaded().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    // ---- interne -----------------------------------------------------------------------------

    private Result verify(Path target, String id, String code, String message) {
        EncounterLoadReport report = loader.loadDirectory(directory);
        if (report.loaded().stream().noneMatch(t -> t.id().equals(id))) {
            String why = report.issues().stream()
                    .filter(i -> i.file().equals(target.getFileName().toString()))
                    .map(EncounterLoadIssue::message).findFirst().orElse("voir les fichiers refusés");
            return new Result(false, "ERROR", "Modèle « " + id + " » écrit mais non rechargeable : " + why,
                    target.getFileName().toString());
        }
        return new Result(true, code, message, target.getFileName().toString());
    }

    private static Result exists(String id) {
        return Result.fail("EXISTS", "Un modèle « " + id + " » existe déjà — utiliser la modification, "
                + "jamais l'écrasement.");
    }

    /** Fichier {@code <id>.yml}/{@code .yaml}, sinon le fichier dont le champ {@code id} vaut {@code id}. */
    private Path existingFileFor(String id) {
        for (String ext : new String[] {".yml", ".yaml"}) {
            Path direct = directory.resolve(id + ext);
            if (Files.exists(direct)) {
                return direct;
            }
        }
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (var stream = Files.newDirectoryStream(directory, "*.y*ml")) {
            for (Path file : stream) {
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.load(file.toFile());
                } catch (Exception unreadable) {
                    continue;
                }
                String declared = yaml.getString("id");
                if (declared != null && id.equals(declared.trim())) {
                    return file;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}
