package com.lodygames.rpgquest.dialogue.model;

public enum ConditionType {
    QUEST_STATE,
    HAS_ITEM,
    HAS_PERMISSION,
    VARIABLE_EQUALS,
    NO_MAIN_CLAIM,
    HAS_MAIN_CLAIM,
    LACKS_CUSTOM_ITEM,
    /** Il reste des objets à remettre à ce PNJ (issue #123). */
    HAS_PENDING_DELIVERY
}
