package com.lodygames.rpgquest.quest.model;

/**
 * {@code npcId} est l'identifiant stable attribué à une entité par
 * {@code com.lodygames.rpgquest.npc.NpcIdentityService} (via
 * {@code /rpgadmin npc tag <id>}), jamais le nom personnalisé affiché de
 * l'entité — celui-ci reste purement cosmétique et peut être renommé
 * librement sans casser cet objectif.
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
