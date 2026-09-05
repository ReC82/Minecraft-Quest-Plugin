package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.slf4j.Logger;

/**
 * Charge les variantes de mobs spéciaux depuis {@code
 * plugins/RPGQuest/mobs/*.yml}. Même conception que {@code
 * ResourceNodeRegistry} : ne dépend que de {@link Path}/{@link Logger},
 * génère les exemples embarqués au premier démarrage sans jamais écraser un
 * fichier existant.
 */
public final class SpecialMobRegistry implements PluginService {

    private static final String[] BUNDLED_EXAMPLES = {
            "red_creeper.yml", "golden_creeper.yml", "creeper_pig.yml", "splitting_zombie.yml"
    };

    private final Path mobsDirectory;
    private final Logger logger;
    private final SpecialMobLoader loader = new SpecialMobLoader();

    private volatile List<SpecialMobDefinition> definitions = List.of();
    private volatile SpecialMobLoadReport lastReport = new SpecialMobLoadReport(List.of(), List.of());

    public SpecialMobRegistry(Path mobsDirectory, Logger logger) {
        this.mobsDirectory = mobsDirectory;
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

    /** Recharge depuis le disque et remplace l'ensemble de définitions actif. */
    public SpecialMobLoadReport reload() {
        SpecialMobLoadReport report = loader.loadDirectory(mobsDirectory);
        this.definitions = report.loaded();
        this.lastReport = report;
        logReport("Chargement", report);
        return report;
    }

    /** Charge et valide depuis le disque sans toucher à l'ensemble actif (dry-run). */
    public SpecialMobLoadReport validate() {
        SpecialMobLoadReport report = loader.loadDirectory(mobsDirectory);
        logReport("Validation", report);
        return report;
    }

    public List<SpecialMobDefinition> definitions() {
        return definitions;
    }

    public SpecialMobLoadReport lastReport() {
        return lastReport;
    }

    public Optional<SpecialMobDefinition> find(NamespacedKey id) {
        return definitions.stream().filter(def -> def.id().equals(id)).findFirst();
    }

    private void logReport(String action, SpecialMobLoadReport report) {
        logger.info("{} des mobs spéciaux : {} chargé(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (SpecialMobLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(mobsDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de mobs spéciaux {}.", mobsDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = mobsDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = SpecialMobRegistry.class.getResourceAsStream("/mobs/" + example)) {
                if (in == null) {
                    logger.warn("Mob spécial d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer le mob spécial d'exemple {}.", example, e);
            }
        }
    }
}
