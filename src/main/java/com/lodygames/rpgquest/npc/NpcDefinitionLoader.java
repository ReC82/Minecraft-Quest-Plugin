package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.npc.model.NpcDefinition;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Charge toutes les définitions PNJ d'un dossier — même conception que {@code QuestLoader} :
 * chaque fichier est validé indépendamment ({@link NpcDefinitionParser}), un fichier invalide
 * n'empêche pas les autres, puis une seconde passe rejette les {@code id} dupliqués entre fichiers.
 */
public final class NpcDefinitionLoader {

    private final NpcDefinitionParser parser = new NpcDefinitionParser();

    /** Scanne {@code directory} pour les {@code *.yml}/{@code *.yaml} (dossier absent = 0 PNJ, pas une erreur). */
    public NpcLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new NpcLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new NpcLoadReport(List.of(), List.of(
                    new NpcLoadIssue(directory.toString(), "Impossible de lister le dossier des PNJ : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<NpcLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new NpcLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        NpcLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<NpcLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new NpcLoadReport(report.loaded(), merged);
    }

    /** Entrée testable avec des sections déjà parsées, sans toucher au disque. */
    public NpcLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<Parsed> parsed = new ArrayList<>();
        List<NpcLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            NpcDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsed.add(new Parsed(entry.getKey(), result.definition()));
            } else {
                issues.addAll(result.issues());
            }
        }

        Map<String, List<Parsed>> byId = new LinkedHashMap<>();
        for (Parsed p : parsed) {
            byId.computeIfAbsent(p.definition().id(), k -> new ArrayList<>()).add(p);
        }

        List<NpcDefinition> loaded = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(Parsed::fileName).reduce((a, b) -> a + ", " + b).orElse("");
                for (Parsed duplicate : entry.getValue()) {
                    issues.add(new NpcLoadIssue(duplicate.fileName(),
                            "id de PNJ dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                loaded.add(entry.getValue().get(0).definition());
            }
        }
        return new NpcLoadReport(loaded, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private record Parsed(String fileName, NpcDefinition definition) {
    }
}
