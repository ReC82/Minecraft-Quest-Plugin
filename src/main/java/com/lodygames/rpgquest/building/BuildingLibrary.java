package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * La bibliothèque de bâtiments : les définitions disponibles, relues depuis
 * {@code plugins/RPGQuest/buildings/*.yml} (issue #213, lot « placement »).
 *
 * <h2>Même conception que les autres registres de contenu</h2>
 *
 * <p>Un exemple embarqué est déposé au premier démarrage, <strong>sans jamais écraser un fichier
 * existant</strong> — c'est la règle du projet, et celle qui évite d'effacer la retouche d'un
 * administrateur à chaque mise à jour du JAR. Un fichier invalide n'empêche pas les autres de
 * charger : il est signalé, nommé, et la bibliothèque continue.</p>
 *
 * <h2>Le schematic manquant n'est pas une erreur de chargement</h2>
 *
 * <p>Une définition dont le fichier {@code .schem} est absent reste <strong>chargée</strong> et
 * visible dans la bibliothèque, marquée « schematic absent ». La distinction compte : la définition
 * est du contenu correct, c'est l'artefact qui manque. L'afficher permet de comprendre pourquoi la
 * pose est refusée ; la cacher laisserait l'administrateur devant une bibliothèque vide sans
 * explication.</p>
 */
public final class BuildingLibrary {

    private static final String[] BUNDLED_EXAMPLES = {"test_hut_01.yml"};

    private final Path directory;
    private final Logger logger;

    private volatile Map<String, BuildingDefinition> definitions = Map.of();
    private volatile List<String> problems = List.of();

    public BuildingLibrary(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    /** Dépose les exemples embarqués puis charge. À appeler au démarrage. */
    public int start() {
        ensureExamplesExist();
        return reload();
    }

    /** Recharge depuis le disque et remplace l'ensemble actif. */
    public int reload() {
        Map<String, BuildingDefinition> loaded = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();

        if (!Files.isDirectory(directory)) {
            this.definitions = Map.of();
            this.problems = List.of();
            return 0;
        }

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.yml")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        } catch (IOException error) {
            logger.log(Level.WARNING, "[building] lecture du dossier " + directory
                    + " impossible : " + error.getClass().getSimpleName());
            this.problems = List.of("Dossier des bâtiments illisible.");
            return 0;
        }
        // Ordre de fichier déterministe : deux démarrages doivent produire la même bibliothèque,
        // et donc le même ordre d'affichage.
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            List<String> fileProblems = new ArrayList<>();
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                Optional<BuildingDefinition> definition =
                        BuildingDefinitionYaml.read(yaml, fileProblems);
                if (definition.isPresent()) {
                    BuildingDefinition found = definition.get();
                    BuildingDefinition clash = loaded.putIfAbsent(found.id(), found);
                    if (clash != null) {
                        fileProblems.add("Identifiant « " + found.id()
                                + " » déjà déclaré par un autre fichier — celui-ci est ignoré.");
                    }
                }
            } catch (RuntimeException error) {
                fileProblems.add("Fichier illisible : " + error.getClass().getSimpleName());
            }
            for (String problem : fileProblems) {
                issues.add(fileName + " : " + problem);
            }
        }

        this.definitions = Map.copyOf(loaded);
        this.problems = List.copyOf(issues);
        logger.info("[building] bibliothèque : " + loaded.size() + " bâtiment(s), "
                + issues.size() + " erreur(s).");
        for (String issue : issues) {
            logger.warning("[building] " + issue);
        }
        return loaded.size();
    }

    /** Les définitions, triées par nom affiché — c'est l'ordre dans lequel on les cherche. */
    public List<BuildingDefinition> all() {
        List<BuildingDefinition> list = new ArrayList<>(definitions.values());
        list.sort(Comparator.comparing(BuildingDefinition::name,
                String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public Optional<BuildingDefinition> find(String id) {
        return id == null ? Optional.empty()
                : Optional.ofNullable(definitions.get(id.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    /** Les problèmes du dernier chargement, pour les afficher dans la bibliothèque. */
    public List<String> problems() {
        return problems;
    }

    public int size() {
        return definitions.size();
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            logger.log(Level.SEVERE, "[building] impossible de créer le dossier " + directory);
            return;
        }
        for (String example : BUNDLED_EXAMPLES) {
            Path target = directory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = BuildingLibrary.class
                    .getResourceAsStream("/buildings/" + example)) {
                if (in == null) {
                    logger.warning("[building] bâtiment d'exemple absent du JAR : " + example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException error) {
                logger.log(Level.SEVERE,
                        "[building] impossible de déposer l'exemple " + example, error);
            }
        }
    }
}
