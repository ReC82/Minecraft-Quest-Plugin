package com.lodygames.rpgquest.panel.authz;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Rôles du Control Panel (issue #50). Un rôle = un ensemble figé de {@link Permission}. Les
 * handlers ne testent jamais le rôle directement : ils passent par
 * {@link PermissionService#can(String, Permission)}.
 *
 * <ul>
 *   <li>{@code OWNER} — accès total. Ses permissions sont {@code EnumSet.allOf(Permission.class)} :
 *       toute nouvelle permission lui revient automatiquement, rien à maintenir.</li>
 *   <li>{@code ADMIN} — exploitation du serveur : joueurs, PNJ, dialogues, diagnostics, contenu,
 *       actions d'administration. <strong>Pas</strong> de gestion des utilisateurs, ni du module
 *       de développement / déploiement.</li>
 *   <li>{@code TESTER} — lectures utiles au test, diagnostics, préparation de test explicitement
 *       sûre (démarrer / avancer une quête ou une story, lire une variable). Pas d'écriture de
 *       contenu, pas de reset joueur, pas de modération.</li>
 *   <li>{@code BUILDER} — documentation et informations PNJ / contenu nécessaires au travail de
 *       construction. <strong>Pas</strong> d'accès aux données joueurs, pas d'action serveur.</li>
 *   <li>{@code CONTENT_EDITOR} — lecture et édition guidée des quêtes, stories, dialogues et PNJ
 *       logiques ; brouillons et validation. Pas de déploiement (aucune permission de déploiement
 *       n'existe encore) ni de modération joueur.</li>
 *   <li>{@code READ_ONLY} — lecture seule sur les modules explicitement autorisés. Aucune
 *       permission de cette liste ne déclenche de mutation.</li>
 * </ul>
 *
 * Ces rôles sont propres à PlugAdmin — aucune correspondance automatique avec OP Minecraft,
 * Paper ou LuckPerms. Voir docs/control-panel/SECURITY.md.
 */
public enum Role {

    OWNER("Propriétaire", EnumSet.allOf(Permission.class)),

    ADMIN("Administrateur", EnumSet.of(
            Permission.DASHBOARD_VIEW,
            Permission.PLAYERS_READ, Permission.PLAYER_MODERATE, Permission.PLAYER_BUILD_WRITE,
            Permission.NPC_READ, Permission.NPC_WRITE, Permission.NPC_BIND_WRITE,
            Permission.NPC_SPAWN_WRITE, Permission.QUEST_GIVER_WRITE,
            Permission.DIALOGUE_READ, Permission.DIALOGUE_WRITE,
            Permission.QUEST_CONTENT_WRITE, Permission.STORY_CONTENT_WRITE,
            Permission.CONTENT_READ, Permission.CONTENT_EXPORT,
            Permission.DOCS_READ, Permission.DIAGNOSTICS_READ, Permission.AUDIT_READ,
            Permission.ACTION_QUEST, Permission.ACTION_STORY,
            Permission.ACTION_VARIABLE_GET, Permission.ACTION_VARIABLE_SET,
            Permission.ACTION_PLAYER_RESET, Permission.ACTION_ITEM_GIVE,
            Permission.ACTION_CONTENT_RELOAD)),

    TESTER("Testeur", EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.PLAYERS_READ, Permission.NPC_READ,
            Permission.DIALOGUE_READ, Permission.CONTENT_READ, Permission.CONTENT_EXPORT,
            Permission.DIAGNOSTICS_READ, Permission.AUDIT_READ, Permission.DOCS_READ,
            Permission.ACTION_QUEST, Permission.ACTION_STORY, Permission.ACTION_VARIABLE_GET)),

    BUILDER("Builder", EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.NPC_READ, Permission.CONTENT_READ,
            Permission.DIAGNOSTICS_READ, Permission.DOCS_READ)),

    CONTENT_EDITOR("Éditeur de contenu", EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.CONTENT_READ, Permission.CONTENT_EXPORT,
            Permission.DIAGNOSTICS_READ, Permission.AUDIT_READ, Permission.DOCS_READ,
            Permission.NPC_READ, Permission.DIALOGUE_READ,
            Permission.QUEST_CONTENT_WRITE, Permission.STORY_CONTENT_WRITE,
            Permission.DIALOGUE_WRITE, Permission.NPC_WRITE, Permission.QUEST_GIVER_WRITE,
            Permission.ACTION_CONTENT_RELOAD)),

    READ_ONLY("Lecture seule", EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.PLAYERS_READ, Permission.NPC_READ,
            Permission.DIALOGUE_READ, Permission.CONTENT_READ, Permission.CONTENT_EXPORT,
            Permission.DIAGNOSTICS_READ, Permission.AUDIT_READ, Permission.DOCS_READ));

    private final String label;
    private final Set<Permission> permissions;

    Role(String label, Set<Permission> permissions) {
        this.label = label;
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    /** Libellé humain (français) pour l'interface. Le stockage utilise toujours {@link #name()}. */
    public String label() {
        return label;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    /** Vue en lecture seule de l'ensemble des permissions du rôle (tests, diagnostics). */
    public Set<Permission> permissions() {
        return permissions;
    }

    /** Rôle nommé, ou {@code null} si le nom ne correspond à aucun rôle connu. */
    public static Role byNameOrNull(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Role.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
