package com.lodygames.rpgquest.resource;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.resource.model.ResourceNodeDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.slf4j.Logger;

/**
 * Charge les <b>types</b> de nœuds de ressource depuis {@code
 * plugins/RPGQuest/resource-nodes/*.yml} (les positions concrètes vivent dans
 * {@link ResourceNodeService}, pas ici). Même conception que {@code
 * YamlCustomItemRegistry}/{@code YamlQuestEngine} : ne dépend que de {@link
 * Path}/{@link Logger}, génère un exemple embarqué au premier démarrage sans
 * jamais écraser un fichier existant.
 */
public final class ResourceNodeRegistry implements PluginService {

    private static final String[] BUNDLED_EXAMPLES = {"crystal_ore.yml"};

    private final Path nodesDirectory;
    private final Logger logger;
    private final ResourceNodeLoader loader = new ResourceNodeLoader();

    private volatile List<ResourceNodeDefinition> types = List.of();
    private volatile ResourceNodeLoadReport lastReport = new ResourceNodeLoadReport(List.of(), List.of());

    public ResourceNodeRegistry(Path nodesDirectory, Logger logger) {
        this.nodesDirectory = nodesDirectory;
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

    /** Recharge depuis le disque et remplace l'ensemble de types actif. */
    public ResourceNodeLoadReport reload() {
        ResourceNodeLoadReport report = loader.loadDirectory(nodesDirectory);
        this.types = report.loaded();
        this.lastReport = report;
        logReport("Chargement", report);
        return report;
    }

    /** Charge et valide depuis le disque sans toucher à l'ensemble actif (dry-run). */
    public ResourceNodeLoadReport validate() {
        ResourceNodeLoadReport report = loader.loadDirectory(nodesDirectory);
        logReport("Validation", report);
        return report;
    }

    public List<ResourceNodeDefinition> types() {
        return types;
    }

    public ResourceNodeLoadReport lastReport() {
        return lastReport;
    }

    public Optional<ResourceNodeDefinition> find(NamespacedKey id) {
        return types.stream().filter(type -> type.id().equals(id)).findFirst();
    }

    private void logReport(String action, ResourceNodeLoadReport report) {
        logger.info("{} des types de nœuds de ressource : {} chargé(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (ResourceNodeLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(nodesDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de types de nœuds {}.", nodesDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = nodesDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = ResourceNodeRegistry.class.getResourceAsStream("/resource-nodes/" + example)) {
                if (in == null) {
                    logger.warn("Type de nœud d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer le type de nœud d'exemple {}.", example, e);
            }
        }
    }
}
