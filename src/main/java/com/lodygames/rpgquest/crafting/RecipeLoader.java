package com.lodygames.rpgquest.crafting;

import com.lodygames.rpgquest.crafting.model.RecipeDefinition;
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
 * Charge toutes les recettes d'un dossier. Même structure à deux phases que
 * {@code QuestLoader}/{@code ItemLoader}/{@code ResourceNodeLoader} : chaque
 * fichier est validé indépendamment ({@link RecipeDefinitionParser}), un
 * fichier invalide n'empêche pas le chargement des autres ; une seconde passe
 * rejette les id dupliqués entre fichiers.
 */
public final class RecipeLoader {

    private final RecipeDefinitionParser parser = new RecipeDefinitionParser();

    /** Scanne {@code directory} pour les fichiers {@code *.yml}/{@code *.yaml} (absent = 0 recette, pas une erreur). */
    public RecipeLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new RecipeLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new RecipeLoadReport(List.of(), List.of(new RecipeLoadIssue(
                    directory.toString(), "Impossible de lister le dossier de recettes : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<RecipeLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new RecipeLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        RecipeLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<RecipeLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new RecipeLoadReport(report.loaded(), merged);
    }

    /** Entrée testable directement avec des sections déjà parsées, sans toucher au disque. */
    public RecipeLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedRecipe> parsedRecipes = new ArrayList<>();
        List<RecipeLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            RecipeDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsedRecipes.add(new ParsedRecipe(entry.getKey(), result.recipe()));
            } else {
                issues.addAll(result.issues());
            }
        }

        return crossValidate(parsedRecipes, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private RecipeLoadReport crossValidate(List<ParsedRecipe> parsedRecipes, List<RecipeLoadIssue> issues) {
        List<RecipeLoadIssue> allIssues = new ArrayList<>(issues);

        Map<NamespacedKey, List<ParsedRecipe>> byId = parsedRecipes.stream()
                .collect(Collectors.groupingBy(pr -> pr.recipe().id(), LinkedHashMap::new, Collectors.toList()));

        List<RecipeDefinition> survivors = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedRecipe::fileName).collect(Collectors.joining(", "));
                for (ParsedRecipe duplicate : entry.getValue()) {
                    allIssues.add(new RecipeLoadIssue(duplicate.fileName(),
                            "id de recette dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                survivors.add(entry.getValue().get(0).recipe());
            }
        }

        return new RecipeLoadReport(survivors, allIssues);
    }

    private record ParsedRecipe(String fileName, RecipeDefinition recipe) {
    }
}
