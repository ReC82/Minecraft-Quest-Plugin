package be.lloyd.rpgquest.resource;

import be.lloyd.rpgquest.resource.model.ResourceNodeDefinition;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Charge tous les types de nœuds de ressource d'un dossier. Même structure à
 * deux phases que {@code QuestLoader}/{@code ItemLoader} : chaque fichier est
 * validé indépendamment ({@link ResourceNodeDefinitionParser}), un fichier
 * invalide n'empêche pas le chargement des autres ; une seconde passe rejette
 * les id dupliqués entre fichiers (pas de notion de prérequis pour un type de
 * nœud).
 */
public final class ResourceNodeLoader {

    private final ResourceNodeDefinitionParser parser = new ResourceNodeDefinitionParser();

    /** Scanne {@code directory} pour les fichiers {@code *.yml}/{@code *.yaml} (absent = 0 type, pas une erreur). */
    public ResourceNodeLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new ResourceNodeLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new ResourceNodeLoadReport(List.of(), List.of(new ResourceNodeLoadIssue(
                    directory.toString(), "Impossible de lister le dossier de nœuds : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<ResourceNodeLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new ResourceNodeLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        ResourceNodeLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<ResourceNodeLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new ResourceNodeLoadReport(report.loaded(), merged);
    }

    /** Entrée testable directement avec des sections déjà parsées, sans toucher au disque. */
    public ResourceNodeLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedNode> parsedNodes = new ArrayList<>();
        List<ResourceNodeLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            ResourceNodeDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsedNodes.add(new ParsedNode(entry.getKey(), result.definition()));
            } else {
                issues.addAll(result.issues());
            }
        }

        return crossValidate(parsedNodes, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private ResourceNodeLoadReport crossValidate(List<ParsedNode> parsedNodes, List<ResourceNodeLoadIssue> issues) {
        List<ResourceNodeLoadIssue> allIssues = new ArrayList<>(issues);

        Map<NamespacedKey, List<ParsedNode>> byId = parsedNodes.stream()
                .collect(Collectors.groupingBy(pn -> pn.definition().id(), LinkedHashMap::new, Collectors.toList()));

        List<ResourceNodeDefinition> survivors = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedNode::fileName).collect(Collectors.joining(", "));
                for (ParsedNode duplicate : entry.getValue()) {
                    allIssues.add(new ResourceNodeLoadIssue(duplicate.fileName(),
                            "id de type de nœud dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                survivors.add(entry.getValue().get(0).definition());
            }
        }

        return new ResourceNodeLoadReport(survivors, allIssues);
    }

    private record ParsedNode(String fileName, ResourceNodeDefinition definition) {
    }
}
