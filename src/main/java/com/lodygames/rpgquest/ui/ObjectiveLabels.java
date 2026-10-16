package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.quest.model.BreakBlockObjective;
import com.lodygames.rpgquest.quest.model.CollectItemObjective;
import com.lodygames.rpgquest.quest.model.CraftItemObjective;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.DiscoverWaypointObjective;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.PlaceBlockObjective;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.ReachLocationObjective;
import com.lodygames.rpgquest.quest.model.SmeltItemObjective;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import java.util.Optional;
import java.util.function.Function;
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
public final class ObjectiveLabels {

    private ObjectiveLabels() {
    }

    /**
     * Nom affichable de la cible d'un objectif, traduit côté client quand Minecraft le permet.
     *
     * <p>Public depuis #238 : le message « Quête commencée » envoyé dans le chat affichait
     * l'identifiant brut ({@code WHEAT_SEEDS}) parce qu'il passait par une simple chaîne. Il réutilise
     * désormais ce libellé-ci, qui est la <strong>seule</strong> règle de nommage des cibles du
     * projet — en recréer une seconde aurait signifié deux vocabulaires pour le même objet.</p>
     */
    public static Component targetName(QuestObjective objective) {
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
            // Issue #123 : le nom de l'objet reste traduit côté client, mais il faut distinguer
            // « ramasser du cuir » de « remettre du cuir au PNJ » — sinon deux lignes du journal
            // seraient identiques alors qu'elles n'attendent pas du tout la même action. Le PNJ
            // destinataire n'est pas nommé ici : son id logique est une donnée interne (même
            // raison que TALK_TO_NPC ci-dessus), et le dialogue du PNJ, lui, donne le détail.
            case DeliverItemToNpcObjective o -> Component.translatable(o.material())
                    .append(Component.text(" (à remettre)"));
            // Issue #141 : « (à cuire) » distingue la cuisson d'une collecte ou d'un craft du même
            // objet — trois objectifs qui attendent trois actions différentes.
            case SmeltItemObjective o -> Component.translatable(o.material())
                    .append(Component.text(" (à cuire)"));
            // Issue #185 : la PORTÉE et la RÈGLE sont dans le libellé, jamais supposées. Le ticket
            // interdit d'imposer silencieusement un mode de comptage ou de laisser croire que tous
            // les mondes comptent.
            case DiscoverWaypointObjective o -> Component.text("Waypoints à découvrir")
                    .append(Component.text(o.worlds().isEmpty() ? " (tous mondes)"
                            : " (" + String.join(", ", o.worlds()) + ")"))
                    .append(Component.text(o.countMode() == DiscoverWaypointObjective.CountMode.NEW_ONLY
                            ? " — nouvelles découvertes" : " — découvertes déjà acquises incluses"));
            // Issue #178 : le nom ACTUEL de la ville (jamais son identifiant), et la règle de
            // comptage toujours énoncée au joueur.
            case com.lodygames.rpgquest.quest.model.DiscoverCityObjective o -> Component.text(o.specific()
                            ? "Découvrir la ville " + com.lodygames.rpgquest.city.CityNames.of(o.city())
                            : "Villes distinctes à découvrir")
                    .append(Component.text(o.countMode() == DiscoverWaypointObjective.CountMode.NEW_ONLY
                            ? " — nouvelles découvertes" : " — découvertes déjà acquises incluses"));
            // Issue #171 : le nom affiché du boss (jamais son identifiant) ; « votre » rappelle que
            // seul le boss créé pour ce joueur compte.
            case com.lodygames.rpgquest.quest.model.KillQuestBossObjective o -> Component.text("Vaincre votre boss "
                    + com.lodygames.rpgquest.mob.MobNames.of(o.mob()));
        };
    }

    /**
     * Issue #70 — comme {@link #targetName(QuestObjective)}, mais nomme le PNJ concerné quand
     * {@code npcNames} connaît son <strong>nom affiché</strong> (texte brut, jamais l'identifiant
     * logique) : « Parler à Garde » au lieu de « Parler au PNJ ». Sans nom connu, le libellé
     * générique reste inchangé — un identifiant interne n'est jamais montré au joueur.
     */
    public static Component targetName(QuestObjective objective, Function<String, Optional<String>> npcNames) {
        return switch (objective) {
            case TalkToNpcObjective o -> npcNames.apply(o.npcId())
                    .<Component>map(name -> Component.text("Parler à " + name))
                    .orElseGet(() -> targetName(objective));
            case DeliverItemToNpcObjective o -> npcNames.apply(o.npcId())
                    .<Component>map(name -> Component.translatable(o.material())
                            .append(Component.text(" (à remettre à " + name + ")")))
                    .orElseGet(() -> targetName(objective));
            default -> targetName(objective);
        };
    }

    /**
     * {@code true} si l'objectif se compte (un compteur {@code n/total} a du sens). Les objectifs
     * binaires — parler à un PNJ, atteindre un lieu — n'affichent pas « 0/1 », qui serait du bruit.
     */
    public static boolean isCountable(QuestObjective objective) {
        return !(objective instanceof TalkToNpcObjective || objective instanceof ReachLocationObjective
                || objective instanceof com.lodygames.rpgquest.quest.model.KillQuestBossObjective);
    }
}
