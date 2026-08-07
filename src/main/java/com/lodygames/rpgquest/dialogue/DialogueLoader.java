package com.lodygames.rpgquest.dialogue;

import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.OpenDialogueAction;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
 * Charge toutes les définitions de dialogues d'un dossier. Chaque fichier
 * est validé indépendamment ({@link DialogueDefinitionParser}) : un fichier
 * invalide n'empêche pas le chargement des autres. Une seconde passe
 * rejette les id dupliqués entre fichiers, les références {@code
 * OPEN_DIALOGUE} vers un dialogue inexistant, et détecte les boucles entre
 * dialogues (A ouvre B qui ouvre A) — les redirections {@code next} entre
 * nœuds d'un même dialogue, elles, peuvent boucler librement (menu
 * « hub » classique), seul le graphe {@code OPEN_DIALOGUE} entre dialogues
 * est contrôlé.
 */
public final class DialogueLoader {

    private final Set<String> allowedCommands;

    public DialogueLoader(List<String> allowedCommands) {
        this.allowedCommands = Set.copyOf(allowedCommands);
    }

    public DialogueLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new DialogueLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new DialogueLoadReport(List.of(), List.of(
                    new DialogueLoadIssue(directory.toString(), "Impossible de lister le dossier de dialogues : " + e.getMessage())));
        }
        files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));

        Map<String, ConfigurationSection> filesByName = new LinkedHashMap<>();
        List<DialogueLoadIssue> ioIssues = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                filesByName.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                ioIssues.add(new DialogueLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
            }
        }

        DialogueLoadReport report = load(filesByName);
        if (ioIssues.isEmpty()) {
            return report;
        }
        List<DialogueLoadIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(report.issues());
        return new DialogueLoadReport(report.loaded(), merged);
    }

    /** Entrée testable directement avec des sections déjà parsées, sans toucher au disque. */
    public DialogueLoadReport load(Map<String, ConfigurationSection> filesByName) {
        DialogueDefinitionParser parser = new DialogueDefinitionParser(allowedCommands);
        List<ParsedDialogue> parsed = new ArrayList<>();
        List<DialogueLoadIssue> issues = new ArrayList<>();

        for (var entry : filesByName.entrySet()) {
            DialogueDefinitionParser.ParseResult result = parser.parse(entry.getKey(), entry.getValue());
            if (result.isSuccess()) {
                parsed.add(new ParsedDialogue(entry.getKey(), result.dialogue()));
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

    private DialogueLoadReport crossValidate(List<ParsedDialogue> parsedDialogues, List<DialogueLoadIssue> issues) {
        List<DialogueLoadIssue> allIssues = new ArrayList<>(issues);

        Map<NamespacedKey, List<ParsedDialogue>> byId = parsedDialogues.stream()
                .collect(Collectors.groupingBy(pd -> pd.dialogue().id(), LinkedHashMap::new, Collectors.toList()));

        List<ParsedDialogue> survivors = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String files = entry.getValue().stream().map(ParsedDialogue::fileName).collect(Collectors.joining(", "));
                for (ParsedDialogue duplicate : entry.getValue()) {
                    allIssues.add(new DialogueLoadIssue(duplicate.fileName(),
                            "id de dialogue dupliqué « " + entry.getKey() + " » (aussi présent dans : " + files + ")."));
                }
            } else {
                survivors.add(entry.getValue().get(0));
            }
        }

        survivors = rejectMissingOpenDialogueReferences(survivors, allIssues);
        survivors = rejectCyclicOpenDialogueReferences(survivors, allIssues);

        List<DialogueDefinition> loaded = survivors.stream().map(ParsedDialogue::dialogue).toList();
        return new DialogueLoadReport(loaded, allIssues);
    }

    private List<ParsedDialogue> rejectMissingOpenDialogueReferences(List<ParsedDialogue> survivors, List<DialogueLoadIssue> allIssues) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<NamespacedKey> survivingIds = survivors.stream().map(pd -> pd.dialogue().id()).collect(Collectors.toSet());
            List<ParsedDialogue> stillValid = new ArrayList<>();
            for (ParsedDialogue pd : survivors) {
                List<NamespacedKey> missing = openDialogueTargets(pd.dialogue()).stream()
                        .filter(target -> !survivingIds.contains(target))
                        .toList();
                if (missing.isEmpty()) {
                    stillValid.add(pd);
                } else {
                    changed = true;
                    allIssues.add(new DialogueLoadIssue(pd.fileName(),
                            "OPEN_DIALOGUE référence un dialogue introuvable pour « " + pd.dialogue().id() + " » : " + missing
                                    + " (dialogue absent ou lui-même rejeté)."));
                }
            }
            survivors = stillValid;
        }
        return survivors;
    }

    /** Détection de cycles (couleurs blanc/gris/noir) sur le graphe OPEN_DIALOGUE entre dialogues. */
    private List<ParsedDialogue> rejectCyclicOpenDialogueReferences(List<ParsedDialogue> survivors, List<DialogueLoadIssue> allIssues) {
        Map<NamespacedKey, ParsedDialogue> byId = new LinkedHashMap<>();
        for (ParsedDialogue pd : survivors) {
            byId.put(pd.dialogue().id(), pd);
        }

        Set<NamespacedKey> visiting = new HashSet<>();
        Set<NamespacedKey> done = new HashSet<>();
        Set<NamespacedKey> onCycle = new HashSet<>();

        for (NamespacedKey id : byId.keySet()) {
            if (!done.contains(id)) {
                detectCycle(id, byId, visiting, done, onCycle);
            }
        }

        if (onCycle.isEmpty()) {
            return survivors;
        }

        List<ParsedDialogue> stillValid = new ArrayList<>();
        for (ParsedDialogue pd : survivors) {
            if (onCycle.contains(pd.dialogue().id())) {
                allIssues.add(new DialogueLoadIssue(pd.fileName(),
                        "boucle de dialogues détectée impliquant « " + pd.dialogue().id() + " » (via OPEN_DIALOGUE)."));
            } else {
                stillValid.add(pd);
            }
        }
        return stillValid;
    }

    private void detectCycle(NamespacedKey id, Map<NamespacedKey, ParsedDialogue> byId,
                              Set<NamespacedKey> visiting, Set<NamespacedKey> done, Set<NamespacedKey> onCycle) {
        ParsedDialogue pd = byId.get(id);
        if (pd == null) {
            return;
        }
        visiting.add(id);
        for (NamespacedKey target : openDialogueTargets(pd.dialogue())) {
            if (!byId.containsKey(target)) {
                continue;
            }
            if (visiting.contains(target)) {
                onCycle.add(id);
                onCycle.add(target);
            } else if (!done.contains(target)) {
                detectCycle(target, byId, visiting, done, onCycle);
                if (onCycle.contains(target)) {
                    onCycle.add(id);
                }
            }
        }
        visiting.remove(id);
        done.add(id);
    }

    private List<NamespacedKey> openDialogueTargets(DialogueDefinition dialogue) {
        List<NamespacedKey> targets = new ArrayList<>();
        for (var node : dialogue.nodes().values()) {
            for (var choice : node.choices()) {
                for (var action : choice.actions()) {
                    if (action instanceof OpenDialogueAction open) {
                        targets.add(open.dialogueId());
                    }
                }
            }
        }
        return targets;
    }

    private record ParsedDialogue(String fileName, DialogueDefinition dialogue) {
    }
}
