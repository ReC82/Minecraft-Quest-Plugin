package com.lodygames.rpgquest.story;

import com.lodygames.rpgquest.story.model.StoryDefinition;
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
 * Charge toutes les stories d'un dossier. Même structure à deux phases que {@code
 * travel.PortalLoader} : chaque fichier validé indépendamment, puis rejet des id dupliqués entre
 * fichiers — pas de notion de chevauchement spatial ici (une Story n'est pas un cuboïde).
 */
public final class StoryLoader {

    private final StoryDefinitionParser parser = new StoryDefinitionParser();

    public StoryLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new StoryLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new StoryLoadReport(List.of(), List.of(new StoryLoadIssue(
                    directory.toString(), "Impossible de lister le dossier de stories : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<StoryLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new StoryLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        StoryLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<StoryLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new StoryLoadReport(report.loaded(), merged);
    }

    public StoryLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedStory> parsed = new ArrayList<>();
        List<StoryLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            StoryDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsed.add(new ParsedStory(entry.getKey(), result.story()));
            } else {
                issues.addAll(result.issues());
            }
        }

        return rejectDuplicateIds(parsed, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private StoryLoadReport rejectDuplicateIds(List<ParsedStory> parsed, List<StoryLoadIssue> issues) {
        List<StoryLoadIssue> allIssues = new ArrayList<>(issues);

        Map<String, List<ParsedStory>> byId = parsed.stream()
                .collect(Collectors.groupingBy(ps -> ps.story().id(), LinkedHashMap::new, Collectors.toList()));

        List<StoryDefinition> survivors = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedStory::fileName).collect(Collectors.joining(", "));
                for (ParsedStory duplicate : entry.getValue()) {
                    allIssues.add(new StoryLoadIssue(duplicate.fileName(),
                            "id de story dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                survivors.add(entry.getValue().get(0).story());
            }
        }

        return new StoryLoadReport(survivors, allIssues);
    }

    private record ParsedStory(String fileName, StoryDefinition story) {
    }
}
