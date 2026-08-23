package com.lodygames.rpgquest.quest;

import com.lodygames.rpgquest.quest.model.QuestDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.slf4j.Logger;

/**
 * Implémentation {@code PluginService} de {@link QuestEngine} : charge les
 * définitions de quêtes YAML depuis {@code questsDirectory}. Ne dépend que
 * de {@link Path}/{@link Logger} (pas de {@code JavaPlugin}), les exemples
 * étant lus depuis le classpath du plugin — testable sans MockBukkit.
 */
public final class YamlQuestEngine implements QuestEngine {

    private static final String[] BUNDLED_EXAMPLES =
            {"premiers_pas.yml", "first_steps.yml", "woodcutters_request.yml", "crystal_hunt.yml"};

    private final Path questsDirectory;
    private final Logger logger;
    private final QuestLoader loader = new QuestLoader();

    private volatile List<QuestDefinition> quests = List.of();
    private volatile QuestLoadReport lastReport = new QuestLoadReport(List.of(), List.of());

    public YamlQuestEngine(Path questsDirectory, Logger logger) {
        this.questsDirectory = questsDirectory;
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

    /** Recharge depuis le disque et remplace l'ensemble de quêtes actif. */
    public QuestLoadReport reload() {
        QuestLoadReport report = loader.loadDirectory(questsDirectory);
        this.quests = report.loaded();
        this.lastReport = report;
        logReport("Chargement", report);
        return report;
    }

    /** Charge et valide depuis le disque sans toucher à l'ensemble de quêtes actif (dry-run). */
    public QuestLoadReport validate() {
        QuestLoadReport report = loader.loadDirectory(questsDirectory);
        logReport("Validation", report);
        return report;
    }

    public List<QuestDefinition> quests() {
        return quests;
    }

    public QuestLoadReport lastReport() {
        return lastReport;
    }

    public Optional<QuestDefinition> find(NamespacedKey id) {
        return quests.stream().filter(quest -> quest.id().equals(id)).findFirst();
    }

    private void logReport(String action, QuestLoadReport report) {
        logger.info("{} des quêtes : {} chargée(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (QuestLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(questsDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de quêtes {}.", questsDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = questsDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = YamlQuestEngine.class.getResourceAsStream("/quests/" + example)) {
                if (in == null) {
                    logger.warn("Quête d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer la quête d'exemple {}.", example, e);
            }
        }
    }
}
