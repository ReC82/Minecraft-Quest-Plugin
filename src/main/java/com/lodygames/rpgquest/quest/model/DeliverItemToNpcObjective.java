package com.lodygames.rpgquest.quest.model;

import org.bukkit.Material;

/**
 * Remettre réellement {@code amount} exemplaires de {@code material} au PNJ {@code npcId}
 * (issue #123). <strong>Distinct de {@link CollectItemObjective}</strong> : posséder, ramasser ou
 * fabriquer l'objet ne fait jamais progresser cet objectif — seule une remise explicite au bon PNJ
 * le fait, et les objets remis sont consommés.
 *
 * <p><strong>Dépôts partiels persistants</strong> : le compteur de l'objectif porte la quantité
 * <em>déjà remise</em>, stockée comme n'importe quel compteur d'objectif (table
 * {@code quest_objective_progress}), donc acquise définitivement — elle survit à la mort, à une
 * reconnexion et à un redémarrage. Le joueur peut revenir déposer le reliquat plus tard.</p>
 *
 * <p>{@code npcId} est l'identifiant stable d'un PNJ logique RPGQuest (même notion que
 * {@link TalkToNpcObjective#npcId()}, posé par {@code /rpgadmin npc tag <id>}), jamais le nom
 * affiché de l'entité : renommer le PNJ ne casse pas l'objectif, et un autre PNJ ne peut pas
 * accepter la remise.</p>
 *
 * <p><strong>Extension prévue (V2, hors #123)</strong> : accepter un objet personnalisé RPGQuest en
 * plus d'un {@link Material} vanilla. Un seul endroit décide aujourd'hui si une pile satisfait
 * l'objectif — {@code quest.progress.QuestItemWithdrawal#matches} — et un seul endroit le parse
 * ({@code QuestDefinitionParser}) : ajouter un champ {@code item:} namespacé n'imposera donc
 * aucune rupture du contrat de contenu existant.</p>
 */
public record DeliverItemToNpcObjective(String npcId, Material material, int amount) implements QuestObjective {

    public DeliverItemToNpcObjective {
        if (npcId == null || npcId.isBlank()) {
            throw new IllegalArgumentException("npcId ne peut pas être vide.");
        }
        if (material == null) {
            throw new IllegalArgumentException("material est obligatoire.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.DELIVER_ITEM_TO_NPC;
    }
}
