package be.lloyd.rpgquest.travel;

import be.lloyd.rpgquest.travel.model.PortalDefinition;
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
 * Charge tous les portails d'un dossier. Même structure à deux phases que
 * {@code ZoneLoader} : chaque fichier validé indépendamment, puis rejet des
 * id dupliqués **et** des zones d'activation qui se chevauchent (même
 * monde) entre fichiers — deux portails activables au même endroit
 * rendraient ambigu « lequel s'active », donc rejetés comme les zones
 * protégées qui se chevauchent.
 */
public final class PortalLoader {

    private final PortalDefinitionParser parser = new PortalDefinitionParser();

    public PortalLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new PortalLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new PortalLoadReport(List.of(), List.of(new PortalLoadIssue(
                    directory.toString(), "Impossible de lister le dossier de portails : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<PortalLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new PortalLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        PortalLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<PortalLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new PortalLoadReport(report.loaded(), merged);
    }

    public PortalLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedPortal> parsed = new ArrayList<>();
        List<PortalLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            PortalDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsed.add(new ParsedPortal(entry.getKey(), result.portal()));
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

    private PortalLoadReport crossValidate(List<ParsedPortal> parsed, List<PortalLoadIssue> issues) {
        List<PortalLoadIssue> allIssues = new ArrayList<>(issues);

        Map<String, List<ParsedPortal>> byId = parsed.stream()
                .collect(Collectors.groupingBy(pp -> pp.portal().id(), LinkedHashMap::new, Collectors.toList()));

        List<ParsedPortal> afterDuplicateCheck = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedPortal::fileName).collect(Collectors.joining(", "));
                for (ParsedPortal duplicate : entry.getValue()) {
                    allIssues.add(new PortalLoadIssue(duplicate.fileName(),
                            "id de portail dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                afterDuplicateCheck.add(entry.getValue().get(0));
            }
        }

        List<PortalDefinition> survivors = new ArrayList<>();
        for (ParsedPortal candidate : afterDuplicateCheck) {
            PortalDefinition overlapping = null;
            for (PortalDefinition survivor : survivors) {
                if (candidate.portal().overlaps(survivor)) {
                    overlapping = survivor;
                    break;
                }
            }
            if (overlapping != null) {
                allIssues.add(new PortalLoadIssue(candidate.fileName(),
                        "le portail « " + candidate.portal().id() + " » chevauche le portail « " + overlapping.id() + " »."));
            } else {
                survivors.add(candidate.portal());
            }
        }

        return new PortalLoadReport(survivors, allIssues);
    }

    private record ParsedPortal(String fileName, PortalDefinition portal) {
    }
}
