package com.lodygames.rpgquest.encounter;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Valide et construit un {@link EncounterTemplate} à partir d'un fichier YAML déjà parsé (issue #13)
 * — même conception que {@code NpcDefinitionParser} : un fichier à la fois, toutes les erreurs
 * accumulées, aucune dépendance au serveur (testable sans MockBukkit).
 *
 * <p>Champs : {@code id} et {@code display_name} et {@code biomes} obligatoires ; {@code npc_id}
 * (défaut : {@code id}), {@code description}, {@code role}, {@code family}, {@code worlds},
 * {@code weight} (défaut 10), {@code max_sites} (défaut 0 = sans plafond propre),
 * {@code skin_url}, {@code enabled} (défaut {@code true}), {@code quest_catalogs} (issue #369)
 * facultatifs. Un biome sans espace de noms
 * est lu comme {@code minecraft:…}. L'existence réelle des biomes est vérifiée au chargement par le
 * moteur, pas ici : ce parseur ne connaît pas le serveur.</p>
 */
public final class EncounterTemplateParser {

    static final int DEFAULT_WEIGHT = 10;
    private static final Pattern BIOME = Pattern.compile("[a-z0-9._-]{1,64}:[a-z0-9._/-]{1,128}");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern WORLD = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        String id = parseId(section, "id", errors, true);
        String npcId = section.isSet("npc_id") ? parseId(section, "npc_id", errors, false) : id;
        String displayName = parseDisplayName(section, errors);
        String description = trimmedOrNull(section.getString("description"));
        String role = parseLabel(section, "role", errors);
        String family = parseLabel(section, "family", errors);
        Set<String> biomes = parseBiomes(section, errors);
        List<String> worlds = parseWorlds(section, errors);
        int weight = parseInt(section, "weight", DEFAULT_WEIGHT, 1, EncounterTemplate.MAX_WEIGHT, errors);
        int maxSites = parseInt(section, "max_sites", 0, 0, 100_000, errors);
        String skinUrl = parseSkin(section, errors);
        boolean enabled = parseEnabled(section, errors);
        List<String> questCatalogs = parseQuestCatalogs(section, errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new EncounterLoadIssue(fileName, m)).toList());
        }
        try {
            return ParseResult.success(new EncounterTemplate(id, displayName, description, npcId, role, family,
                    biomes, worlds, weight, maxSites, skinUrl, enabled, questCatalogs));
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new EncounterLoadIssue(fileName, e.getMessage())));
        }
    }

    /** Clé de biome normalisée ({@code forest} → {@code minecraft:forest}), ou {@code null} si invalide. */
    public static String normalizeBiome(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.indexOf(':') < 0) {
            key = "minecraft:" + key;
        }
        return BIOME.matcher(key).matches() ? key : null;
    }

    private String parseId(ConfigurationSection section, String field, List<String> errors, boolean required) {
        String raw = section.getString(field);
        if (raw == null || raw.isBlank()) {
            if (required) {
                errors.add("« " + field + " » est obligatoire.");
            } else {
                errors.add("« " + field + " » ne peut pas être vide.");
            }
            return null;
        }
        String trimmed = raw.trim();
        if (!EncounterTemplate.ID_PATTERN.matcher(trimmed).matches()) {
            errors.add("« " + field + " » invalide : « " + trimmed + " » (minuscules, chiffres, « . _ - » uniquement).");
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
        String trimmed = raw.trim();
        if (trimmed.length() > 64 || trimmed.indexOf('<') >= 0 || trimmed.indexOf('§') >= 0) {
            errors.add("« display_name » : texte simple de 64 caractères au plus, sans balise ni code couleur.");
            return null;
        }
        return trimmed;
    }

    private String parseLabel(ConfigurationSection section, String field, List<String> errors) {
        String raw = section.getString(field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (!LABEL.matcher(trimmed).matches()) {
            errors.add("« " + field + " » invalide : « " + raw + " » (minuscules, chiffres, « _ - », max 32).");
            return null;
        }
        return trimmed;
    }

    private Set<String> parseBiomes(ConfigurationSection section, List<String> errors) {
        if (!section.isList("biomes")) {
            errors.add("« biomes » est obligatoire : une liste de biomes (ex. minecraft:forest).");
            return Set.of();
        }
        Set<String> biomes = new LinkedHashSet<>();
        for (String raw : section.getStringList("biomes")) {
            String key = normalizeBiome(raw);
            if (key == null) {
                errors.add("« biomes » : clé de biome invalide « " + raw + " ».");
            } else {
                biomes.add(key);
            }
        }
        if (biomes.isEmpty() && errors.isEmpty()) {
            errors.add("« biomes » ne peut pas être vide.");
        }
        return biomes;
    }

    private List<String> parseWorlds(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("worlds")) {
            return List.of();
        }
        if (!section.isList("worlds")) {
            errors.add("« worlds » doit être une liste de noms de mondes.");
            return List.of();
        }
        List<String> worlds = new ArrayList<>();
        for (String raw : section.getStringList("worlds")) {
            String name = raw == null ? "" : raw.trim();
            if (!WORLD.matcher(name).matches()) {
                errors.add("« worlds » : nom de monde invalide « " + raw + " ».");
            } else if (!worlds.contains(name)) {
                worlds.add(name);
            }
        }
        return worlds;
    }

    /** Issue #369 — identifiants de catalogues ; leur existence est vérifiée à l'usage, pas ici. */
    private List<String> parseQuestCatalogs(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("quest_catalogs")) {
            return List.of();
        }
        if (!section.isList("quest_catalogs")) {
            errors.add("« quest_catalogs » doit être une liste d'identifiants de catalogues.");
            return List.of();
        }
        List<String> catalogs = new ArrayList<>();
        for (String raw : section.getStringList("quest_catalogs")) {
            String id = raw == null ? "" : raw.trim();
            if (!EncounterTemplate.ID_PATTERN.matcher(id).matches()) {
                errors.add("« quest_catalogs » : identifiant de catalogue invalide « " + raw + " ».");
            } else if (!catalogs.contains(id)) {
                catalogs.add(id);
            }
        }
        return catalogs;
    }

    private int parseInt(ConfigurationSection section, String field, int fallback, int min, int max,
                         List<String> errors) {
        if (!section.isSet(field)) {
            return fallback;
        }
        if (!section.isInt(field)) {
            errors.add("« " + field + " » doit être un entier, valeur trouvée : " + section.get(field));
            return fallback;
        }
        int value = section.getInt(field);
        if (value < min || value > max) {
            errors.add("« " + field + " » doit être compris entre " + min + " et " + max + ", valeur trouvée : " + value);
            return fallback;
        }
        return value;
    }

    private String parseSkin(ConfigurationSection section, List<String> errors) {
        String raw = trimmedOrNull(section.getString("skin_url"));
        if (raw == null) {
            return null;
        }
        if (!NpcIdentityService.isValidMineSkinUrl(raw)) {
            errors.add("« skin_url » : lien MineSkin valide attendu.");
            return null;
        }
        return raw;
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

    public record ParseResult(EncounterTemplate template, List<EncounterLoadIssue> issues) {

        public static ParseResult success(EncounterTemplate template) {
            return new ParseResult(template, List.of());
        }

        public static ParseResult failure(List<EncounterLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        public boolean isSuccess() {
            return template != null;
        }
    }
}
