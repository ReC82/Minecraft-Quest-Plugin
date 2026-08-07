package be.lloyd.rpgquest.economy.merchant;

import be.lloyd.rpgquest.economy.merchant.model.MerchantDefinition;
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
 * Charge toutes les définitions de marchands d'un dossier. Même structure à
 * deux phases que {@code ItemLoader} : chaque fichier est validé
 * indépendamment ({@link MerchantDefinitionParser}), un fichier invalide
 * n'empêche pas le chargement des autres ; une seconde passe rejette les id
 * dupliqués entre fichiers (pas de notion de prérequis entre marchands).
 */
public final class MerchantLoader {

    private final MerchantDefinitionParser parser = new MerchantDefinitionParser();

    /** Scanne {@code directory} pour les fichiers {@code *.yml}/{@code *.yaml} (absent = 0 marchand, pas une erreur). */
    public MerchantLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new MerchantLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new MerchantLoadReport(List.of(), List.of(new MerchantLoadIssue(
                    directory.toString(), "Impossible de lister le dossier de marchands : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<MerchantLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new MerchantLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        MerchantLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<MerchantLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new MerchantLoadReport(report.loaded(), merged);
    }

    /** Entrée testable directement avec des sections déjà parsées, sans toucher au disque. */
    public MerchantLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedMerchant> parsedMerchants = new ArrayList<>();
        List<MerchantLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            MerchantDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsedMerchants.add(new ParsedMerchant(entry.getKey(), result.merchant()));
            } else {
                issues.addAll(result.issues());
            }
        }

        return crossValidate(parsedMerchants, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private MerchantLoadReport crossValidate(List<ParsedMerchant> parsedMerchants, List<MerchantLoadIssue> issues) {
        List<MerchantLoadIssue> allIssues = new ArrayList<>(issues);

        Map<NamespacedKey, List<ParsedMerchant>> byId = parsedMerchants.stream()
                .collect(Collectors.groupingBy(pm -> pm.merchant().id(), LinkedHashMap::new, Collectors.toList()));

        List<MerchantDefinition> survivors = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedMerchant::fileName).collect(Collectors.joining(", "));
                for (ParsedMerchant duplicate : entry.getValue()) {
                    allIssues.add(new MerchantLoadIssue(duplicate.fileName(),
                            "id de marchand dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                survivors.add(entry.getValue().get(0).merchant());
            }
        }

        return new MerchantLoadReport(survivors, allIssues);
    }

    private record ParsedMerchant(String fileName, MerchantDefinition merchant) {
    }
}
