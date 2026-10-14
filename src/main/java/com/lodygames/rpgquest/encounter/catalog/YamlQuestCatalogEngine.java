package com.lodygames.rpgquest.encounter.catalog;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.encounter.EncounterLoadIssue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Registre des catalogues de quêtes secondaires (issue #369), chargés depuis
 * {@code plugins/RPGQuest/quest-catalogs/*.yml}. Purement données : {@link #reload()} est sûr hors
 * thread principal et ne touche à aucune progression de joueur.
 */
public final class YamlQuestCatalogEngine implements PluginService {

    /** Exemple livré, copié une seule fois s'il manque — rattaché à aucun modèle par défaut. */
    static final String[] BUNDLED_EXAMPLES = {"encounter_side_quests.yml"};

    private final Path directory;
    private final Logger logger;
    private final QuestCatalogLoader loader = new QuestCatalogLoader();

    private volatile QuestCatalogLoadReport lastReport = new QuestCatalogLoadReport(List.of(), List.of());

    public YamlQuestCatalogEngine(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    @Override
    public void start() {
        ensureExamplesExist();
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : catalogues en mémoire.
    }

    public QuestCatalogLoadReport reload() {
        QuestCatalogLoadReport report = loader.loadDirectory(directory);
        this.lastReport = report;
        logger.info("Chargement des catalogues de quêtes : {} catalogue(s), {} erreur(s).",
                report.loaded().size(), report.issues().size());
        for (EncounterLoadIssue issue : report.issues()) {
            logger.warn("[quest-catalogs/{}] {}", issue.file(), issue.message());
        }
        return report;
    }

    public Path directory() {
        return directory;
    }

    public List<QuestCatalog> catalogs() {
        return lastReport.loaded();
    }

    public QuestCatalogLoadReport lastReport() {
        return lastReport;
    }

    public Optional<QuestCatalog> find(String id) {
        return catalogs().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier des catalogues de quêtes {}.", directory, e);
            return;
        }
        for (String example : BUNDLED_EXAMPLES) {
            Path target = directory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = YamlQuestCatalogEngine.class.getResourceAsStream("/quest-catalogs/" + example)) {
                if (in == null) {
                    logger.warn("Catalogue de quêtes d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer le catalogue de quêtes d'exemple {}.", example, e);
            }
        }
    }
}
