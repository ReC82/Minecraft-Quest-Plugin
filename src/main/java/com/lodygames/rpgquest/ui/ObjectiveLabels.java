package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.quest.model.BreakBlockObjective;
import com.lodygames.rpgquest.quest.model.CollectItemObjective;
import com.lodygames.rpgquest.quest.model.CraftItemObjective;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.PlaceBlockObjective;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.ReachLocationObjective;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import net.kyori.adventure.text.Component;

/**
 * Libellé <strong>court et lisible</strong> d'un objectif, pour les infobulles du journal.
 *
 * <p>Remplace {@code QuestObjective.describe}, qui rendait l'identifiant technique brut
 * (« Tuer SPIDER », « Collecter AMETHYST_SHARD ») : des majuscules et des underscores dans une
 * infobulle joueur. Ici le nom de l'entité / du bloc / de l'objet vient de sa <strong>clé de
 * traduction vanilla</strong> ({@code EntityType}/{@code Material} implémentent
 * {@code Translatable}), donc le client affiche « Araignée » ou « Éclat d'améthyste » dans sa
 * propre langue — aucune table de correspondance à maintenir côté plugin, et jamais de mot anglais
 * figé en dur.</p>
 *
 * <p>Volontairement sans verbe ni ponctuation : la ligne d'infobulle ajoute elle-même le compteur
 * (« Araignée 3/5 »), ce qui tient sur une ligne courte même avec des noms longs.</p>
 */
final class ObjectiveLabels {

    private ObjectiveLabels() {
    }

    /** Nom affichable de la cible d'un objectif, traduit côté client quand Minecraft le permet. */
    static Component targetName(QuestObjective objective) {
        return switch (objective) {
            case KillEntityObjective o -> Component.translatable(o.entity());
            case BreakBlockObjective o -> Component.translatable(o.material());
            case PlaceBlockObjective o -> Component.translatable(o.material());
            case CollectItemObjective o -> Component.translatable(o.material());
            case CraftItemObjective o -> Component.translatable(o.material());
            // Pas de clé de traduction pour un PNJ : on reste générique plutôt que d'afficher
            // l'identifiant logique (« libraire »), qui est une donnée interne.
            case TalkToNpcObjective o -> Component.text("Parler au PNJ");
            case ReachLocationObjective o -> Component.text("Atteindre le lieu");
        };
    }

    /**
     * {@code true} si l'objectif se compte (un compteur {@code n/total} a du sens). Les objectifs
     * binaires — parler à un PNJ, atteindre un lieu — n'affichent pas « 0/1 », qui serait du bruit.
     */
    static boolean isCountable(QuestObjective objective) {
        return !(objective instanceof TalkToNpcObjective || objective instanceof ReachLocationObjective);
    }
}
