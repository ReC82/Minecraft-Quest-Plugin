package com.lodygames.rpgquest.story;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Charge les définitions de Story depuis {@code plugins/RPGQuest/stories/} — même conception que
 * {@code zone.ZoneRegistry} (dossier + exemple embarqué copié au premier démarrage). Purement
 * lecture seule à l'exécution : contrairement à {@code zone.ZoneRegistry}/{@code
 * travel.YamlPortalRegistry}, il n'existe pas de commande {@code /rpgadmin story create}/{@code
 * delete} à cette étape (mission : uniquement {@code info}/{@code start}/{@code reset}), donc pas
 * de méthode d'écriture ici.
 */
public final class StoryRegistry implements PluginService {

    private static final String[] BUNDLED_EXAMPLES = {"main_story.yml"};

    private final Path storiesDirectory;
    private final Logger logger;
    private final StoryLoader loader = new StoryLoader();

    private volatile List<StoryDefinition> stories = List.of();
    private volatile StoryLoadReport lastReport = new StoryLoadReport(List.of(), List.of());

    public StoryRegistry(Path storiesDirectory, Logger logger) {
        this.storiesDirectory = storiesDirectory;
        this.logger = logger;
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

    public StoryLoadReport reload() {
        StoryLoadReport report = loader.loadDirectory(storiesDirectory);
        this.stories = report.loaded();
        this.lastReport = report;
        logReport("Chargement", report);
        return report;
    }

    public List<StoryDefinition> stories() {
        return stories;
    }

    public Optional<StoryDefinition> find(String id) {
        return stories.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public StoryLoadReport lastReport() {
        return lastReport;
    }

    private void logReport(String action, StoryLoadReport report) {
        logger.info("{} des stories : {} chargée(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (StoryLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(storiesDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de stories {}.", storiesDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = storiesDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = StoryRegistry.class.getResourceAsStream("/stories/" + example)) {
                if (in == null) {
                    logger.warn("Story d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer la story d'exemple {}.", example, e);
            }
        }
    }
}
