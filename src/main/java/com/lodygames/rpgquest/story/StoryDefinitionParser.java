package com.lodygames.rpgquest.story;

import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Valide et construit une {@link StoryDefinition} à partir d'un fichier YAML déjà parsé. Même
 * conception que {@code travel.PortalDefinitionParser} pour la validation en deux temps (erreurs
 * de champs d'abord, construction ensuite) et la résolution des id de quête (défaut au namespace
 * {@code rpgquest} si aucun « : » n'est présent, jamais résolue contre le moteur de quête ici).
 */
final class StoryDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        String id = section.getString("id");
        if (id == null || id.isBlank()) {
            errors.add("« id » est obligatoire.");
        }

        LocalizedText name = parseName(section, errors);
        List<NamespacedKey> questIds = parseQuestIds(section, errors);
        boolean secret = section.getBoolean("secret", false);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new StoryLoadIssue(fileName, m)).toList());
        }

        try {
            return ParseResult.success(new StoryDefinition(id, name, questIds, secret));
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new StoryLoadIssue(fileName, e.getMessage())));
        }
    }

    private LocalizedText parseName(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("name");
        if (raw == null || raw.isBlank()) {
            errors.add("« name » est obligatoire.");
            return null;
        }
        return LocalizedText.of(raw);
    }

    private List<NamespacedKey> parseQuestIds(ConfigurationSection section, List<String> errors) {
        if (!section.isList("quests")) {
            errors.add("« quests » est obligatoire (liste d'id de quête, au moins un).");
            return List.of();
        }
        List<String> raw = section.getStringList("quests");
        if (raw.isEmpty()) {
            errors.add("« quests » doit contenir au moins un id de quête.");
            return List.of();
        }
        List<NamespacedKey> questIds = new ArrayList<>();
        for (String entry : raw) {
            NamespacedKey key = toNamespacedKey(entry);
            if (key == null) {
                errors.add("id de quête invalide dans « quests » : \"" + entry + "\".");
                continue;
            }
            questIds.add(key);
        }
        return questIds;
    }

    private NamespacedKey toNamespacedKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return raw.contains(":") ? NamespacedKey.fromString(raw) : new NamespacedKey(DEFAULT_NAMESPACE, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record ParseResult(StoryDefinition story, List<StoryLoadIssue> issues) {

        static ParseResult success(StoryDefinition story) {
            return new ParseResult(story, List.of());
        }

        static ParseResult failure(List<StoryLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return story != null;
        }
    }
}
