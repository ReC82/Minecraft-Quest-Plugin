package com.lodygames.rpgquest.content.pack;

import java.util.Map;

/**
 * Texte localisable d'un content pack (issue #108) — miroir déclaratif de
 * {@link com.lodygames.rpgquest.quest.model.LocalizedText}, <strong>sans dépendance Bukkit</strong>.
 *
 * <p>{@code default} est toujours présent. L'ordre d'itération de {@link #byLocale()} n'est pas
 * significatif : c'est {@code ContentPackSerializer} qui impose l'ordre canonique en sortie
 * ({@code default} d'abord, puis les autres locales triées), pour un rendu déterministe quelle que
 * soit l'implémentation de {@code Map}.</p>
 */
public record PackText(Map<String, String> byLocale) {

    public static final String DEFAULT_KEY = "default";

    public PackText {
        if (byLocale == null || byLocale.isEmpty()) {
            throw new IllegalArgumentException("PackText.byLocale est obligatoire.");
        }
        String base = byLocale.get(DEFAULT_KEY);
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("PackText requiert une entrée « " + DEFAULT_KEY + " » non vide.");
        }
        byLocale = Map.copyOf(byLocale);
    }

    public static PackText of(String defaultText) {
        return new PackText(Map.of(DEFAULT_KEY, defaultText));
    }

    public boolean isPlain() {
        return byLocale.size() == 1 && byLocale.containsKey(DEFAULT_KEY);
    }

    public String base() {
        return byLocale.get(DEFAULT_KEY);
    }
}
