package be.lloyd.rpgquest.item;

import be.lloyd.rpgquest.item.model.CustomItemDefinition;
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
 * Charge toutes les définitions d'objets d'un dossier. Même structure à deux
 * phases que {@code QuestLoader} : chaque fichier est validé indépendamment
 * ({@link ItemDefinitionParser}), un fichier invalide n'empêche pas le
 * chargement des autres ; une seconde passe rejette les id dupliqués entre
 * fichiers (pas de notion de prérequis pour un objet, contrairement à une
 * quête : la validation croisée s'arrête donc là).
 */
public final class ItemLoader {

    private final ItemDefinitionParser parser = new ItemDefinitionParser();

    /** Scanne {@code directory} pour les fichiers {@code *.yml}/{@code *.yaml} (absent = 0 objet, pas une erreur). */
    public ItemLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new ItemLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new ItemLoadReport(List.of(), List.of(
                    new ItemLoadIssue(directory.toString(), "Impossible de lister le dossier d'objets : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<ItemLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new ItemLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        ItemLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<ItemLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new ItemLoadReport(report.loaded(), merged);
    }

    /** Entrée testable directement avec des sections déjà parsées, sans toucher au disque. */
    public ItemLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedItem> parsedItems = new ArrayList<>();
        List<ItemLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            ItemDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsedItems.add(new ParsedItem(entry.getKey(), result.item()));
            } else {
                issues.addAll(result.issues());
            }
        }

        return crossValidate(parsedItems, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private ItemLoadReport crossValidate(List<ParsedItem> parsedItems, List<ItemLoadIssue> issues) {
        List<ItemLoadIssue> allIssues = new ArrayList<>(issues);

        Map<NamespacedKey, List<ParsedItem>> byId = parsedItems.stream()
                .collect(Collectors.groupingBy(pi -> pi.item().id(), LinkedHashMap::new, Collectors.toList()));

        List<CustomItemDefinition> survivors = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedItem::fileName).collect(Collectors.joining(", "));
                for (ParsedItem duplicate : entry.getValue()) {
                    allIssues.add(new ItemLoadIssue(duplicate.fileName(),
                            "id d'objet dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                survivors.add(entry.getValue().get(0).item());
            }
        }

        return new ItemLoadReport(survivors, allIssues);
    }

    private record ParsedItem(String fileName, CustomItemDefinition item) {
    }
}
