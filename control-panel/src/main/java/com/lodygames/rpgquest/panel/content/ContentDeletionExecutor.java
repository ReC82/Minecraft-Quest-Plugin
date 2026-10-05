package com.lodygames.rpgquest.panel.content;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Exécution d'un {@link DeletionPlan} (issue #194).
 *
 * <p><strong>Ordre d'exécution, et pourquoi il est dans cet ordre.</strong></p>
 *
 * <ol>
 *   <li><strong>Tout sauvegarder d'abord.</strong> Le fichier visé <em>et</em> chaque fichier que
 *       le plan va réécrire, sous un même horodatage. Une restauration part donc d'un ensemble
 *       cohérent, jamais de morceaux de deux opérations.</li>
 *   <li><strong>Réécrire les références ensuite.</strong> Si une réécriture échoue — conflit de
 *       version, droits — on restaure ce qui a déjà été réécrit et on s'arrête <em>avant</em> de
 *       supprimer quoi que ce soit. Le contenu visé est donc toujours intact en cas d'échec
 *       partiel.</li>
 *   <li><strong>Supprimer le fichier visé en dernier.</strong> C'est l'opération irréversible :
 *       elle n'arrive qu'une fois tout le reste réussi.</li>
 * </ol>
 *
 * <p>Si la suppression finale échoue après des réécritures réussies, celles-ci sont annulées : on
 * ne laisse pas une source à moitié nettoyée référençant un contenu toujours présent.</p>
 */
public final class ContentDeletionExecutor {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ContentWorkspace workspace;

    public ContentDeletionExecutor(ContentWorkspace workspace) {
        this.workspace = workspace;
    }

    /**
     * Résultat d'une exécution.
     *
     * @param ok            la source est dans l'état voulu
     * @param code          {@code DELETED}, {@code BLOCKED}, {@code CONFLICT}, {@code ROLLED_BACK},
     *                      {@code ROLLBACK_FAILED}
     * @param message       message lisible, destiné à l'opérateur
     * @param backupDir     dossier des sauvegardes de cette opération, à citer pour restaurer
     * @param rewritten     chemins réécrits pour retirer la référence
     * @param deletedPath   chemin du fichier supprimé, ou {@code null}
     * @param rollbackNotes ce que le rollback a fait, ou n'a pas pu faire — jamais tu
     */
    public record Result(boolean ok, String code, String message, String backupDir,
                         List<String> rewritten, String deletedPath, List<String> rollbackNotes) {

        public Result {
            rewritten = List.copyOf(rewritten == null ? List.of() : rewritten);
            rollbackNotes = List.copyOf(rollbackNotes == null ? List.of() : rollbackNotes);
        }

        static Result fail(String code, String message) {
            return new Result(false, code, message, null, List.of(), null, List.of());
        }
    }

    /** Applique le plan. Un plan non exécutable n'entraîne <strong>aucune</strong> écriture. */
    public Result apply(DeletionPlan plan) {
        if (plan == null || !plan.deletable()) {
            return Result.fail("BLOCKED", "Suppression refusée : des références ne peuvent pas être "
                    + "traitées proprement. Aucune modification n'a été faite.");
        }

        String stamp = STAMP.format(Instant.now()) + "-" + plan.kind() + "-" + plan.slug();
        List<ContentWorkspace.Backup> backups = new ArrayList<>();
        List<String> rewritten = new ArrayList<>();

        // --- 1. Sauvegardes, avant toute écriture -------------------------------------------
        if (plan.sourcePresent()) {
            Optional<ContentWorkspace.Backup> backup = workspace.backup(plan.kind(), plan.slug(), stamp);
            if (backup.isEmpty()) {
                return Result.fail("ERROR", "Sauvegarde du fichier à supprimer impossible : "
                        + "suppression annulée. Rien n'a été modifié.");
            }
            backups.add(backup.get());
        }
        for (DeletionPlan.Edit edit : plan.edits()) {
            Optional<ContentWorkspace.Backup> backup = workspace.backup(edit.kind(), edit.slug(), stamp);
            if (backup.isEmpty()) {
                return Result.fail("ERROR", "Sauvegarde de « " + edit.repoPath() + " » impossible : "
                        + "suppression annulée. Rien n'a été modifié.");
            }
            backups.add(backup.get());
        }
        String backupDir = backups.isEmpty() ? null
                : workspace.backupDir(stamp).toAbsolutePath().toString();

        // --- 2. Réécriture des références ---------------------------------------------------
        for (DeletionPlan.Edit edit : plan.edits()) {
            ContentWorkspace.WriteResult write =
                    workspace.write(edit.kind(), edit.slug(), edit.newYaml(), edit.expectedSha());
            if (!write.ok()) {
                List<String> notes = rollback(backups, rewritten);
                return new Result(false, "CONFLICT",
                        "Nettoyage de « " + edit.repoPath() + " » impossible : " + write.message()
                                + " La suppression a été annulée et les fichiers déjà réécrits ont "
                                + "été restaurés. Le contenu visé est intact.",
                        backupDir, rewritten, null, notes);
            }
            rewritten.add(edit.repoPath());
        }

        // --- 3. Suppression du fichier visé, en dernier -------------------------------------
        if (plan.sourcePresent()) {
            ContentWorkspace.WriteResult delete =
                    workspace.delete(plan.kind(), plan.slug(), plan.sourceSha());
            if (!delete.ok()) {
                List<String> notes = rollback(backups, rewritten);
                return new Result(false, delete.code(),
                        "Suppression refusée : " + delete.message()
                                + " Les nettoyages de références ont été annulés.",
                        backupDir, rewritten, null, notes);
            }
            return new Result(true, "DELETED",
                    "Contenu supprimé de la source, références nettoyées.",
                    backupDir, rewritten, delete.repoPath(), List.of());
        }

        // Contenu présent seulement côté serveur : rien à faire dans la source.
        return new Result(true, "DELETED",
                "Aucun fichier source à supprimer ; seule la suppression côté serveur reste à faire.",
                backupDir, rewritten, null, List.of());
    }

    /**
     * Restaure les fichiers déjà réécrits. Ne restaure que ceux qui ont effectivement été touchés :
     * remettre en place un fichier intact serait inutile et masquerait ce qui s'est passé.
     */
    private List<String> rollback(List<ContentWorkspace.Backup> backups, List<String> rewritten) {
        List<String> notes = new ArrayList<>();
        for (ContentWorkspace.Backup backup : backups) {
            if (!rewritten.contains(backup.repoPath())) {
                continue;
            }
            ContentWorkspace.WriteResult restore = workspace.restore(backup);
            notes.add(restore.ok()
                    ? "Restauré : " + backup.repoPath()
                    : "ÉCHEC de restauration de " + backup.repoPath() + " — copie disponible dans "
                            + backup.backupPath() + " (" + restore.message() + ")");
        }
        return notes;
    }
}
