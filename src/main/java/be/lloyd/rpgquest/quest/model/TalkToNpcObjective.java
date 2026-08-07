package be.lloyd.rpgquest.quest.model;

/**
 * L'identifiant du PNJ est une simple chaîne libre : aucun système de PNJ
 * n'existe encore (voir le package {@code npc} prévu pour une étape
 * ultérieure). La résolution réelle (Citizens ou solution interne) est hors
 * périmètre de cette étape.
 */
public record TalkToNpcObjective(String npcId) implements QuestObjective {

    public TalkToNpcObjective {
        if (npcId == null || npcId.isBlank()) {
            throw new IllegalArgumentException("npcId ne peut pas être vide.");
        }
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.TALK_TO_NPC;
    }
}
