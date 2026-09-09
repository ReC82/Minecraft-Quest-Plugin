package com.lodygames.rpgquest.content.pack;

import java.util.List;

/**
 * Entrée « story » d'un content pack (issue #108) — miroir déclaratif de
 * {@link com.lodygames.rpgquest.story.model.StoryDefinition}. {@code questIds} conserve les
 * relations story → quêtes par identifiant métier stable ({@code namespace:clé}).
 */
public record StoryPackEntry(String id, PackText name, boolean secret, List<String> questIds) {

    public StoryPackEntry {
        questIds = List.copyOf(questIds);
    }
}
