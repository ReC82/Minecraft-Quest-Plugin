package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.travel.model.Destination;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Charge toutes les destinations d'un dossier. Même structure à deux phases
 * que {@code ZoneLoader} : chaque fichier validé indépendamment, une
 * seconde passe rejette uniquement les id dupliqués entre fichiers (deux
 * destinations peuvent parfaitement se superposer, contrairement aux
 * zones — rien n'empêche deux noms de pointer au même endroit).
 */
public final class DestinationLoader {

    private final DestinationDefinitionParser parser = new DestinationDefinitionParser();

    public DestinationLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new DestinationLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new DestinationLoadReport(List.of(), List.of(new DestinationLoadIssue(
                    directory.toString(), "Impossible de lister le dossier de destinations : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<DestinationLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new DestinationLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        DestinationLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<DestinationLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new DestinationLoadReport(report.loaded(), merged);
    }

    public DestinationLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedDestination> parsed = new ArrayList<>();
        List<DestinationLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            DestinationDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsed.add(new ParsedDestination(entry.getKey(), result.destination()));
            } else {
                issues.addAll(result.issues());
            }
        }

        return crossValidate(parsed, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private DestinationLoadReport crossValidate(List<ParsedDestination> parsed, List<DestinationLoadIssue> issues) {
        List<DestinationLoadIssue> allIssues = new ArrayList<>(issues);

        Map<String, List<ParsedDestination>> byId = parsed.stream()
                .collect(Collectors.groupingBy(pd -> pd.destination().id(), LinkedHashMap::new, Collectors.toList()));

        List<Destination> survivors = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedDestination::fileName).collect(Collectors.joining(", "));
                for (ParsedDestination duplicate : entry.getValue()) {
                    allIssues.add(new DestinationLoadIssue(duplicate.fileName(),
                            "id de destination dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                survivors.add(entry.getValue().get(0).destination());
            }
        }

        return new DestinationLoadReport(survivors, allIssues);
    }

    private record ParsedDestination(String fileName, Destination destination) {
    }
}
