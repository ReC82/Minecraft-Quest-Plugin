package com.lodygames.rpgquest.quest;

import com.lodygames.rpgquest.quest.model.QuestDefinition;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Charge toutes les définitions de quêtes d'un dossier. Chaque fichier est
 * validé indépendamment ({@link QuestDefinitionParser}) : un fichier
 * invalide n'empêche pas le chargement des autres. Une fois tous les
 * fichiers analysés individuellement, une seconde passe rejette les id
 * dupliqués et les quêtes dont un prérequis ne résout vers aucune quête
 * effectivement chargée (y compris en cascade).
 */
public final class QuestLoader {

    private final QuestDefinitionParser parser = new QuestDefinitionParser();

    /** Scanne {@code directory} pour les fichiers {@code *.yml}/{@code *.yaml} (absent = 0 quête, pas une erreur). */
    public QuestLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new QuestLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new QuestLoadReport(List.of(), List.of(
                    new QuestLoadIssue(directory.toString(), "Impossible de lister le dossier de quêtes : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<QuestLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new QuestLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        QuestLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<QuestLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new QuestLoadReport(report.loaded(), merged);
    }

    /** Entrée testable directement avec des sections déjà parsées, sans toucher au disque. */
    public QuestLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedQuest> parsedQuests = new ArrayList<>();
        List<QuestLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            QuestDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsedQuests.add(new ParsedQuest(entry.getKey(), result.quest()));
            } else {
                issues.addAll(result.issues());
            }
        }

        return crossValidate(parsedQuests, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private QuestLoadReport crossValidate(List<ParsedQuest> parsedQuests, List<QuestLoadIssue> issues) {
        List<QuestLoadIssue> allIssues = new ArrayList<>(issues);

        Map<NamespacedKey, List<ParsedQuest>> byId = parsedQuests.stream()
                .collect(Collectors.groupingBy(pq -> pq.quest().id(), LinkedHashMap::new, Collectors.toList()));

        List<ParsedQuest> survivors = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedQuest::fileName).collect(Collectors.joining(", "));
                for (ParsedQuest duplicate : entry.getValue()) {
                    allIssues.add(new QuestLoadIssue(duplicate.fileName(),
                            "id de quête dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                survivors.add(entry.getValue().get(0));
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            Set<NamespacedKey> survivingIds = survivors.stream().map(pq -> pq.quest().id()).collect(Collectors.toSet());
            List<ParsedQuest> stillValid = new ArrayList<>();
            for (ParsedQuest pq : survivors) {
                List<NamespacedKey> missing = pq.quest().prerequisites().stream()
                        .filter(req -> !survivingIds.contains(req))
                        .toList();
                if (missing.isEmpty()) {
                    stillValid.add(pq);
                } else {
                    changed = true;
                    allIssues.add(new QuestLoadIssue(pq.fileName(),
                            "prérequis introuvable pour « " + pq.quest().id() + " » : " + missing
                                    + " (quête absente ou elle-même rejetée)."));
                }
            }
            survivors = stillValid;
        }

        List<QuestDefinition> loaded = survivors.stream().map(ParsedQuest::quest).toList();
        return new QuestLoadReport(loaded, allIssues);
    }

    private record ParsedQuest(String fileName, QuestDefinition quest) {
    }
}
