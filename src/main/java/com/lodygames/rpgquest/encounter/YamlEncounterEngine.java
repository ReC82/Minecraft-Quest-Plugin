package com.lodygames.rpgquest.encounter;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;

/**
 * Registre des modèles de PNJ récurrents (issue #13), chargés depuis
 * {@code plugins/RPGQuest/encounters/*.yml} — même {@code PluginService} que {@code YamlNpcEngine}.
 * Purement données : {@link #reload()} est sûr hors thread principal et ne touche à aucun PNJ déjà
 * apparu (le cycle de vie des occurrences appartient à {@link EncounterService}).
 */
public final class YamlEncounterEngine implements PluginService {

    /** Modèles livrés, copiés une seule fois s'ils manquent — jamais réécrits ensuite. */
    static final String[] BUNDLED_EXAMPLES = {"forest_bandit.yml", "desert_merchant.yml", "mountain_smith.yml"};

    private final Path directory;
    private final Logger logger;
    private final Predicate<String> knownBiome;
    private final EncounterTemplateLoader loader = new EncounterTemplateLoader();

    private volatile List<EncounterTemplate> templates = List.of();
    private volatile EncounterLoadReport lastReport = new EncounterLoadReport(List.of(), List.of());

    /**
     * @param knownBiome vrai si la clé de biome existe sur ce serveur. Un biome inconnu n'invalide
     *                   pas le modèle (un datapack peut l'ajouter plus tard) mais il est signalé :
     *                   une faute de frappe ne doit pas rester silencieuse.
     */
    public YamlEncounterEngine(Path directory, Logger logger, Predicate<String> knownBiome) {
        this.directory = directory;
        this.logger = logger;
        this.knownBiome = knownBiome;
    }

    @Override
    public void start() {
        ensureExamplesExist();
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : modèles en mémoire.
    }

    public EncounterLoadReport reload() {
        EncounterLoadReport report = loader.loadDirectory(directory);
        this.templates = report.loaded();
        this.lastReport = report;
        logger.info("Chargement des PNJ récurrents : {} modèle(s), {} erreur(s).",
                report.loaded().size(), report.issues().size());
        for (EncounterLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
        for (EncounterTemplate template : report.loaded()) {
            for (String biome : template.biomes()) {
                if (!knownBiome.test(biome)) {
                    logger.warn("[{}] biome inconnu sur ce serveur : {} — ignoré tant qu'il n'existe pas.",
                            template.id(), biome);
                }
            }
        }
        return report;
    }

    public Path directory() {
        return directory;
    }

    public List<EncounterTemplate> templates() {
        return templates;
    }

    public EncounterLoadReport lastReport() {
        return lastReport;
    }

    public Optional<EncounterTemplate> find(String id) {
        return templates.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier des PNJ récurrents {}.", directory, e);
            return;
        }
        for (String example : BUNDLED_EXAMPLES) {
            Path target = directory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = YamlEncounterEngine.class.getResourceAsStream("/encounters/" + example)) {
                if (in == null) {
                    logger.warn("Modèle de PNJ récurrent d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer le modèle de PNJ récurrent d'exemple {}.", example, e);
            }
        }
    }
}
