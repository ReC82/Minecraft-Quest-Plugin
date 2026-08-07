package be.lloyd.rpgquest.travel;

import be.lloyd.rpgquest.quest.model.QuestState;
import be.lloyd.rpgquest.travel.model.PortalDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Valide et construit une {@link PortalDefinition} à partir d'un fichier
 * YAML déjà parsé. Même conception que {@code ZoneDefinitionParser} pour la
 * zone d'activation, et même conception que {@code MerchantDefinitionParser}
 * pour les conditions d'accès optionnelles (permission/quête/niveau).
 */
final class PortalDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        String id = section.getString("id");
        if (id == null || id.isBlank()) {
            errors.add("« id » est obligatoire.");
        }
        String world = section.getString("world");
        if (world == null || world.isBlank()) {
            errors.add("« world » est obligatoire.");
        }

        int[] min = parsePoint(section, "min", errors);
        int[] max = parsePoint(section, "max", errors);
        String destinationId = parseOptionalDestinationId(section, errors);
        Integer channelSeconds = parseNonNegativeInt(section, "channel-seconds", 3, errors);
        Integer cooldownSeconds = parseNonNegativeInt(section, "cooldown-seconds", 5, errors);
        String requiredPermission = parseOptionalPermission(section, errors);
        NamespacedKey requiredQuestId = parseOptionalQuestRef(section, errors);
        QuestState requiredQuestState = parseOptionalQuestState(section, requiredQuestId, errors);
        Integer requiredLevel = parseOptionalNonNegativeInt(section, "required-level", errors);
        Long cost = parseOptionalPositiveLong(section, "cost", errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new PortalLoadIssue(fileName, m)).toList());
        }

        try {
            PortalDefinition portal = new PortalDefinition(
                    id, world, min[0], min[1], min[2], max[0], max[1], max[2],
                    destinationId, channelSeconds, cooldownSeconds,
                    requiredPermission, requiredQuestId, requiredQuestState, requiredLevel, cost);
            return ParseResult.success(portal);
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new PortalLoadIssue(fileName, e.getMessage())));
        }
    }

    private int[] parsePoint(ConfigurationSection section, String key, List<String> errors) {
        ConfigurationSection point = section.getConfigurationSection(key);
        if (point == null) {
            errors.add("« " + key + " » est obligatoire (x, y, z).");
            return new int[]{0, 0, 0};
        }
        if (!point.isSet("x") || !point.isSet("y") || !point.isSet("z")) {
            errors.add("« " + key + " » doit contenir x, y et z.");
            return new int[]{0, 0, 0};
        }
        return new int[]{point.getInt("x"), point.getInt("y"), point.getInt("z")};
    }

    private String parseOptionalDestinationId(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("destination")) {
            return null;
        }
        String raw = section.getString("destination");
        if (raw == null || raw.isBlank()) {
            errors.add("« destination » ne peut pas être vide si présent.");
            return null;
        }
        return raw;
    }

    private Integer parseNonNegativeInt(ConfigurationSection section, String key, int defaultValue, List<String> errors) {
        if (!section.isSet(key)) {
            return defaultValue;
        }
        if (!section.isInt(key)) {
            errors.add("« " + key + " » doit être un entier.");
            return defaultValue;
        }
        int value = section.getInt(key);
        if (value < 0) {
            errors.add("« " + key + " » ne peut pas être négatif, valeur trouvée : " + value);
            return defaultValue;
        }
        return value;
    }

    private String parseOptionalPermission(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("required-permission")) {
            return null;
        }
        String value = section.getString("required-permission");
        if (value == null || value.isBlank()) {
            errors.add("« required-permission » ne peut pas être vide si présent.");
            return null;
        }
        return value;
    }

    private NamespacedKey parseOptionalQuestRef(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("required-quest")) {
            return null;
        }
        String raw = section.getString("required-quest");
        NamespacedKey id = toNamespacedKey(raw);
        if (id == null) {
            errors.add("« required-quest » invalide : \"" + raw + "\".");
        }
        return id;
    }

    private QuestState parseOptionalQuestState(ConfigurationSection section, NamespacedKey requiredQuestId, List<String> errors) {
        if (!section.isSet("required-quest-state")) {
            return null;
        }
        if (requiredQuestId == null) {
            errors.add("« required-quest-state » n'a de sens qu'avec « required-quest ».");
            return null;
        }
        String raw = section.getString("required-quest-state");
        try {
            return QuestState.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("état de quête inconnu « " + raw + " » (valides : " + List.of(QuestState.values()) + ").");
            return null;
        }
    }

    private Integer parseOptionalNonNegativeInt(ConfigurationSection section, String key, List<String> errors) {
        if (!section.isSet(key)) {
            return null;
        }
        if (!section.isInt(key)) {
            errors.add("« " + key + " » doit être un entier.");
            return null;
        }
        int value = section.getInt(key);
        if (value < 0) {
            errors.add("« " + key + " » ne peut pas être négatif, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    private Long parseOptionalPositiveLong(ConfigurationSection section, String key, List<String> errors) {
        if (!section.isSet(key)) {
            return null;
        }
        if (!(section.isInt(key) || section.isLong(key))) {
            errors.add("« " + key + " » doit être un entier.");
            return null;
        }
        long value = section.getLong(key);
        if (value <= 0) {
            errors.add("« " + key + " » doit être strictement positif si présent, valeur trouvée : " + value);
            return null;
        }
        return value;
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

    record ParseResult(PortalDefinition portal, List<PortalLoadIssue> issues) {

        static ParseResult success(PortalDefinition portal) {
            return new ParseResult(portal, List.of());
        }

        static ParseResult failure(List<PortalLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return portal != null;
        }
    }
}
