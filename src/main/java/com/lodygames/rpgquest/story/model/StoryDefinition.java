package com.lodygames.rpgquest.story.model;

import com.lodygames.rpgquest.quest.model.LocalizedText;
import java.util.List;
import java.util.regex.Pattern;
import org.bukkit.NamespacedKey;

/**
 * Un conteneur logique ordonné de quêtes existantes — la Story elle-même ne connaît rien du
 * fonctionnement interne du moteur de quête (aucune dépendance vers {@code quest.progress}) :
 * {@code questIds} n'est qu'une liste d'identifiants de référence, jamais résolue/validée contre
 * {@code YamlQuestEngine} au chargement (voir {@code StoryDefinitionParser}), pour que le
 * chargement des Stories reste indépendant de l'ordre de démarrage des deux moteurs.
 *
 * <p>{@code id} est stable et sert de clé de persistance (voir {@code database.StoryProgressRepository}) —
 * même contrainte de format que {@code travel.model.WorldPortalDefinition}/{@code zone.model.ZoneDefinition}.</p>
 */
public record StoryDefinition(String id, LocalizedText name, List<NamespacedKey> questIds, boolean secret) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]+");

    public StoryDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id est obligatoire.");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "id invalide : \"" + id + "\" (minuscules, chiffres, « _ » et « - » uniquement).");
        }
        if (name == null) {
            throw new IllegalArgumentException("name est obligatoire.");
        }
        questIds = List.copyOf(questIds);
        if (questIds.isEmpty()) {
            throw new IllegalArgumentException("la story « " + id + " » doit contenir au moins un id de quête.");
        }
    }
}
