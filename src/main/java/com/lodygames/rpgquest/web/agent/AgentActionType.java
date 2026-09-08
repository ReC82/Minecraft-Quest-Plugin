package com.lodygames.rpgquest.web.agent;

import java.util.Optional;

/**
 * Liste blanche des types d'action que l'agent RPGQuest accepte d'exécuter (issue #51 + outillage
 * Control Panel). Tout type absent de cette énumération est <strong>rejeté</strong>
 * ({@code REJECTED}) sans exécution.
 *
 * <p>Chaque type est adossé à un <strong>service métier</strong> via {@link AgentActions} — jamais
 * une commande texte {@code /rpgadmin …}, jamais un {@code dispatchCommand}, jamais du SQL. Les
 * paramètres sont validés par {@link AgentActionExecutor} avant tout appel.</p>
 *
 * <ul>
 *   <li><strong>Lectures</strong> (sans effet) : {@link #PLAYER_VARIABLE_GET}, {@link #PLAYER_LIST},
 *       {@link #QUEST_LIST}, {@link #QUEST_PLAYER_STATUS}, {@link #STORY_LIST},
 *       {@link #STORY_PLAYER_STATUS}, {@link #ITEM_LIST}, {@link #NPC_LIST},
 *       {@link #PLAYER_RESETNEW_PREVIEW}.</li>
 *   <li><strong>Mutations</strong> (confirmation exigée côté panel) : {@link #PLAYER_ITEM_GIVE},
 *       {@link #QUEST_START}, {@link #QUEST_COMPLETE}, {@link #QUEST_RESET}, {@link #STORY_ADVANCE},
 *       {@link #STORY_COMPLETE}, {@link #PLAYER_VARIABLE_SET}, {@link #PLAYER_RESETNEW_CONFIRM}.</li>
 * </ul>
 */
public enum AgentActionType {

    PLAYER_VARIABLE_GET("player.variable.get"),
    PLAYER_LIST("player.list"),
    QUEST_LIST("quest.list"),
    QUEST_PLAYER_STATUS("quest.player.status"),
    STORY_LIST("story.list"),
    STORY_PLAYER_STATUS("story.player.status"),
    ITEM_LIST("item.list"),
    NPC_LIST("npc.list"),
    PLAYER_RESETNEW_PREVIEW("player.resetnew.preview"),

    PLAYER_ITEM_GIVE("player.item.give"),
    QUEST_START("quest.start"),
    QUEST_COMPLETE("quest.complete"),
    QUEST_RESET("quest.reset"),
    STORY_ADVANCE("story.advance"),
    STORY_COMPLETE("story.complete"),
    PLAYER_VARIABLE_SET("player.variable.set"),
    PLAYER_RESETNEW_CONFIRM("player.resetnew.confirm");

    private final String wire;

    AgentActionType(String wire) {
        this.wire = wire;
    }

    /** Nom transporté sur le fil (payload PlugAdmin). */
    public String wire() {
        return wire;
    }

    public static Optional<AgentActionType> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim();
        for (AgentActionType type : values()) {
            if (type.wire.equalsIgnoreCase(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
