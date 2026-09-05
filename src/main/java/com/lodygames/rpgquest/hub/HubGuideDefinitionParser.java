package com.lodygames.rpgquest.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Valide et construit une {@link HubGuideDefinition} à partir d'un fichier YAML déjà parsé. Même
 * conception que {@code ZoneDefinitionParser}/{@code DialogueDefinitionParser} : purement structurel,
 * ne dépend que de {@link ConfigurationSection}, accumule toutes les erreurs avant d'échouer.
 */
final class HubGuideDefinitionParser {

    private static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final Pattern HUB_ID = Pattern.compile("[a-z0-9._-]+");

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        String hubId = section.getString("hub-id");
        if (hubId == null || hubId.isBlank()) {
            errors.add("« hub-id » est obligatoire.");
        } else if (!HUB_ID.matcher(hubId).matches()) {
            errors.add("« hub-id » invalide « " + hubId + " » (minuscules, chiffres, . _ - uniquement).");
        }

        NamespacedKey guideDialogueId = null;
        String rawDialogue = section.getString("guide-dialogue");
        if (rawDialogue == null || rawDialogue.isBlank()) {
            errors.add("« guide-dialogue » est obligatoire.");
        } else {
            guideDialogueId = toNamespacedKey(rawDialogue);
            if (guideDialogueId == null) {
                errors.add("« guide-dialogue » invalide : \"" + rawDialogue + "\".");
            }
        }

        List<String> worlds = new ArrayList<>();
        for (String world : section.getStringList("worlds")) {
            if (world != null && !world.isBlank()) {
                worlds.add(world);
            }
        }

        String helpNode = section.getString("help-node");
        String welcome = section.getString("welcome");
        String specialty = section.getString("specialty");
        List<HubGuideReferral> referrals = parseReferrals(section, errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new HubGuideLoadIssue(fileName, m)).toList());
        }
        try {
            return ParseResult.success(new HubGuideDefinition(
                    hubId, worlds, guideDialogueId, helpNode, welcome, specialty, referrals));
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new HubGuideLoadIssue(fileName, e.getMessage())));
        }
    }

    private List<HubGuideReferral> parseReferrals(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("referrals")) {
            return List.of();
        }
        List<HubGuideReferral> referrals = new ArrayList<>();
        List<Map<?, ?>> raw = section.getMapList("referrals");
        for (int i = 0; i < raw.size(); i++) {
            ConfigurationSection entry = toSection(raw.get(i));
            String role = entry.getString("role");
            String npc = entry.getString("npc");
            String note = entry.getString("note");
            if (role == null || role.isBlank() || npc == null || npc.isBlank()) {
                errors.add("referrals[" + i + "] : « role » et « npc » sont obligatoires.");
                continue;
            }
            referrals.add(new HubGuideReferral(role, npc, note));
        }
        return referrals;
    }

    private NamespacedKey toNamespacedKey(String raw) {
        try {
            return raw.contains(":") ? NamespacedKey.fromString(raw) : new NamespacedKey(DEFAULT_NAMESPACE, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ConfigurationSection toSection(Map<?, ?> map) {
        MemoryConfiguration memory = new MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }

    record ParseResult(HubGuideDefinition definition, List<HubGuideLoadIssue> issues) {

        static ParseResult success(HubGuideDefinition definition) {
            return new ParseResult(definition, List.of());
        }

        static ParseResult failure(List<HubGuideLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return definition != null;
        }
    }
}
