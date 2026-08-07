package com.lodygames.rpgquest.quest.model;

import java.util.Map;

/**
 * Texte MiniMessage localisable. {@code default} est toujours présent ;
 * les autres clés sont des codes de langue ISO 639-1 (voir {@code config.yml}
 * → {@code locale}). La résolution effective selon la langue active du
 * joueur n'est pas encore câblée : cette étape ne fait que porter la donnée.
 */
public record LocalizedText(Map<String, String> byLocale) {

    public static final String DEFAULT_KEY = "default";

    public LocalizedText {
        byLocale = Map.copyOf(byLocale);
        if (!byLocale.containsKey(DEFAULT_KEY) || byLocale.get(DEFAULT_KEY).isBlank()) {
            throw new IllegalArgumentException("LocalizedText requiert une entrée « " + DEFAULT_KEY + "» non vide.");
        }
    }

    public static LocalizedText of(String defaultText) {
        return new LocalizedText(Map.of(DEFAULT_KEY, defaultText));
    }

    public String base() {
        return byLocale.get(DEFAULT_KEY);
    }

    public String forLocale(String localeCode) {
        return byLocale.getOrDefault(localeCode, base());
    }
}
