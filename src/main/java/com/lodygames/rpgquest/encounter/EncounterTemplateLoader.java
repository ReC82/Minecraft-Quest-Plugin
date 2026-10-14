package com.lodygames.rpgquest.encounter;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
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
 * Charge tous les modèles de PNJ récurrents d'un dossier (issue #13) — même conception que {@code NpcDefinitionLoader} :
 * chaque fichier est validé indépendamment ({@link EncounterTemplateParser}), un fichier invalide
 * n'empêche pas les autres, puis une seconde passe rejette les {@code id} dupliqués entre fichiers.
 */
public final class EncounterTemplateLoader {

    private final EncounterTemplateParser parser = new EncounterTemplateParser();

    /** Scanne {@code directory} pour les {@code *.yml}/{@code *.yaml} (dossier absent = 0 modèle, pas une erreur). */
    public EncounterLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new EncounterLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new EncounterLoadReport(List.of(), List.of(
                    new EncounterLoadIssue(directory.toString(), "Impossible de lister le dossier des PNJ récurrents : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<EncounterLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new EncounterLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        EncounterLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<EncounterLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new EncounterLoadReport(report.loaded(), merged);
    }

    /** Entrée testable avec des sections déjà parsées, sans toucher au disque. */
    public EncounterLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<Parsed> parsed = new ArrayList<>();
        List<EncounterLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            EncounterTemplateParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsed.add(new Parsed(entry.getKey(), result.template()));
            } else {
                issues.addAll(result.issues());
            }
        }

        Map<String, List<Parsed>> byId = new LinkedHashMap<>();
        for (Parsed p : parsed) {
            byId.computeIfAbsent(p.template().id(), k -> new ArrayList<>()).add(p);
        }

        List<EncounterTemplate> loaded = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(Parsed::fileName).reduce((a, b) -> a + ", " + b).orElse("");
                for (Parsed duplicate : entry.getValue()) {
                    issues.add(new EncounterLoadIssue(duplicate.fileName(),
                            "id de modèle dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                loaded.add(entry.getValue().get(0).template());
            }
        }
        return new EncounterLoadReport(loaded, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private record Parsed(String fileName, EncounterTemplate template) {
    }
}
