package com.lodygames.rpgquest.quest.model;

import org.bukkit.NamespacedKey;

/**
 * Issue #171 — vaincre <strong>son</strong> boss de quête : un profil de mob de catégorie
 * {@code BOSS} en mode {@code QUEST} ({@code mob.model.BossMode#QUEST}), créé pour le joueur quand
 * l'étape qui porte cet objectif devient active (à l'acceptation si c'est la première étape).
 *
 * <p>Distinct de {@link KillEntityObjective} : tuer n'importe quel mob du même type, ou le boss d'un
 * autre joueur, ne valide jamais cet objectif. Seule la mort de l'instance possédée par le joueur
 * pour CETTE quête, CETTE étape et CET objectif compte (voir {@code mob.quest.QuestBossService}).
 * Toujours binaire (quantité 1).</p>
 *
 * @param mob identifiant du profil de mob ({@code rpgquest:ogre}) ; son existence et sa catégorie
 *            sont vérifiées à l'apparition, pas au chargement (les profils se chargent après les
 *            quêtes) — un profil absent est signalé au joueur et dans les logs, jamais ignoré
 */
public record KillQuestBossObjective(NamespacedKey mob) implements QuestObjective {

    public KillQuestBossObjective {
        if (mob == null) {
            throw new IllegalArgumentException("mob est obligatoire.");
        }
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.KILL_QUEST_BOSS;
    }
}
