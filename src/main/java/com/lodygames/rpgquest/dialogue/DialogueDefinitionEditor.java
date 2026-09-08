package com.lodygames.rpgquest.dialogue;

import com.lodygames.rpgquest.dialogue.model.CloseAction;
import com.lodygames.rpgquest.dialogue.model.DialogueAction;
import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Édition <strong>guidée et sûre</strong> d'un dialogue déjà chargé — phase 1 de l'éditeur
 * {@code /dialogues} (issue #82). Périmètre volontairement restreint : modifier le locuteur / le
 * texte d'un nœud, ajouter un nœud simple, ajouter / modifier / supprimer un <em>choix simple</em>
 * (sans condition, sans action autre que {@code CLOSE}). L'édition fine des actions et conditions
 * riches, le renommage de nœud et la suppression de nœud restent hors périmètre.
 *
 * <p>Discipline d'écriture, pour chaque mutation :</p>
 * <ol>
 *   <li>localiser le fichier du dialogue par son {@code id} (jamais un chemin fourni) ;</li>
 *   <li>le re-parser tel quel — s'il est <em>déjà</em> invalide, refuser (on ne réécrit pas un
 *       fichier cassé) ;</li>
 *   <li>appliquer la mutation sur le modèle métier ({@link DialogueDefinition}) ;</li>
 *   <li>sérialiser le dialogue complet ({@link DialogueDefinitionWriter}) puis le re-parser en
 *       mémoire et vérifier l'égalité sémantique — sinon on n'écrit rien ;</li>
 *   <li>écrire atomiquement (fichier temporaire + {@code move}) ;</li>
 *   <li>recharger tout le dossier ({@link DialogueLoader}) : si le fichier est rejeté ou le
 *       dialogue absent, <strong>restaurer l'octet près</strong> le contenu d'origine.</li>
 * </ol>
 *
 * <p>Purement IO + parsing (JUnit pur, sans MockBukkit), comme {@link DialogueDefinitionStore}.</p>
 */
public final class DialogueDefinitionEditor {

    /** Fragment d'id de nœud accepté à la <em>création</em> (les nœuds existants sont référencés tels quels). */
    public static final Pattern NEW_NODE_ID = Pattern.compile("[a-z0-9_][a-z0-9_-]{0,63}");
    static final String DEFAULT_NAMESPACE = "rpgquest";

    private final Path directory;
    private final List<String> allowedCommands;
    private final DialogueLoader loader;

    public DialogueDefinitionEditor(Path directory, List<String> allowedCommands) {
        this.directory = directory;
        this.allowedCommands = List.copyOf(allowedCommands);
        this.loader = new DialogueLoader(allowedCommands);
    }

    /**
     * @param code {@code UPDATED} en cas de succès ; sinon {@code NOT_FOUND} / {@code SOURCE_INVALID}
     *             / {@code INVALID} / {@code NODE_EXISTS} / {@code UNKNOWN_NODE} / {@code UNKNOWN_TARGET}
     *             / {@code UNSAFE_CHOICE} / {@code LAST_CHOICE} / {@code ROUNDTRIP} / {@code RELOAD_FAILED}
     *             / {@code ERROR}
     */
    public record Result(boolean ok, String code, String message, String file, List<String> effects,
                         List<String> issues) {
        static Result fail(String code, String message) {
            return new Result(false, code, message, null, List.of(), List.of());
        }

        static Result ok(String file, String message, List<String> effects) {
            return new Result(true, "UPDATED", message, file, List.copyOf(effects), List.of());
        }
    }

    // ---- Mutations publiques -----------------------------------------------------------------

    /** Modifie le {@code speaker} et le {@code text} d'un nœud existant (les choix sont conservés). */
    public Result updateNode(String dialogueId, String nodeId, String speaker, String text) {
        String s = clean(speaker);
        String t = clean(text);
        if (s == null || t == null) {
            return Result.fail("INVALID", "Locuteur et texte du nœud obligatoires.");
        }
        return mutate(dialogueId, "dialogue.node.update", def -> {
            DialogueNode current = def.nodes().get(nodeId);
            if (current == null) {
                return Edit.fail("UNKNOWN_NODE", "Nœud « " + nodeId + " » introuvable dans ce dialogue.");
            }
            DialogueNode updated = new DialogueNode(current.id(), s, LocalizedText.of(t), current.choices());
            return Edit.of(replaceNode(def, updated), null,
                    List.of("nœud « " + nodeId + " » : locuteur + texte mis à jour"));
        });
    }

    /**
     * Ajoute un nœud simple ({@code speaker} + {@code text}) avec un unique choix « fermer ». Le
     * nœud n'est relié à aucun choix existant : c'est un nœud orphelin assumé, à relier ensuite via
     * {@code dialogue.choice.add}.
     */
    public Result createNode(String dialogueId, String nodeId, String speaker, String text, String closeChoiceText) {
        String id = nodeId == null ? "" : nodeId.trim().toLowerCase(Locale.ROOT);
        String s = clean(speaker);
        String t = clean(text);
        String closeLabel = clean(closeChoiceText);
        if (!NEW_NODE_ID.matcher(id).matches()) {
            return Result.fail("INVALID", "Id de nœud invalide (minuscules, chiffres, « _ - », max 64).");
        }
        if (s == null || t == null) {
            return Result.fail("INVALID", "Locuteur et texte du nœud obligatoires.");
        }
        String label = closeLabel == null ? "Au revoir" : closeLabel;
        return mutate(dialogueId, "dialogue.node.create", def -> {
            if (def.nodes().containsKey(id)) {
                return Edit.fail("NODE_EXISTS", "Un nœud « " + id + " » existe déjà dans ce dialogue.");
            }
            DialogueNode node = new DialogueNode(id, s, LocalizedText.of(t),
                    List.of(new DialogueChoice(LocalizedText.of(label), List.of(), List.of(new CloseAction()), null)));
            Map<String, DialogueNode> nodes = new LinkedHashMap<>(def.nodes());
            nodes.put(id, node);
            return Edit.of(new DialogueDefinition(def.id(), def.startNodeId(), nodes), id,
                    List.of("nœud « " + id + " » créé (orphelin — à relier via un choix)"));
        });
    }

    /**
     * Ajoute un choix simple à un nœud existant : un texte + soit une redirection {@code next} vers
     * un nœud existant, soit une fermeture du dialogue ({@code close = true}).
     */
    public Result addChoice(String dialogueId, String nodeId, String choiceText, String nextNodeId, boolean close) {
        String text = clean(choiceText);
        String next = clean(nextNodeId);
        if (text == null) {
            return Result.fail("INVALID", "Texte du choix obligatoire.");
        }
        if (close == (next != null)) {
            return Result.fail("INVALID", "Un choix simple redirige vers un nœud OU ferme le dialogue — pas les deux, pas aucun.");
        }
        return mutate(dialogueId, "dialogue.choice.add", def -> {
            DialogueNode node = def.nodes().get(nodeId);
            if (node == null) {
                return Edit.fail("UNKNOWN_NODE", "Nœud « " + nodeId + " » introuvable dans ce dialogue.");
            }
            if (next != null && !def.nodes().containsKey(next)) {
                return Edit.fail("UNKNOWN_TARGET", "Nœud cible « " + next + " » introuvable dans ce dialogue.");
            }
            DialogueChoice choice = close
                    ? new DialogueChoice(LocalizedText.of(text), List.of(), List.of(new CloseAction()), null)
                    : new DialogueChoice(LocalizedText.of(text), List.of(), List.of(), next);
            List<DialogueChoice> choices = new ArrayList<>(node.choices());
            choices.add(choice);
            return Edit.of(replaceNode(def, new DialogueNode(node.id(), node.speaker(), node.text(), choices)), null,
                    List.of("choix ajouté au nœud « " + nodeId + " » ("
                            + (close ? "ferme le dialogue" : "→ " + next) + ")"));
        });
    }

    /** Modifie le texte et la cible ({@code next} / fermeture) d'un choix simple existant. */
    public Result updateChoice(String dialogueId, String nodeId, int choiceIndex, String choiceText,
                               String nextNodeId, boolean close) {
        String text = clean(choiceText);
        String next = clean(nextNodeId);
        if (text == null) {
            return Result.fail("INVALID", "Texte du choix obligatoire.");
        }
        if (close == (next != null)) {
            return Result.fail("INVALID", "Un choix simple redirige vers un nœud OU ferme le dialogue — pas les deux, pas aucun.");
        }
        return mutate(dialogueId, "dialogue.choice.update", def -> {
            DialogueNode node = def.nodes().get(nodeId);
            if (node == null) {
                return Edit.fail("UNKNOWN_NODE", "Nœud « " + nodeId + " » introuvable dans ce dialogue.");
            }
            if (choiceIndex < 0 || choiceIndex >= node.choices().size()) {
                return Edit.fail("UNKNOWN_CHOICE", "Choix #" + choiceIndex + " introuvable dans le nœud « " + nodeId + " ».");
            }
            if (!isSimple(node.choices().get(choiceIndex))) {
                return Edit.fail("UNSAFE_CHOICE", "Ce choix porte des conditions ou des actions avancées — "
                        + "édition réservée à une phase ultérieure de l'éditeur.");
            }
            if (next != null && !def.nodes().containsKey(next)) {
                return Edit.fail("UNKNOWN_TARGET", "Nœud cible « " + next + " » introuvable dans ce dialogue.");
            }
            DialogueChoice replacement = close
                    ? new DialogueChoice(LocalizedText.of(text), List.of(), List.of(new CloseAction()), null)
                    : new DialogueChoice(LocalizedText.of(text), List.of(), List.of(), next);
            List<DialogueChoice> choices = new ArrayList<>(node.choices());
            choices.set(choiceIndex, replacement);
            return Edit.of(replaceNode(def, new DialogueNode(node.id(), node.speaker(), node.text(), choices)), null,
                    List.of("choix #" + choiceIndex + " du nœud « " + nodeId + " » mis à jour"));
        });
    }

    /** Supprime un choix simple si le nœud garde au moins un choix (invariant du modèle). */
    public Result deleteChoice(String dialogueId, String nodeId, int choiceIndex) {
        return mutate(dialogueId, "dialogue.choice.delete", def -> {
            DialogueNode node = def.nodes().get(nodeId);
            if (node == null) {
                return Edit.fail("UNKNOWN_NODE", "Nœud « " + nodeId + " » introuvable dans ce dialogue.");
            }
            if (choiceIndex < 0 || choiceIndex >= node.choices().size()) {
                return Edit.fail("UNKNOWN_CHOICE", "Choix #" + choiceIndex + " introuvable dans le nœud « " + nodeId + " ».");
            }
            if (node.choices().size() <= 1) {
                return Edit.fail("LAST_CHOICE", "Impossible de supprimer le dernier choix d'un nœud "
                        + "(un nœud doit garder au moins un choix).");
            }
            if (!isSimple(node.choices().get(choiceIndex))) {
                return Edit.fail("UNSAFE_CHOICE", "Ce choix porte des conditions ou des actions avancées — "
                        + "suppression réservée à une phase ultérieure de l'éditeur.");
            }
            List<DialogueChoice> choices = new ArrayList<>(node.choices());
            choices.remove(choiceIndex);
            return Edit.of(replaceNode(def, new DialogueNode(node.id(), node.speaker(), node.text(), choices)), null,
                    List.of("choix #" + choiceIndex + " retiré du nœud « " + nodeId + " »"));
        });
    }

    // ---- Cœur : localise, applique, garde-fous, écrit, recharge ------------------------------

    private interface EditFn {
        Edit apply(DialogueDefinition current);
    }

    private record Edit(DialogueDefinition next, String highlightNodeId, List<String> effects, String failCode,
                        String failMessage) {
        static Edit of(DialogueDefinition next, String highlightNodeId, List<String> effects) {
            return new Edit(next, highlightNodeId, effects, null, null);
        }

        static Edit fail(String code, String message) {
            return new Edit(null, null, List.of(), code, message);
        }
    }

    private Result mutate(String dialogueId, String actionLabel, EditFn fn) {
        NamespacedKey wanted = normalizeId(dialogueId);
        if (wanted == null) {
            return Result.fail("INVALID", "Identifiant de dialogue invalide : « " + dialogueId + " ».");
        }

        Located located;
        try {
            located = locate(wanted);
        } catch (IOException e) {
            return Result.fail("ERROR", "Lecture du dossier de dialogues impossible : " + e.getMessage());
        }
        if (located == null) {
            return Result.fail("NOT_FOUND", "Aucun fichier de dialogue « " + wanted + " » dans le dossier.");
        }

        DialogueDefinitionParser parser = new DialogueDefinitionParser(java.util.Set.copyOf(allowedCommands));
        DialogueDefinitionParser.ParseResult source = parser.parse(located.fileName, located.yaml);
        if (!source.isSuccess()) {
            return new Result(false, "SOURCE_INVALID",
                    "Le fichier « " + located.fileName + " » est déjà invalide — édition refusée (le corriger d'abord).",
                    located.fileName, List.of(),
                    source.issues().stream().map(DialogueLoadIssue::message).collect(Collectors.toList()));
        }

        Edit edit = fn.apply(source.dialogue());
        if (edit.failCode() != null) {
            return Result.fail(edit.failCode(), edit.failMessage());
        }

        List<String> nodeOrder = readNodeOrder(located.yaml);
        String rendered = DialogueDefinitionWriter.render(edit.next(), nodeOrder);

        // Garde-fou round-trip : re-parser le texte rendu et exiger l'égalité sémantique.
        DialogueDefinition reparsed = parseInMemory(parser, located.fileName, rendered);
        if (reparsed == null || !reparsed.equals(edit.next())) {
            return Result.fail("ROUNDTRIP", "Le rendu du dialogue ne se re-parse pas à l'identique — "
                    + "écriture annulée (aucun fichier modifié).");
        }

        byte[] original = located.originalBytes;
        try {
            atomicWrite(located.path, rendered);
        } catch (IOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }

        DialogueLoadReport report = loader.loadDirectory(directory);
        List<String> fileIssues = report.issues().stream()
                .filter(i -> located.fileName.equals(i.file()))
                .map(DialogueLoadIssue::message)
                .collect(Collectors.toList());
        boolean present = report.loaded().stream().anyMatch(d -> d.id().equals(wanted));
        if (!fileIssues.isEmpty() || !present) {
            restore(located.path, original);
            return new Result(false, "RELOAD_FAILED",
                    "Le fichier écrit ne se recharge pas proprement — modification annulée, contenu d'origine restauré.",
                    located.fileName, List.of(),
                    fileIssues.isEmpty() ? List.of("dialogue « " + wanted + " » absent après rechargement") : fileIssues);
        }

        List<String> effects = new ArrayList<>();
        effects.add("dialogues/" + located.fileName);
        effects.addAll(edit.effects());
        if (edit.highlightNodeId() != null) {
            effects.add("nœud: " + edit.highlightNodeId());
        }
        return Result.ok(located.fileName, actionLabel + " appliqué sur « " + wanted + " ».", effects);
    }

    private record Located(Path path, String fileName, YamlConfiguration yaml, byte[] originalBytes) {
    }

    private Located locate(NamespacedKey wanted) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYaml)) {
            stream.forEach(files::add);
        }
        files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        for (Path file : files) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file.toFile());
            } catch (IOException | InvalidConfigurationException e) {
                continue; // fichier illisible : ne peut pas être notre cible.
            }
            NamespacedKey id = normalizeId(yaml.getString("id"));
            if (wanted.equals(id)) {
                return new Located(file, file.getFileName().toString(), yaml, Files.readAllBytes(file));
            }
        }
        return null;
    }

    private boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private static List<String> readNodeOrder(ConfigurationSection yaml) {
        ConfigurationSection nodes = yaml.getConfigurationSection("nodes");
        return nodes == null ? List.of() : List.copyOf(nodes.getKeys(false));
    }

    private static DialogueDefinition parseInMemory(DialogueDefinitionParser parser, String fileName, String yamlText) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(yamlText);
        } catch (InvalidConfigurationException e) {
            return null;
        }
        DialogueDefinitionParser.ParseResult result = parser.parse(fileName, yaml);
        return result.isSuccess() ? result.dialogue() : null;
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(directory);
        Path tmp = directory.resolve("." + target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void restore(Path target, byte[] original) {
        try {
            Path tmp = directory.resolve("." + target.getFileName() + ".restore");
            Files.write(tmp, original);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Restauration du dialogue d'origine impossible : " + target, e);
        }
    }

    // ---- Petits utilitaires ----------------------------------------------------------------

    private static boolean isSimple(DialogueChoice choice) {
        if (!choice.conditions().isEmpty()) {
            return false;
        }
        for (DialogueAction action : choice.actions()) {
            if (!(action instanceof CloseAction)) {
                return false;
            }
        }
        return true;
    }

    private static DialogueDefinition replaceNode(DialogueDefinition def, DialogueNode node) {
        Map<String, DialogueNode> nodes = new LinkedHashMap<>(def.nodes());
        nodes.put(node.id(), node);
        return new DialogueDefinition(def.id(), def.startNodeId(), nodes);
    }

    private static NamespacedKey normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        try {
            return value.contains(":") ? NamespacedKey.fromString(value) : new NamespacedKey(DEFAULT_NAMESPACE, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
