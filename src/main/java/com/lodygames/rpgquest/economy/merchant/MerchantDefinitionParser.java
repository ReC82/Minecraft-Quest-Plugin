package com.lodygames.rpgquest.economy.merchant;

import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import com.lodygames.rpgquest.economy.merchant.model.MerchantOffer;
import com.lodygames.rpgquest.economy.merchant.model.OfferItemKind;
import com.lodygames.rpgquest.economy.merchant.model.TradeDirection;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Valide et construit une {@link MerchantDefinition} à partir d'un fichier
 * YAML déjà parsé. Même conception que {@code ItemDefinitionParser}/{@code
 * DialogueDefinitionParser} : purement structurel (un seul fichier à la
 * fois), ne dépend que de {@link ConfigurationSection} (testable sans
 * MockBukkit), accumule toutes les erreurs trouvées avant d'échouer.
 */
final class MerchantDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        NamespacedKey id = parseId(section, errors);
        String title = parseTitle(section, errors);
        List<MerchantOffer> offers = parseOffers(section, errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(message -> new MerchantLoadIssue(fileName, message)).toList());
        }

        try {
            return ParseResult.success(new MerchantDefinition(id, title, offers));
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new MerchantLoadIssue(fileName, e.getMessage())));
        }
    }

    private NamespacedKey parseId(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("id");
        if (raw == null || raw.isBlank()) {
            errors.add("« id » est obligatoire.");
            return null;
        }
        NamespacedKey id = toNamespacedKey(raw);
        if (id == null) {
            errors.add("« id » invalide : \"" + raw + "\".");
        }
        return id;
    }

    private String parseTitle(ConfigurationSection section, List<String> errors) {
        String title = section.getString("title");
        if (title == null || title.isBlank()) {
            errors.add("« title » est obligatoire.");
            return null;
        }
        return title;
    }

    private List<MerchantOffer> parseOffers(ConfigurationSection section, List<String> errors) {
        if (!section.isList("offers") || section.getMapList("offers").isEmpty()) {
            errors.add("« offers » est obligatoire et doit contenir au moins une offre.");
            return List.of();
        }
        List<MerchantOffer> offers = new ArrayList<>();
        List<Map<?, ?>> raw = section.getMapList("offers");
        for (int i = 0; i < raw.size(); i++) {
            MerchantOffer offer = parseOffer(toSection(raw.get(i)), "offers[" + i + "]", errors);
            if (offer != null) {
                offers.add(offer);
            }
        }
        return offers;
    }

    private MerchantOffer parseOffer(ConfigurationSection section, String context, List<String> errors) {
        TradeDirection direction = parseDirection(section, context, errors);
        boolean hasMaterial = section.isSet("material");
        boolean hasCustomItem = section.isSet("custom-item");

        Material material = null;
        NamespacedKey customItemId = null;
        OfferItemKind itemKind = null;

        if (hasMaterial == hasCustomItem) {
            errors.add(context + ": exactement un de « material » ou « custom-item » est requis.");
        } else if (hasMaterial) {
            itemKind = OfferItemKind.VANILLA;
            material = parseMaterial(section, context, errors);
        } else {
            itemKind = OfferItemKind.CUSTOM;
            customItemId = parseCustomItemRef(section, context, errors);
        }

        Integer quantity = parsePositiveInt(section, "quantity", context, errors);
        Long price = parseNonNegativeLong(section, "price", context, errors);
        String requiredPermission = parseOptionalPermission(section, context, errors);
        NamespacedKey requiredQuestId = parseOptionalQuestRef(section, context, errors);
        QuestState requiredQuestState = parseOptionalQuestState(section, requiredQuestId, context, errors);
        Integer requiredLevel = parseOptionalNonNegativeInt(section, "required-level", context, errors);

        if (direction == null || itemKind == null || quantity == null || price == null) {
            return null;
        }
        if ((itemKind == OfferItemKind.VANILLA && material == null)
                || (itemKind == OfferItemKind.CUSTOM && customItemId == null)) {
            return null;
        }

        try {
            return new MerchantOffer(direction, itemKind, material, customItemId, quantity, price,
                    requiredPermission, requiredQuestId, requiredQuestState, requiredLevel);
        } catch (IllegalArgumentException e) {
            errors.add(context + ": " + e.getMessage());
            return null;
        }
    }

    private TradeDirection parseDirection(ConfigurationSection section, String context, List<String> errors) {
        String raw = section.getString("direction");
        if (raw == null || raw.isBlank()) {
            errors.add(context + ": « direction » est obligatoire.");
            return null;
        }
        try {
            return TradeDirection.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + ": « direction » inconnue « " + raw + " » (valides : "
                    + List.of(TradeDirection.values()) + ").");
            return null;
        }
    }

    private Material parseMaterial(ConfigurationSection section, String context, List<String> errors) {
        String raw = section.getString("material");
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            errors.add(context + ": matériau inconnu « " + raw + " ».");
        }
        return material;
    }

    private NamespacedKey parseCustomItemRef(ConfigurationSection section, String context, List<String> errors) {
        String raw = section.getString("custom-item");
        NamespacedKey id = toNamespacedKey(raw);
        if (id == null) {
            errors.add(context + ": « custom-item » invalide : \"" + raw + "\".");
        }
        return id;
    }

    private Integer parsePositiveInt(ConfigurationSection section, String key, String context, List<String> errors) {
        if (!section.isSet(key)) {
            errors.add(context + ": « " + key + " » est obligatoire.");
            return null;
        }
        if (!section.isInt(key)) {
            errors.add(context + ": « " + key + " » doit être un entier, valeur trouvée : " + section.get(key));
            return null;
        }
        int value = section.getInt(key);
        if (value <= 0) {
            errors.add(context + ": « " + key + " » doit être strictement positif, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    private Long parseNonNegativeLong(ConfigurationSection section, String key, String context, List<String> errors) {
        if (!section.isSet(key)) {
            errors.add(context + ": « " + key + " » est obligatoire.");
            return null;
        }
        if (!(section.isInt(key) || section.isLong(key))) {
            errors.add(context + ": « " + key + " » doit être un entier, valeur trouvée : " + section.get(key));
            return null;
        }
        long value = section.getLong(key);
        if (value < 0) {
            errors.add(context + ": « " + key + " » ne peut pas être négatif, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    private String parseOptionalPermission(ConfigurationSection section, String context, List<String> errors) {
        if (!section.isSet("required-permission")) {
            return null;
        }
        String value = section.getString("required-permission");
        if (value == null || value.isBlank()) {
            errors.add(context + ": « required-permission » ne peut pas être vide si présent.");
            return null;
        }
        return value;
    }

    private NamespacedKey parseOptionalQuestRef(ConfigurationSection section, String context, List<String> errors) {
        if (!section.isSet("required-quest")) {
            return null;
        }
        String raw = section.getString("required-quest");
        NamespacedKey id = toNamespacedKey(raw);
        if (id == null) {
            errors.add(context + ": « required-quest » invalide : \"" + raw + "\".");
        }
        return id;
    }

    private QuestState parseOptionalQuestState(ConfigurationSection section, NamespacedKey requiredQuestId,
                                                String context, List<String> errors) {
        if (!section.isSet("required-quest-state")) {
            return null;
        }
        if (requiredQuestId == null) {
            errors.add(context + ": « required-quest-state » n'a de sens qu'avec « required-quest ».");
            return null;
        }
        String raw = section.getString("required-quest-state");
        try {
            return QuestState.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + ": état de quête inconnu « " + raw + " » (valides : " + List.of(QuestState.values()) + ").");
            return null;
        }
    }

    private Integer parseOptionalNonNegativeInt(ConfigurationSection section, String key, String context, List<String> errors) {
        if (!section.isSet(key)) {
            return null;
        }
        if (!section.isInt(key)) {
            errors.add(context + ": « " + key + " » doit être un entier, valeur trouvée : " + section.get(key));
            return null;
        }
        int value = section.getInt(key);
        if (value < 0) {
            errors.add(context + ": « " + key + " » ne peut pas être négatif, valeur trouvée : " + value);
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

    private ConfigurationSection toSection(Map<?, ?> map) {
        MemoryConfiguration memory = new MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }

    record ParseResult(MerchantDefinition merchant, List<MerchantLoadIssue> issues) {

        static ParseResult success(MerchantDefinition merchant) {
            return new ParseResult(merchant, List.of());
        }

        static ParseResult failure(List<MerchantLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return merchant != null;
        }
    }
}
