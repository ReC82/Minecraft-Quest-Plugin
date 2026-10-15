package com.lodygames.rpgquest.webapi.news;

import java.util.Locale;
import java.util.Optional;

/** Catégorie d'une actualité (#56) : identifiant de fichier et d'URL, libellé affiché. */
public enum NewsCategory {
    NEWS("news", "Nouveauté"),
    ANNOUNCEMENT("announcement", "Annonce"),
    MAINTENANCE("maintenance", "Maintenance"),
    EVENT("event", "Événement"),
    RELEASE("release", "Notes de version"),
    STORY("story", "Récit du monde");

    private final String id;
    private final String label;

    NewsCategory(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static Optional<NewsCategory> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        for (NewsCategory category : values()) {
            if (category.id.equals(wanted)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
