package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.MobAbilityType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.entity.EntityType;

/**
 * Issue #542 — la fiche d'un type de capacité : ce qu'il fait, ses paramètres, ses garanties. Lue par
 * le parseur, le rendu YAML, le schéma #214 et (#543) le catalogue PlugAdmin ; aucune de ces vues ne
 * code plus une capacité en dur.
 *
 * @param type          identifiant stable (clé {@code type:} du YAML)
 * @param label         nom affiché en français
 * @param summary       ce que voit le joueur, une ligne
 * @param category      famille, pour le classement du catalogue
 * @param status        {@link Status#IN_DEVELOPMENT} : réservée aux profils de test (décision Q8)
 * @param kind          {@link Kind#PASSIVE} se cumulent ; {@link Kind#ACTION} : une seule à la fois par mob (Q5)
 * @param triggers      événements auxquels le moteur la présente
 * @param repeatable    peut figurer plusieurs fois dans un profil (aucune aujourd'hui, décision Q3)
 * @param effectiveOn   créatures sur lesquelles elle a un effet ; vide = toutes (sinon simple avertissement)
 * @param params        paramètres propres puis champs communs, dans l'ordre d'écriture
 * @param presets       valeurs de départ nommées (Doux/Normal/Brutal), <strong>copiées</strong> dans l'instance (Q6)
 * @param maxEntitiesPerActivation entités au plus créées par un déclenchement (0 = aucune)
 * @param worldMutation modification du terrain : {@link WorldMutation#NONE} pour toutes aujourd'hui
 */
public record AbilityDescriptor(MobAbilityType type, String label, String summary, Category category, Status status,
                                Kind kind, Set<AbilityTrigger> triggers, boolean repeatable,
                                Set<EntityType> effectiveOn, List<AbilityParam> params,
                                Map<String, Map<String, Object>> presets, int maxEntitiesPerActivation,
                                WorldMutation worldMutation) {

    public enum Category { EXPLOSION, REACTION, ENHANCEMENT, SUMMON }

    public enum Status { AVAILABLE, IN_DEVELOPMENT, PLANNED }

    public enum Kind { PASSIVE, ACTION }

    public enum WorldMutation { NONE, TEMPORARY, PERMANENT }

    public AbilityDescriptor {
        Objects.requireNonNull(type, "type");
        triggers = Set.copyOf(triggers);
        effectiveOn = Set.copyOf(effectiveOn);
        params = List.copyOf(params);
        presets = presets == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(presets));
        if (worldMutation != WorldMutation.NONE) {
            // Q11 / #230 : un effet de terrain exige une politique de mutation dédiée, pas encore écrite.
            throw new IllegalArgumentException(type + " : aucune modification du terrain n'est autorisée sans politique dédiée.");
        }
    }

    public java.util.Optional<AbilityParam> param(String key) {
        return params.stream().filter(p -> p.key().equals(key)).findFirst();
    }
}
