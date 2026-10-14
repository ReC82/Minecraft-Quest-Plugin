package com.lodygames.rpgquest.encounter.catalog;

import com.lodygames.rpgquest.encounter.EncounterLoadIssue;
import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Valide et construit un {@link QuestCatalog} à partir d'un YAML déjà parsé (issue #369) — même
 * conception que {@code EncounterTemplateParser} : toutes les erreurs accumulées, aucune dépendance
 * au serveur. L'existence réelle des quêtes référencées n'est <strong>pas</strong> vérifiée ici :
 * une quête absente est signalée à l'usage et simplement jamais proposée.
 *
 * <p>Champs : {@code id}, {@code display_name} obligatoires ; {@code description},
 * {@code enabled} (défaut {@code true}), {@code avoid_recent} (défaut 1) et {@code entries}
 * (liste de {@code quest}, {@code weight} défaut 10, {@code enabled} défaut {@code true}). Une
 * quête sans espace de noms est lue comme {@code rpgquest:…}.</p>
 */
public final class QuestCatalogParser {

    static final int DEFAULT_WEIGHT = 10;
    static final int DEFAULT_AVOID_RECENT = 1;
    static final Pattern QUEST_ID = Pattern.compile("[a-z0-9._-]{1,64}:[a-z0-9._/-]{1,128}");

    public ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();
        String id = parseId(section, errors);
        String displayName = parseDisplayName(section, errors);
        String description = trimmedOrNull(section.getString("description"));
        if (description != null && description.length() > 256) {
            errors.add("« description » : 256 caractères au plus.");
        }
        boolean enabled = parseBoolean(section, "enabled", true, "", errors);
        int avoidRecent = parseInt(section, "avoid_recent", DEFAULT_AVOID_RECENT, 0, QuestCatalog.MAX_AVOID_RECENT,
                "", errors);
        List<QuestCatalog.Entry> entries = parseEntries(section, errors);
        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new EncounterLoadIssue(fileName, m)).toList());
        }
        try {
            return ParseResult.success(new QuestCatalog(id, displayName, description, enabled, avoidRecent, entries));
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new EncounterLoadIssue(fileName, e.getMessage())));
        }
    }

    /** Identifiant de quête normalisé ({@code spiders} → {@code rpgquest:spiders}), ou {@code null}. */
    public static String normalizeQuestId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.indexOf(':') < 0) {
            key = "rpgquest:" + key;
        }
        return QUEST_ID.matcher(key).matches() ? key : null;
    }

    private String parseId(ConfigurationSection section, List<String> errors) {
        String raw = trimmedOrNull(section.getString("id"));
        if (raw == null) {
            errors.add("« id » est obligatoire.");
            return null;
        }
        if (!EncounterTemplate.ID_PATTERN.matcher(raw).matches()) {
            errors.add("« id » invalide : « " + raw + " » (minuscules, chiffres, « . _ - » uniquement).");
            return null;
        }
        return raw;
    }

    private String parseDisplayName(ConfigurationSection section, List<String> errors) {
        String raw = trimmedOrNull(section.getString("display_name"));
        if (raw == null) {
            errors.add("« display_name » est obligatoire.");
            return null;
        }
        if (raw.length() > 64 || raw.indexOf('<') >= 0 || raw.indexOf('§') >= 0) {
            errors.add("« display_name » : texte simple de 64 caractères au plus, sans balise ni code couleur.");
            return null;
        }
        return raw;
    }

    private List<QuestCatalog.Entry> parseEntries(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("entries")) {
            return List.of();
        }
        if (!section.isList("entries")) {
            errors.add("« entries » doit être une liste de quêtes.");
            return List.of();
        }
        List<QuestCatalog.Entry> entries = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        List<Map<?, ?>> raw = section.getMapList("entries");
        for (int i = 0; i < raw.size(); i++) {
            String context = "entries[" + i + "] : ";
            MemoryConfiguration entry = new MemoryConfiguration();
            raw.get(i).forEach((k, v) -> entry.set(String.valueOf(k), v));
            String questId = normalizeQuestId(entry.getString("quest"));
            if (questId == null) {
                errors.add(context + "« quest » manquant ou invalide (« " + entry.getString("quest") + " »).");
                continue;
            }
            if (seen.contains(questId)) {
                errors.add(context + "la quête « " + questId + " » figure déjà dans ce catalogue.");
                continue;
            }
            seen.add(questId);
            int weight = parseInt(entry, "weight", DEFAULT_WEIGHT, 1, QuestCatalog.MAX_WEIGHT, context, errors);
            boolean enabled = parseBoolean(entry, "enabled", true, context, errors);
            entries.add(new QuestCatalog.Entry(questId, weight, enabled));
        }
        if (entries.size() > QuestCatalog.MAX_ENTRIES) {
            errors.add("au plus " + QuestCatalog.MAX_ENTRIES + " quêtes par catalogue.");
        }
        return entries;
    }

    private static int parseInt(ConfigurationSection section, String field, int fallback, int min, int max,
                                String context, List<String> errors) {
        if (!section.isSet(field)) {
            return fallback;
        }
        if (!section.isInt(field)) {
            errors.add(context + "« " + field + " » doit être un entier, valeur trouvée : " + section.get(field));
            return fallback;
        }
        int value = section.getInt(field);
        if (value < min || value > max) {
            errors.add(context + "« " + field + " » doit être compris entre " + min + " et " + max
                    + ", valeur trouvée : " + value);
            return fallback;
        }
        return value;
    }

    private static boolean parseBoolean(ConfigurationSection section, String field, boolean fallback, String context,
                                        List<String> errors) {
        if (!section.isSet(field)) {
            return fallback;
        }
        if (!section.isBoolean(field)) {
            errors.add(context + "« " + field + " » doit être un booléen (true/false), valeur trouvée : "
                    + section.get(field));
            return fallback;
        }
        return section.getBoolean(field);
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ParseResult(QuestCatalog catalog, List<EncounterLoadIssue> issues) {

        public static ParseResult success(QuestCatalog catalog) {
            return new ParseResult(catalog, List.of());
        }

        public static ParseResult failure(List<EncounterLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        public boolean isSuccess() {
            return catalog != null;
        }
    }
}
