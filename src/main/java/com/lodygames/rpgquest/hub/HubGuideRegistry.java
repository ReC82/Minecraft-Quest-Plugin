package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.bootstrap.PluginService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Charge les Guides de Hub depuis {@code plugins/RPGQuest/hub-guides/} (issue #11, partie A —
 * structure d'extensibilité multi-Hub). Même conception que {@code ZoneRegistry} : un fichier YAML
 * par Hub reste la seule source de vérité, le registre n'expose qu'un index en lecture reconstruit à
 * chaque {@link #reload()}. La V1 ne fournit qu'un seul Hub réel ({@code hub_depart}) ; ajouter un
 * Hub = déposer un fichier, sans toucher au code.
 *
 * <p>Volontairement découplé du moteur de dialogue : le registre ne connaît que l'identifiant du
 * dialogue d'aide ({@link HubGuideDefinition#guideDialogueId()}), il ne le résout pas. Le contenu du
 * menu d'aide vit dans le fichier de dialogue correspondant (réutilisation du système existant).</p>
 */
public final class HubGuideRegistry implements PluginService {

    private static final String[] BUNDLED_EXAMPLES = {"hub_depart.yml"};

    private final Path directory;
    private final Logger logger;
    private final HubGuideLoader loader = new HubGuideLoader();

    private volatile List<HubGuideDefinition> guides = List.of();
    private volatile Map<String, HubGuideDefinition> byHub = Map.of();
    private volatile Map<String, HubGuideDefinition> byWorld = Map.of();
    private volatile HubGuideLoadReport lastReport = new HubGuideLoadReport(List.of(), List.of());

    public HubGuideRegistry(Path directory, Logger logger) {
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
        // Rien à libérer : les définitions vivent en mémoire, pas de ressource externe.
    }

    public HubGuideLoadReport reload() {
        HubGuideLoadReport report = loader.loadDirectory(directory);
        this.guides = report.loaded();

        Map<String, HubGuideDefinition> hubIndex = new HashMap<>();
        Map<String, HubGuideDefinition> worldIndex = new HashMap<>();
        for (HubGuideDefinition guide : report.loaded()) {
            hubIndex.put(guide.hubId(), guide);
            for (String world : guide.worlds()) {
                worldIndex.put(world, guide);
            }
        }
        this.byHub = Map.copyOf(hubIndex);
        this.byWorld = Map.copyOf(worldIndex);
        this.lastReport = report;

        logger.info("Chargement des Guides de Hub : {} chargé(s), {} erreur(s).",
                report.loaded().size(), report.issues().size());
        for (HubGuideLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
        return report;
    }

    public List<HubGuideDefinition> all() {
        return guides;
    }

    public Optional<HubGuideDefinition> forHub(String hubId) {
        return Optional.ofNullable(byHub.get(hubId));
    }

    public Optional<HubGuideDefinition> forWorld(String world) {
        return Optional.ofNullable(byWorld.get(world));
    }

    public HubGuideLoadReport lastReport() {
        return lastReport;
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier hub-guides {}.", directory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = directory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = HubGuideRegistry.class.getResourceAsStream("/hub-guides/" + example)) {
                if (in == null) {
                    logger.warn("Guide de Hub d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer le Guide de Hub d'exemple {}.", example, e);
            }
        }
    }
}
