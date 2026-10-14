package com.lodygames.rpgquest.encounter.catalog;

import com.lodygames.rpgquest.encounter.EncounterLoadIssue;
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
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Charge les catalogues d'un dossier (issue #369), comme {@code EncounterTemplateLoader} : chaque
 * fichier est validé seul, puis les {@code id} dupliqués entre fichiers sont tous rejetés.
 */
public final class QuestCatalogLoader {

    private final QuestCatalogParser parser = new QuestCatalogParser();

    public QuestCatalogLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new QuestCatalogLoadReport(List.of(), List.of());
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, QuestCatalogLoader::isYamlFile)) {
            stream.forEach(files::add);
        } catch (IOException e) {
            return new QuestCatalogLoadReport(List.of(), List.of(new EncounterLoadIssue(directory.toString(),
                    "Impossible de lister le dossier des catalogues : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        List<EncounterLoadIssue> issues = new ArrayList<>();
        Map<String, List<String>> filesById = new LinkedHashMap<>();
        Map<String, QuestCatalog> byId = new LinkedHashMap<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file.toFile());
            } catch (IOException | InvalidConfigurationException e) {
                issues.add(new EncounterLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
                continue;
            }
            QuestCatalogParser.ParseResult result = parser.parse(name, yaml);
            if (!result.isSuccess()) {
                issues.addAll(result.issues());
                continue;
            }
            filesById.computeIfAbsent(result.catalog().id(), k -> new ArrayList<>()).add(name);
            byId.putIfAbsent(result.catalog().id(), result.catalog());
        }
        List<QuestCatalog> loaded = new ArrayList<>();
        for (var entry : filesById.entrySet()) {
            if (entry.getValue().size() > 1) {
                String all = String.join(", ", entry.getValue());
                for (String name : entry.getValue()) {
                    issues.add(new EncounterLoadIssue(name, "id de catalogue dupliqué « " + entry.getKey()
                            + " » (aussi présent dans : " + all + ")."));
                }
            } else {
                loaded.add(byId.get(entry.getKey()));
            }
        }
        return new QuestCatalogLoadReport(loaded, issues);
    }

    private static boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }
}
