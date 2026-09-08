package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Modèle éditable d'une <em>story</em> pour l'éditeur guidé #46 — conteneur ordonné de quêtes
 * existantes. Reprend fidèlement {@code story.model.StoryDefinition} : {@code id}, {@code name},
 * liste ordonnée de {@code questIds}, {@code secret}. La sérialisation ({@link StoryYaml}) produit
 * exactement le YAML attendu par {@code StoryDefinitionParser}.
 */
public final class StoryDraft {

    public String id = "";
    public String name = "";
    public boolean secret = false;
    public final List<String> questIds = new ArrayList<>();

    public static StoryDraft blank() {
        return new StoryDraft();
    }

    public StoryDraft copy() {
        StoryDraft d = new StoryDraft();
        d.id = id;
        d.name = name;
        d.secret = secret;
        d.questIds.addAll(questIds);
        return d;
    }
}
