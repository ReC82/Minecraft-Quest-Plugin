package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.npc.model.NpcDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Registre des <strong>définitions logiques</strong> de PNJ (V2), chargées depuis
 * {@code plugins/RPGQuest/npcs/*.yml} — même {@code PluginService} que {@code YamlQuestEngine} /
 * {@code YamlDialogueEngine}. Purement données : aucun listener à (dé)brancher, donc
 * {@link #reload()} est sûr hors thread principal.
 */
public final class YamlNpcEngine implements PluginService {

    private static final String[] BUNDLED_EXAMPLES = {"guard.yml"};

    private final Path directory;
    private final Logger logger;
    private final NpcDefinitionLoader loader = new NpcDefinitionLoader();

    private volatile List<NpcDefinition> definitions = List.of();
    private volatile NpcLoadReport lastReport = new NpcLoadReport(List.of(), List.of());

    public YamlNpcEngine(Path directory, Logger logger) {
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
        // Rien à libérer : définitions en mémoire.
    }

    public NpcLoadReport reload() {
        NpcLoadReport report = loader.loadDirectory(directory);
        this.definitions = report.loaded();
        this.lastReport = report;
        logger.info("Chargement des PNJ : {} définition(s), {} erreur(s).",
                report.loaded().size(), report.issues().size());
        for (NpcLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
        return report;
    }

    public Path directory() {
        return directory;
    }

    public List<NpcDefinition> definitions() {
        return definitions;
    }

    public NpcLoadReport lastReport() {
        return lastReport;
    }

    public Optional<NpcDefinition> find(String id) {
        return definitions.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier des PNJ {}.", directory, e);
            return;
        }
        for (String example : BUNDLED_EXAMPLES) {
            Path target = directory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = YamlNpcEngine.class.getResourceAsStream("/npcs/" + example)) {
                if (in == null) {
                    logger.warn("Définition PNJ d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer la définition PNJ d'exemple {}.", example, e);
            }
        }
    }
}
