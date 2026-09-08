package com.lodygames.rpgquest.panel.authz;

/**
 * Permissions du Control Panel. La V1 n'en applique presque aucune (un seul rôle {@code OWNER} qui
 * a tout), mais l'énumération est posée dès maintenant : les modules futurs demandent une
 * permission explicite via {@link PermissionService#can}, jamais un test {@code if role == …}.
 */
public enum Permission {
    DASHBOARD_VIEW,
    PLAYERS_READ,
    NPC_READ,
    NPC_WRITE,
    NPC_BIND_WRITE,
    NPC_SPAWN_WRITE,
    QUEST_GIVER_WRITE,
    CONTENT_READ,
    DIAGNOSTICS_READ,
    AUDIT_READ,
    ACTION_QUEST,
    ACTION_STORY,
    ACTION_VARIABLE_GET,
    ACTION_VARIABLE_SET,
    ACTION_PLAYER_RESET,
    ACTION_ITEM_GIVE,
    ACTION_CONTENT_RELOAD,
    DEV_MODULE
}
