package com.lodygames.rpgquest.panel.authz;

/**
 * Permissions du Control Panel. Chaque module demande une permission explicite via
 * {@link PermissionService#can} — jamais un test {@code if role == …} dispersé. {@code OWNER}
 * obtient automatiquement toutes les valeurs de cette énumération ({@code EnumSet.allOf}), donc
 * aucune liste à maintenir permission par permission (issue #50).
 *
 * <p>Les permissions PlugAdmin sont <strong>totalement distinctes</strong> des permissions
 * Minecraft / Paper / LuckPerms : aucune correspondance automatique.</p>
 */
public enum Permission {
    DASHBOARD_VIEW,
    PLAYERS_READ,
    /** Modération d'un joueur (ban / unban). Jamais donnée à un rôle en lecture seule. */
    PLAYER_MODERATE,
    /** Accorder / retirer un droit de construction persistant à un joueur (issue #96 — dépend de #27). */
    PLAYER_BUILD_WRITE,
    NPC_READ,
    NPC_WRITE,
    NPC_BIND_WRITE,
    NPC_SPAWN_WRITE,
    QUEST_GIVER_WRITE,
    DIALOGUE_READ,
    DIALOGUE_WRITE,
    QUEST_CONTENT_WRITE,
    STORY_CONTENT_WRITE,
    CONTENT_READ,
    /** Exporter le contenu déclaratif en content pack versionné (issue #108). Lecture — jamais d'écriture. */
    CONTENT_EXPORT,
    DOCS_READ,
    DIAGNOSTICS_READ,
    AUDIT_READ,
    ACTION_QUEST,
    ACTION_STORY,
    ACTION_VARIABLE_GET,
    ACTION_VARIABLE_SET,
    ACTION_PLAYER_RESET,
    ACTION_ITEM_GIVE,
    ACTION_CONTENT_RELOAD,
    DEV_MODULE,
    /**
     * Gérer les comptes PlugAdmin : créer un utilisateur, changer son rôle, l'activer / le
     * désactiver, consulter la page {@code /users} (issue #50). Réservée à {@code OWNER} par
     * défaut ; jamais accordée à {@code ADMIN} sans décision explicite.
     */
    USER_MANAGE
}
