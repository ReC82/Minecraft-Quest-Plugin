package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.npc.model.NpcDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Valide et construit une {@link NpcDefinition} à partir d'un fichier YAML déjà parsé — même
 * conception que {@code QuestDefinitionParser} : purement structurel (un fichier), accumule toutes
 * les erreurs, ne dépend que de {@link ConfigurationSection} (testable sans MockBukkit).
 *
 * <p>Champs YAML : {@code id} (obligatoire), {@code display_name} (obligatoire), {@code description}
 * (facultatif), {@code dialogue} (facultatif — {@code namespace:key} ou clé simple, normalisée en
 * {@code rpgquest:key}), {@code role} (facultatif), {@code enabled} (facultatif, {@code true} par
 * défaut).</p>
 */
public final class NpcDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final Pattern KEY = Pattern.compile("[a-z0-9._-]{1,64}");
    private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9._-]{1,64}:[a-z0-9._/-]{1,128}");
    private static final Pattern ROLE = Pattern.compile("[a-z0-9_-]{1,32}");

    public ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        String id = parseId(section, errors);
        String displayName = parseDisplayName(section, errors);
        String description = trimmedOrNull(section.getString("description"));
        String dialogueId = parseDialogue(section, errors);
        String role = parseRole(section, errors);
        boolean enabled = parseEnabled(section, errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new NpcLoadIssue(fileName, m)).toList());
        }
        try {
            return ParseResult.success(new NpcDefinition(id, displayName, description, dialogueId, role, enabled));
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new NpcLoadIssue(fileName, e.getMessage())));
        }
    }

    private String parseId(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("id");
        if (raw == null || raw.isBlank()) {
            errors.add("« id » est obligatoire.");
            return null;
        }
        String trimmed = raw.trim();
        if (!NpcDefinition.isValidId(trimmed)) {
            errors.add("« id » invalide : « " + trimmed + " » (minuscules, chiffres, « . _ - » uniquement).");
            return null;
        }
        return trimmed;
    }

    private String parseDisplayName(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("display_name");
        if (raw == null || raw.isBlank()) {
            errors.add("« display_name » est obligatoire.");
            return null;
        }
        if (raw.length() > 128) {
            errors.add("« display_name » trop long (max 128).");
            return null;
        }
        return raw.trim();
    }

    private String parseDialogue(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("dialogue");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (NAMESPACED.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (KEY.matcher(trimmed).matches()) {
            return DEFAULT_NAMESPACE + ":" + trimmed;
        }
        errors.add("« dialogue » invalide : « " + raw + " » (attendu « namespace:clé » ou une clé simple).");
        return null;
    }

    private String parseRole(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("role");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (!ROLE.matcher(trimmed).matches()) {
            errors.add("« role » invalide : « " + raw + " » (minuscules, chiffres, « _ - », max 32).");
            return null;
        }
        return trimmed;
    }

    private boolean parseEnabled(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("enabled")) {
            return true;
        }
        if (!section.isBoolean("enabled")) {
            errors.add("« enabled » doit être un booléen (true/false), valeur trouvée : " + section.get("enabled"));
            return true;
        }
        return section.getBoolean("enabled");
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ParseResult(NpcDefinition definition, List<NpcLoadIssue> issues) {

        public static ParseResult success(NpcDefinition definition) {
            return new ParseResult(definition, List.of());
        }

        public static ParseResult failure(List<NpcLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        public boolean isSuccess() {
            return definition != null;
        }
    }
}
