package be.lloyd.rpgquest.dialogue;

import be.lloyd.rpgquest.dialogue.model.DialogueDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.slf4j.Logger;

/**
 * Implémentation {@code PluginService} de {@link DialogueEngine} : charge
 * les définitions de dialogues YAML depuis {@code dialoguesDirectory}. Ne
 * dépend que de {@link Path}/{@link Logger}/la liste blanche de commandes
 * (pas de {@code JavaPlugin}) — testable sans MockBukkit, à l'identique de
 * {@code YamlQuestEngine}.
 */
public final class YamlDialogueEngine implements DialogueEngine {

    private static final String[] BUNDLED_EXAMPLES = {"guard.yml"};

    private final Path dialoguesDirectory;
    private final Logger logger;
    private final DialogueLoader loader;

    private volatile List<DialogueDefinition> dialogues = List.of();
    private volatile DialogueLoadReport lastReport = new DialogueLoadReport(List.of(), List.of());

    public YamlDialogueEngine(Path dialoguesDirectory, Logger logger, List<String> allowedCommands) {
        this.dialoguesDirectory = dialoguesDirectory;
        this.logger = logger;
        this.loader = new DialogueLoader(allowedCommands);
    }

    @Override
    public void start() {
        ensureExamplesExist();
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : les définitions vivent en mémoire, pas de ressource externe.
    }

    public DialogueLoadReport reload() {
        DialogueLoadReport report = loader.loadDirectory(dialoguesDirectory);
        this.dialogues = report.loaded();
        this.lastReport = report;
        logReport(report);
        return report;
    }

    public List<DialogueDefinition> dialogues() {
        return dialogues;
    }

    public DialogueLoadReport lastReport() {
        return lastReport;
    }

    public Optional<DialogueDefinition> find(NamespacedKey id) {
        return dialogues.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    private void logReport(DialogueLoadReport report) {
        logger.info("Chargement des dialogues : {} chargé(s), {} erreur(s).",
                report.loaded().size(), report.issues().size());
        for (DialogueLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(dialoguesDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de dialogues {}.", dialoguesDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = dialoguesDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = YamlDialogueEngine.class.getResourceAsStream("/dialogues/" + example)) {
                if (in == null) {
                    logger.warn("Dialogue d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer le dialogue d'exemple {}.", example, e);
            }
        }
    }
}
