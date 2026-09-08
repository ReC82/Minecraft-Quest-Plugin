package com.lodygames.rpgquest.dialogue;

import com.lodygames.rpgquest.dialogue.model.DialogueDraft;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.NamespacedKey;

/**
 * Écriture <strong>sûre</strong> d'un dialogue sur disque : un fichier par dialogue sous
 * {@code dialogues/}, jamais de chemin arbitraire (le nom de fichier est toujours {@code <key>.yml},
 * la {@code key} étant validée en amont par {@link DialogueDraft}). Pas de YAML brut :
 * {@link DialogueDefinitionYaml} produit le texte. Écriture atomique (fichier temporaire +
 * {@code move}), refus d'écrasement, puis <strong>rechargement complet</strong> du dossier pour
 * valider — le fichier est supprimé s'il ne se recharge pas proprement.
 *
 * <p>Purement IO + {@link DialogueLoader} (JUnit pur, sans MockBukkit).</p>
 */
public final class DialogueDefinitionStore {

    private final Path directory;
    private final DialogueLoader loader;

    public DialogueDefinitionStore(Path directory, List<String> allowedCommands) {
        this.directory = directory;
        this.loader = new DialogueLoader(allowedCommands);
    }

    /** @param code {@code CREATED} / {@code EXISTS} / {@code ERROR} */
    public record Result(boolean ok, String code, String message, String file, List<String> issues) {
        static Result fail(String code, String message) {
            return new Result(false, code, message, null, List.of());
        }
    }

    public Result create(DialogueDraft draft) {
        String key = draft.key();
        Path target = directory.resolve(key + ".yml");
        if (Files.exists(target) || Files.exists(directory.resolve(key + ".yaml"))) {
            return Result.fail("EXISTS", "Un dialogue « " + draft.id() + " » existe déjà — jamais d'écrasement.");
        }

        try {
            Files.createDirectories(directory);
            Path tmp = directory.resolve("." + target.getFileName() + ".tmp");
            Files.writeString(tmp, DialogueDefinitionYaml.render(draft), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }

        DialogueLoadReport report = loader.loadDirectory(directory);
        NamespacedKey expected = NamespacedKey.fromString(draft.id());
        boolean present = expected != null
                && report.loaded().stream().anyMatch(d -> d.id().equals(expected));
        List<String> issuesForFile = report.issues().stream()
                .filter(i -> target.getFileName().toString().equals(i.file()))
                .map(DialogueLoadIssue::message)
                .collect(Collectors.toList());

        if (!present || !issuesForFile.isEmpty()) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // Best effort : le fichier reste, mais il sera rejeté au chargement — signalé ci-dessous.
            }
            return new Result(false, "ERROR",
                    "Le fichier généré ne se recharge pas proprement — création annulée.",
                    target.getFileName().toString(),
                    issuesForFile.isEmpty() ? List.of("dialogue « " + draft.id() + " » absent après rechargement") : issuesForFile);
        }
        return new Result(true, "CREATED", "Dialogue « " + draft.id() + " » créé.",
                target.getFileName().toString(), List.of());
    }
}
