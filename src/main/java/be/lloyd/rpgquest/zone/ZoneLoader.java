package be.lloyd.rpgquest.zone;

import be.lloyd.rpgquest.zone.model.ZoneDefinition;
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
 * Charge toutes les zones d'un dossier. Même structure à deux phases que
 * {@code QuestLoader}/{@code ResourceNodeLoader} : chaque fichier validé
 * indépendamment, un fichier invalide n'empêche pas le chargement des
 * autres ; une seconde passe rejette les id dupliqués **et** les zones qui
 * se chevauchent (même monde) entre fichiers.
 */
public final class ZoneLoader {

    private final ZoneDefinitionParser parser = new ZoneDefinitionParser();

    public ZoneLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new ZoneLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new ZoneLoadReport(List.of(), List.of(new ZoneLoadIssue(
                    directory.toString(), "Impossible de lister le dossier de zones : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<ZoneLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new ZoneLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        ZoneLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<ZoneLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new ZoneLoadReport(report.loaded(), merged);
    }

    public ZoneLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedZone> parsedZones = new ArrayList<>();
        List<ZoneLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            ZoneDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsedZones.add(new ParsedZone(entry.getKey(), result.zone()));
            } else {
                issues.addAll(result.issues());
            }
        }

        return crossValidate(parsedZones, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private ZoneLoadReport crossValidate(List<ParsedZone> parsedZones, List<ZoneLoadIssue> issues) {
        List<ZoneLoadIssue> allIssues = new ArrayList<>(issues);

        Map<String, List<ParsedZone>> byId = parsedZones.stream()
                .collect(Collectors.groupingBy(pz -> pz.zone().id(), LinkedHashMap::new, Collectors.toList()));

        List<ParsedZone> afterDuplicateCheck = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedZone::fileName).collect(Collectors.joining(", "));
                for (ParsedZone duplicate : entry.getValue()) {
                    allIssues.add(new ZoneLoadIssue(duplicate.fileName(),
                            "id de zone dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                afterDuplicateCheck.add(entry.getValue().get(0));
            }
        }

        List<ZoneDefinition> survivors = new ArrayList<>();
        for (int i = 0; i < afterDuplicateCheck.size(); i++) {
            ParsedZone candidate = afterDuplicateCheck.get(i);
            ZoneDefinition overlapping = null;
            for (ZoneDefinition survivor : survivors) {
                if (candidate.zone().overlaps(survivor)) {
                    overlapping = survivor;
                    break;
                }
            }
            if (overlapping != null) {
                allIssues.add(new ZoneLoadIssue(candidate.fileName(),
                        "la zone « " + candidate.zone().id() + " » chevauche la zone « " + overlapping.id() + " »."));
            } else {
                survivors.add(candidate.zone());
            }
        }

        return new ZoneLoadReport(survivors, allIssues);
    }

    private record ParsedZone(String fileName, ZoneDefinition zone) {
    }
}
