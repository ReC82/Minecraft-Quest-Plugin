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
 *       actions d'administration, <strong>suppression de contenu</strong> (#194), module
 *       <strong>Exploitation serveur</strong> (#95) et <strong>rechargement du contenu</strong>
 *       (#131). <strong>Pas</strong> de gestion des utilisateurs, ni du module de développement /
 *       déploiement, ni de l'<strong>élévation OP</strong> (#210, {@code PLAYER_OP_WRITE}) :
 *       celle-ci est réservée à {@code OWNER} par décision explicite du ticket, pour ne pas
 *       l'accorder implicitement à tout administrateur.</li>
 *   <li>{@code TESTER} — lectures utiles au test, diagnostics, préparation de test explicitement
 *       sûre (démarrer / avancer une quête ou une story, lire une variable). Pas d'écriture de
 *       contenu, pas de reset joueur, pas de modération.</li>
 *   <li>{@code BUILDER} — documentation et informations PNJ / contenu nécessaires au travail de
 *       construction. <strong>Pas</strong> d'accès aux données joueurs, pas d'action serveur.</li>
 *   <li>{@code CONTENT_EDITOR} — lecture et édition guidée des quêtes, stories, dialogues et PNJ
 *       logiques ; brouillons et validation. <strong>Pas de publication sur DEV</strong>
 *       ({@code CONTENT_PUBLISH}/{@code CONTENT_ROLLBACK}, issue #47) : enregistrer dans la source
 *       et changer ce qui tourne sur le serveur de test sont deux gestes de portée différente, donc
 *       deux droits. Pas de modération joueur, et <strong>pas de suppression de contenu</strong> :
 *       écrire est réversible, détruire ne l'est pas de la même façon.</li>
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
            Permission.NPC_READ, Permission.TRAVEL_READ, Permission.NPC_WRITE, Permission.NPC_BIND_WRITE,
            Permission.NPC_SPAWN_WRITE, Permission.QUEST_GIVER_WRITE,
            Permission.MOB_READ, Permission.MOB_WRITE, Permission.MOB_TEST_SPAWN,
            Permission.DIALOGUE_READ, Permission.DIALOGUE_WRITE,
            Permission.QUEST_CONTENT_WRITE, Permission.STORY_CONTENT_WRITE,
            Permission.CONTENT_READ, Permission.CONTENT_EXPORT, Permission.CONTENT_IMPORT,
            Permission.CONTENT_DELETE, Permission.NPC_DELETE,
            // Issue #47 : publier sur DEV et défaire font partie de l'exploitation courante d'un
            // administrateur, mais restent deux droits distincts de l'écriture de la source.
            Permission.CONTENT_PUBLISH, Permission.CONTENT_ROLLBACK,
            // Issue #213 : les emplacements de construction font partie de l'exploitation courante.
            Permission.BUILDING_READ, Permission.BUILDING_WRITE, Permission.BUILDING_DELETE,
            // Lot « placement » : poser et restaurer écrivent dans le monde, donc administrateurs
            // seulement. Le Builder consulte la bibliothèque, il ne pose pas — un bâtiment posé
            // écrase des blocs, et ce geste doit rester rare et traçable.
            Permission.BUILDING_PLACE, Permission.BUILDING_ROLLBACK,
            Permission.AI_USE, Permission.AI_CONFIGURE,
            Permission.DOCS_READ, Permission.DIAGNOSTICS_READ, Permission.AUDIT_READ,
            Permission.ACTION_QUEST, Permission.ACTION_STORY,
            Permission.ACTION_VARIABLE_GET, Permission.ACTION_VARIABLE_SET,
            // Issue #235 : le reset COMPLET (qui vide l'inventaire) fait partie des outils de test
            // d'un administrateur, mais reste un droit à part — voir ACTION_PLAYER_RESET_FULL.
            Permission.ACTION_PLAYER_RESET, Permission.ACTION_PLAYER_RESET_FULL,
            Permission.ACTION_ITEM_GIVE,
            Permission.ACTION_CONTENT_RELOAD,
            // Issue #95 : « exploitation du serveur » est la définition même de ce rôle.
            Permission.OPS_VIEW, Permission.OPS_ANNOUNCE, Permission.OPS_RESTART, Permission.OPS_LOGS,
            // Issue #140 : administrer la monnaie fait partie de l'exploitation courante.
            Permission.ECONOMY_READ, Permission.ECONOMY_WRITE)),

    TESTER("Testeur", EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.PLAYERS_READ, Permission.NPC_READ, Permission.TRAVEL_READ,
            Permission.DIALOGUE_READ, Permission.CONTENT_READ, Permission.CONTENT_EXPORT,
            Permission.DIAGNOSTICS_READ, Permission.AUDIT_READ, Permission.DOCS_READ,
            Permission.ACTION_QUEST, Permission.ACTION_STORY, Permission.ACTION_VARIABLE_GET,
            Permission.MOB_READ, Permission.MOB_TEST_SPAWN,
            // Issue #95 : un testeur a besoin de l'état et de la console pour comprendre ce qu'il
            // observe en jeu. Il n'annonce rien et ne redémarre rien.
            Permission.OPS_VIEW, Permission.OPS_LOGS,
            // Issue #140 : lire un solde aide à comprendre un comportement en jeu ; en créer, non.
            Permission.ECONOMY_READ,
            // Issue #213 : un testeur doit pouvoir vérifier qu'un emplacement marqué en jeu est
            // bien arrivé. Lecture seule.
            Permission.BUILDING_READ)),

    BUILDER("Builder", EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.NPC_READ, Permission.TRAVEL_READ, Permission.CONTENT_READ,
            Permission.DIAGNOSTICS_READ, Permission.DOCS_READ, Permission.MOB_READ,
            // Issue #213 : un emplacement de construction est un repère de construction — c'est
            // exactement ce que ce rôle a besoin de consulter. Lecture seule : il ne renomme ni ne
            // supprime rien, et il ne peut de toute façon pas en créer depuis le panel.
            Permission.BUILDING_READ)),

    CONTENT_EDITOR("Éditeur de contenu", EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.CONTENT_READ, Permission.CONTENT_EXPORT,
            Permission.DIAGNOSTICS_READ, Permission.AUDIT_READ, Permission.DOCS_READ,
            Permission.NPC_READ, Permission.DIALOGUE_READ,
            Permission.QUEST_CONTENT_WRITE, Permission.STORY_CONTENT_WRITE,
            Permission.CONTENT_IMPORT, Permission.AI_USE,
            Permission.DIALOGUE_WRITE, Permission.NPC_WRITE, Permission.QUEST_GIVER_WRITE,
            Permission.ACTION_CONTENT_RELOAD, Permission.MOB_READ, Permission.MOB_WRITE)),

    READ_ONLY("Lecture seule", EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.PLAYERS_READ, Permission.NPC_READ, Permission.TRAVEL_READ,
            Permission.DIALOGUE_READ, Permission.CONTENT_READ, Permission.CONTENT_EXPORT,
            Permission.DIAGNOSTICS_READ, Permission.AUDIT_READ, Permission.DOCS_READ, Permission.MOB_READ,
            // Issue #95 : l'état synthétique, oui ; la console, non — elle est plus bavarde
            // (pseudos, coordonnées, erreurs internes).
            Permission.OPS_VIEW, Permission.ECONOMY_READ));

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
