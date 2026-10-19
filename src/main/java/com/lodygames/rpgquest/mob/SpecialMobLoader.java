package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.ability.MobAbilityRules;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
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
 * Charge toutes les variantes de mobs spéciaux d'un dossier. Même structure à
 * deux phases que {@link com.lodygames.rpgquest.resource.ResourceNodeLoader} :
 * chaque fichier est validé indépendamment ({@link SpecialMobDefinitionParser}),
 * un fichier invalide n'empêche pas le chargement des autres ; une seconde
 * passe rejette les id dupliqués entre fichiers.
 */
public final class SpecialMobLoader {

    /**
     * Issue #169 : fichiers de réglages rangés dans {@code mobs/} à côté des profils (réglages des boss
     * communautaires, tirage du Wild). Ce ne sont pas des profils : les lire comme tels les faisait
     * apparaître en « Profils rejetés » (id, entity-type, name, spawn-chance manquants) sur DEV.
     */
    public static final Set<String> RESERVED_FILE_NAMES = Set.of("community-bosses.yml", "spawn-settings.yml");

    private final SpecialMobDefinitionParser parser = new SpecialMobDefinitionParser();

    /** Vrai pour un fichier de réglages de {@code mobs/}, jamais lu ni écrit comme un profil. */
    public static boolean isReservedFileName(String fileName) {
        return fileName != null && RESERVED_FILE_NAMES.contains(fileName.toLowerCase(Locale.ROOT));
    }

    /** Scanne {@code directory} pour les fichiers {@code *.yml}/{@code *.yaml} (absent = 0 variante, pas une erreur). */
    public SpecialMobLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new SpecialMobLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new SpecialMobLoadReport(List.of(), List.of(new SpecialMobLoadIssue(
                    directory.toString(), "Impossible de lister le dossier de mobs spéciaux : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<SpecialMobLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new SpecialMobLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        SpecialMobLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<SpecialMobLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new SpecialMobLoadReport(report.loaded(), merged, report.warnings());
    }

    /** Entrée testable directement avec des sections déjà parsées, sans toucher au disque. */
    public SpecialMobLoadReport load(Map<String, ConfigurationSection> filesByName) {
        List<ParsedMob> parsedMobs = new ArrayList<>();
        List<SpecialMobLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            SpecialMobDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsedMobs.add(new ParsedMob(entry.getKey(), result.definition()));
            } else {
                issues.addAll(result.issues());
            }
        }

        return crossValidate(parsedMobs, issues);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"))
                && !isReservedFileName(name);
    }

    private SpecialMobLoadReport crossValidate(List<ParsedMob> parsedMobs, List<SpecialMobLoadIssue> issues) {
        List<SpecialMobLoadIssue> allIssues = new ArrayList<>(issues);

        Map<NamespacedKey, List<ParsedMob>> byId = parsedMobs.stream()
                .collect(Collectors.groupingBy(pm -> pm.definition().id(), LinkedHashMap::new, Collectors.toList()));

        List<SpecialMobDefinition> survivors = new ArrayList<>();
        List<SpecialMobLoadIssue> warnings = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedMob::fileName).collect(Collectors.joining(", "));
                for (ParsedMob duplicate : entry.getValue()) {
                    allIssues.add(new SpecialMobLoadIssue(duplicate.fileName(),
                            "id de mob spécial dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                ParsedMob survivor = entry.getValue().get(0);
                survivors.add(survivor.definition());
                MobAbilityRules.loadWarnings(survivor.definition())
                        .forEach(w -> warnings.add(new SpecialMobLoadIssue(survivor.fileName(), w)));
            }
        }

        return new SpecialMobLoadReport(survivors, allIssues, warnings);
    }

    private record ParsedMob(String fileName, SpecialMobDefinition definition) {
    }
}
