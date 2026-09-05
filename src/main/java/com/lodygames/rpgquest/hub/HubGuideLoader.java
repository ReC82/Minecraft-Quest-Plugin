package com.lodygames.rpgquest.hub;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Charge tous les Guides de Hub d'un dossier. Même structure à deux phases que {@code ZoneLoader} :
 * chaque fichier validé indépendamment ({@link HubGuideDefinitionParser}), un fichier invalide
 * n'empêche pas le chargement des autres ; une seconde passe rejette les {@code hub-id} dupliqués
 * <strong>et</strong> les mondes revendiqués par deux Hubs différents (un monde ↦ un seul Guide).
 */
public final class HubGuideLoader {

    private final HubGuideDefinitionParser parser = new HubGuideDefinitionParser();

    public HubGuideLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new HubGuideLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new HubGuideLoadReport(List.of(), List.of(new HubGuideLoadIssue(
                    directory.toString(), "Impossible de lister le dossier hub-guides : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<HubGuideLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new HubGuideLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        HubGuideLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<HubGuideLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new HubGuideLoadReport(report.loaded(), merged);
    }

    /** Entrée testable directement avec des sections déjà parsées, sans toucher au disque. */
    public HubGuideLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedGuide> parsed = new ArrayList<>();
        List<HubGuideLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            HubGuideDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsed.add(new ParsedGuide(entry.getKey(), result.definition()));
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

    private HubGuideLoadReport crossValidate(List<ParsedGuide> parsed, List<HubGuideLoadIssue> issues) {
        List<HubGuideLoadIssue> allIssues = new ArrayList<>(issues);

        Map<String, List<ParsedGuide>> byHub = parsed.stream()
                .collect(Collectors.groupingBy(pg -> pg.definition().hubId(), LinkedHashMap::new, Collectors.toList()));

        List<ParsedGuide> afterDuplicateCheck = new ArrayList<>();
        for (var entry : byHub.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedGuide::fileName).collect(Collectors.joining(", "));
                for (ParsedGuide duplicate : entry.getValue()) {
                    allIssues.add(new HubGuideLoadIssue(duplicate.fileName(),
                            "hub-id dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                afterDuplicateCheck.add(entry.getValue().get(0));
            }
        }

        Set<String> claimedWorlds = new LinkedHashSet<>();
        List<HubGuideDefinition> survivors = new ArrayList<>();
        for (ParsedGuide candidate : afterDuplicateCheck) {
            String clash = candidate.definition().worlds().stream()
                    .filter(claimedWorlds::contains)
                    .findFirst()
                    .orElse(null);
            if (clash != null) {
                allIssues.add(new HubGuideLoadIssue(candidate.fileName(),
                        "le monde « " + clash + " » est déjà associé à un autre Guide de Hub."));
            } else {
                claimedWorlds.addAll(candidate.definition().worlds());
                survivors.add(candidate.definition());
            }
        }

        return new HubGuideLoadReport(survivors, allIssues);
    }

    private record ParsedGuide(String fileName, HubGuideDefinition definition) {
    }
}
