package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.npc.model.NpcDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Écriture <strong>sûre</strong> des définitions PNJ sur disque : un fichier par PNJ sous le
 * dossier {@code npcs/}, jamais de chemin arbitraire (le nom de fichier est toujours
 * {@code <id>.yml}, l'{@code id} étant validé en amont par {@link NpcDefinition}). Pas de YAML
 * brut : {@link NpcDefinitionYaml} produit le texte. Écriture atomique (fichier temporaire +
 * {@code move}). Purement IO : testable avec {@code @TempDir}, aucune dépendance Bukkit au-delà
 * de {@link NpcDefinitionLoader} (qui fonctionne en JUnit pur).
 */
public final class NpcDefinitionStore {

    private final Path directory;
    private final NpcDefinitionLoader loader = new NpcDefinitionLoader();

    public NpcDefinitionStore(Path directory) {
        this.directory = directory;
    }

    /** @param code {@code CREATED} / {@code UPDATED} / {@code EXISTS} / {@code NOT_FOUND} / {@code ERROR} */
    public record Result(boolean ok, String code, String message, String file, NpcLoadReport report) {
        static Result fail(String code, String message) {
            return new Result(false, code, message, null, null);
        }
    }

    /**
     * Crée une définition, en échouant si elle existe déjà.
     *
     * <p><strong>Atomique.</strong> Le fichier est créé avec {@code CREATE_NEW} : c'est le système
     * de fichiers qui arbitre, pas un {@code exists()} suivi d'une écriture. Deux créations
     * simultanées du même identifiant — un double clic, une requête rejouée, deux administrateurs —
     * ne peuvent donc pas réussir toutes les deux. Sans cela, la seconde écrasait la première,
     * puis son propre nettoyage supprimait la définition que la première venait légitimement de
     * créer.</p>
     */
    public Result create(NpcDefinition definition) {
        Path target = directory.resolve(definition.id() + ".yml");
        if (Files.exists(directory.resolve(definition.id() + ".yaml"))) {
            return Result.fail("EXISTS", "Une définition « " + definition.id() + " » existe déjà — "
                    + "utiliser la modification, jamais l'écrasement.");
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(target, NpcDefinitionYaml.render(definition), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            return Result.fail("EXISTS", "Une définition « " + definition.id() + " » existe déjà — "
                    + "utiliser la modification, jamais l'écrasement.");
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        NpcLoadReport report = loader.loadDirectory(directory);
        boolean present = report.loaded().stream().anyMatch(d -> d.id().equals(definition.id()));
        if (!present) {
            return new Result(false, "ERROR",
                    "Définition « " + definition.id() + " » écrite mais non rechargeable — à vérifier.",
                    target.getFileName().toString(), report);
        }
        return new Result(true, "CREATED", "Définition PNJ « " + definition.id() + " » créée.",
                target.getFileName().toString(), report);
    }

    public Result update(NpcDefinition definition) {
        Path target = existingFileFor(definition.id());
        if (target == null) {
            return Result.fail("NOT_FOUND", "Aucune définition « " + definition.id() + " » à modifier.");
        }
        return writeAndReload(target, definition, "UPDATED", "Définition PNJ « " + definition.id() + " » modifiée.");
    }

    /**
     * Supprime le fichier de définition d'un PNJ — <strong>réservé au rollback</strong> d'une
     * création qui vient d'échouer, jamais une suppression de contenu à la demande.
     *
     * <p>Ne supprime que le fichier dont l'id correspond exactement, et ne touche à rien d'autre :
     * ni liaison, ni dialogue, ni quête. Un fichier absent n'est pas une erreur (le rollback doit
     * rester idempotent).</p>
     */
    public Result deleteForRollback(String id) {
        Path target = existingFileFor(id);
        if (target == null) {
            return new Result(true, "ABSENT", "Aucune définition « " + id + " » à retirer.", null, null);
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            return Result.fail("DELETE_FAILED",
                    "Définition « " + id + " » non supprimée : " + e.getMessage());
        }
        NpcLoadReport report = loader.loadDirectory(directory);
        return new Result(true, "DELETED", "Définition PNJ « " + id + " » retirée (rollback).",
                target.getFileName().toString(), report);
    }

    /**
     * Supprime la définition d'un PNJ <strong>à la demande</strong> (issue #226), après l'avoir
     * sauvegardée.
     *
     * <p><strong>Distincte de {@link #deleteForRollback}</strong>, qui annule une création ratée et
     * n'a rien à sauvegarder. Ici le fichier existait, un administrateur l'avait écrit, et une
     * suppression sans copie serait irréversible. La sauvegarde part donc <em>avant</em> la
     * suppression : si elle échoue, rien n'est supprimé.</p>
     *
     * <p>Ne touche à <strong>rien d'autre</strong> : ni liaison Citizens, ni dialogue, ni quête. La
     * cascade est refusée par construction — cette méthode ne sait même pas les atteindre.
     * L'analyse des dépendances et l'ordre des opérations appartiennent à l'appelant.</p>
     *
     * <p>Un fichier absent renvoie {@code ABSENT} avec {@code ok() == true} : un double clic ou un
     * rejeu aboutit au même état plutôt qu'à une erreur, puisque l'état voulu est atteint.</p>
     *
     * @param backupDirectory dossier des sauvegardes ; {@code null} ⇒ suppression refusée
     */
    public Result deleteDefinition(String id, Path backupDirectory) {
        Path target = existingFileFor(id);
        if (target == null) {
            return new Result(true, "ABSENT", "Aucune définition « " + id + " » : rien à supprimer.",
                    null, null);
        }
        if (backupDirectory == null) {
            return Result.fail("NO_BACKUP_DIR", "Aucun dossier de sauvegarde : une suppression de "
                    + "définition sans copie préalable est refusée.");
        }
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        Path backup = backupDirectory.resolve(stamp + "-" + id + ".yml");
        try {
            Files.createDirectories(backupDirectory);
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("BACKUP_FAILED", "Sauvegarde de « " + id + " » impossible ("
                    + e.getMessage() + ") : suppression annulée, rien n'a été modifié.");
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            return Result.fail("DELETE_FAILED",
                    "Définition « " + id + " » non supprimée : " + e.getMessage()
                            + " La sauvegarde reste dans " + backup + ".");
        }
        NpcLoadReport report = loader.loadDirectory(directory);
        if (report.loaded().stream().anyMatch(d -> d.id().equals(id))) {
            // Deux fichiers portaient le même id : on a supprimé l'un, l'autre reste. Le dire, au
            // lieu de laisser croire que le PNJ a disparu.
            return new Result(false, "STILL_PRESENT",
                    "« " + id + " » est toujours défini après suppression de "
                            + target.getFileName() + " : un autre fichier du dossier porte le même "
                            + "identifiant. Sauvegarde dans " + backup + ".",
                    target.getFileName().toString(), report);
        }
        return new Result(true, "DELETED",
                "Définition PNJ « " + id + " » supprimée. Sauvegarde : " + backup + ".",
                target.getFileName().toString(), report);
    }

    public Optional<NpcDefinition> find(String id) {
        return loader.loadDirectory(directory).loaded().stream()
                .filter(d -> d.id().equals(id)).findFirst();
    }

    public NpcLoadReport list() {
        return loader.loadDirectory(directory);
    }

    // ---- interne -----------------------------------------------------------------------------

    private Result writeAndReload(Path target, NpcDefinition definition, String code, String message) {
        try {
            Files.createDirectories(directory);
            Path tmp = directory.resolve("." + target.getFileName() + ".tmp");
            Files.writeString(tmp, NpcDefinitionYaml.render(definition), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        NpcLoadReport report = loader.loadDirectory(directory);
        boolean present = report.loaded().stream().anyMatch(d -> d.id().equals(definition.id()));
        if (!present) {
            return new Result(false, "ERROR",
                    "Le fichier a été écrit mais ne se recharge pas proprement — voir les erreurs.", target.getFileName().toString(), report);
        }
        return new Result(true, code, message, target.getFileName().toString(), report);
    }

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
                org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
                try {
                    yaml.load(file.toFile());
                } catch (Exception ignored) {
                    continue;
                }
                if (id.equals(trim(yaml.getString("id")))) {
                    return file;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
