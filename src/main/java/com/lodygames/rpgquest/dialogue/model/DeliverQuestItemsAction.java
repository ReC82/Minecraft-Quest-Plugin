package com.lodygames.rpgquest.dialogue.model;

/**
 * Remet au PNJ, en une seule interaction, tous les objets que le joueur possède et qui manquent
 * encore aux objectifs {@code DELIVER_ITEM_TO_NPC} destinés à ce PNJ (issue #123) — voir
 * {@code quest.progress.QuestProgressEngine#deliverTo} pour les garanties (retrait exact, jamais
 * plus que le reliquat, pas de double retrait, persistance immédiate).
 *
 * <p>{@code npcId} est <strong>optionnel</strong>. Laissé vide, le destinataire est déduit de la
 * clé du dialogue courant — {@code rpgquest:guard} vise le PNJ {@code guard}, suivant la convention
 * « id de dialogue = id de PNJ » du projet. C'est ce qui rend le mécanisme générique : le même
 * dialogue, copié pour un autre PNJ, remet à cet autre PNJ sans qu'aucune donnée ne nomme qui que
 * ce soit. Le renseigner explicitement sert au cas (rare) d'un dialogue partagé ou ouvert par
 * {@code OPEN_DIALOGUE} depuis un autre PNJ.</p>
 */
public record DeliverQuestItemsAction(String npcId) implements DialogueAction {

    /** Destinataire déduit du dialogue courant. */
    public DeliverQuestItemsAction() {
        this(null);
    }

    public DeliverQuestItemsAction {
        if (npcId != null && npcId.isBlank()) {
            npcId = null; // « npc: "" » vaut « non précisé », jamais un id vide qui ne viserait rien.
        }
    }

    @Override
    public ActionType type() {
        return ActionType.DELIVER_QUEST_ITEMS;
    }
}
