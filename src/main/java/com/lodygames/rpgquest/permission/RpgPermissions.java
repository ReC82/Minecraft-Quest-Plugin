package com.lodygames.rpgquest.permission;

import org.bukkit.permissions.Permissible;

/**
 * Point <strong>unique</strong> de nommage des permissions Minecraft de RPGQuest (issues #27/#200).
 *
 * <p>Avant ce découpage, un seul nœud — {@code rpgquest.admin.world} — gouvernait
 * <strong>tout</strong> : la construction dans le Hub, le bypass des claims, le bypass des zones,
 * l'accès au monde des claims <em>et</em> l'intégralité de {@code /rpgadmin}, y compris le marquage
 * de PNJ. Il était donc impossible d'autoriser « construire dans le Hub » sans donner en même temps
 * la surface d'administration complète, ni de distinguer « éditer des PNJ » de « construire ».</p>
 *
 * <h2>Compatibilité : {@link #LEGACY_ADMIN_WORLD} reste une ombrelle explicite</h2>
 *
 * <p>Chaque contrôle accepte le nouveau nœud <strong>ou</strong> {@code rpgquest.admin.world}. Un
 * administrateur déjà autorisé garde donc <strong>exactement</strong> ses droits, sans rien
 * reconfigurer — c'est la condition pour que ce découpage ne casse personne. En revanche cette
 * ombrelle ne doit <strong>jamais</strong> être attribuée à un groupe « builder » ou « éditeur
 * PNJ » : elle donne tout.</p>
 *
 * <h2>Ce qu'une permission de construction n'accorde JAMAIS</h2>
 *
 * <p>Aucun nœud {@code rpgquest.build.*} n'est consulté par la protection des claims, des zones
 * protégées, des waypoints/bornes de voyage, ni par aucune commande d'administration. Construire et
 * contourner une protection sont deux choses différentes, et elles ont des nœuds différents.</p>
 */
public final class RpgPermissions {

    private RpgPermissions() {
    }

    /**
     * Ombrelle historique. La détenir implique <strong>tous</strong> les droits découpés ci-dessous.
     * Conservée pour ne pas casser les administrateurs existants ; à ne jamais accorder à un groupe
     * restreint.
     */
    public static final String LEGACY_ADMIN_WORLD = "rpgquest.admin.world";

    // ---- Construction par monde ----------------------------------------------------------------

    /**
     * Préfixe des droits de construction dans un Hub donné : {@code rpgquest.build.hub.<monde>}.
     *
     * <h3>Convention retenue, et pourquoi elle diffère du libellé de #27</h3>
     *
     * <p>#27 prévoyait {@code rpgquest.build.hub.<id>}. <strong>L'« id » retenu est le nom du monde
     * du Hub</strong>, lu dans la configuration réelle ({@code hub.world}, défaut
     * {@code world_hub}). La raison est simple : c'est le <em>seul</em> identifiant de Hub qui
     * existe aujourd'hui dans le plugin. La configuration ne décrit qu'un Hub, et le seul
     * discriminant dont dispose le listener au moment de décider est
     * {@code player.getWorld().getName()}. Inventer un identifiant logique distinct aurait créé une
     * seconde source de vérité à maintenir, pour une distinction que rien ne sait encore faire.</p>
     *
     * <h3>Limites assumées</h3>
     *
     * <ul>
     *   <li><strong>Plusieurs Hubs dans des mondes différents</strong> : fonctionne tel quel, un
     *       nœud par monde, plus {@link #BUILD_HUB_ALL} pour tous.</li>
     *   <li><strong>Plusieurs Hubs dans le MÊME monde</strong> (plusieurs zones de hub) : <em>non
     *       distinguables</em>. Le nœud porterait le même nom pour les deux, et autoriser l'un
     *       autoriserait l'autre. Il faudrait alors un identifiant de Hub dans la configuration et
     *       une résolution par zone dans le listener — un lot à part entière.</li>
     *   <li><strong>Renommer le monde du Hub</strong> change le nœud : les droits accordés sur
     *       l'ancien nom deviennent sans effet, silencieusement. À faire figurer dans toute
     *       procédure de renommage de monde.</li>
     * </ul>
     */
    public static final String BUILD_HUB_PREFIX = "rpgquest.build.hub.";

    /** Construction dans <strong>tous</strong> les Hubs. */
    public static final String BUILD_HUB_ALL = "rpgquest.build.hub.*";

    /**
     * Construction dans le Wild.
     *
     * <p><strong>Sans effet aujourd'hui, et c'est dit franchement</strong> : le Wild n'a aucune
     * restriction de construction — un joueur ordinaire y construit déjà librement. Ce nœud existe
     * pour la convention (exprimer un groupe « builder » de façon uniforme) et pour un éventuel
     * verrou futur. Il n'accorde <strong>aucun</strong> bypass de zone protégée, de claim ou de
     * waypoint : le Wild contient de telles protections, et une permission de construction ne les
     * contourne jamais.</p>
     */
    public static final String BUILD_WILD = "rpgquest.build.wild";

    /** Droit de construire dans ce monde de Hub précis. */
    public static String buildHub(String hubWorldName) {
        return BUILD_HUB_PREFIX + hubWorldName;
    }

    /**
     * {@code true} si {@code who} peut construire dans le monde de Hub {@code hubWorldName}.
     *
     * <p>Trois chemins acceptés : le monde précis, le joker tous-Hubs, ou l'ombrelle historique.
     * Le joker est testé <strong>explicitement</strong> et non laissé à l'interprétation du
     * gestionnaire de permissions : sans LuckPerms, Bukkit ne développe pas {@code .*}.</p>
     */
    public static boolean canBuildInHub(Permissible who, String hubWorldName) {
        if (who == null) {
            return false;
        }
        return who.hasPermission(LEGACY_ADMIN_WORLD)
                || who.hasPermission(BUILD_HUB_ALL)
                || who.hasPermission(buildHub(hubWorldName));
    }

    // ---- Bypass de protection, séparés les uns des autres --------------------------------------

    /** Contourner la protection des claims d'autrui. Distinct de toute permission de construction. */
    public static final String BYPASS_CLAIM = "rpgquest.bypass.claim";

    /** Contourner la protection des zones. Distinct des claims et de la construction. */
    public static final String BYPASS_ZONE = "rpgquest.bypass.zone";

    /**
     * Accès et règles administratifs du <strong>monde</strong> des claims : entrer sans être
     * éligible, et ignorer les règles de ce monde. Préserve le correctif #22 — un porteur reçoit
     * malgré tout une Pierre de retour, et l'entrée ne lui est jamais refusée.
     */
    public static final String BYPASS_CLAIM_WORLD = "rpgquest.bypass.claimworld";

    /** Contrôle « nouveau nœud OU ombrelle historique », utilisé par tous les bypass. */
    public static boolean hasBypass(Permissible who, String node) {
        return who != null && (who.hasPermission(node) || who.hasPermission(LEGACY_ADMIN_WORLD));
    }

    // ---- Commandes d'administration ------------------------------------------------------------

    /**
     * Entrer dans {@code /rpgadmin}. <strong>N'autorise aucune branche</strong> : chaque
     * sous-commande exige en plus son propre nœud (ou l'ombrelle). C'est l'exigence explicite
     * « une permission parent permettant d'entrer dans la commande ne doit pas permettre d'exécuter
     * toutes ses branches ».
     */
    public static final String ADMIN_COMMAND = "rpgquest.admin.command";

    /** Branche PNJ de {@code /rpgadmin} : parent des trois sous-actions réellement disponibles. */
    public static final String ADMIN_NPC = "rpgquest.admin.npc";

    /** Marquer une entité d'un identifiant RPGQuest stable ({@code /rpgadmin npc tag}). */
    public static final String ADMIN_NPC_TAG = "rpgquest.admin.npc.tag";

    /** Retirer ce marquage ({@code /rpgadmin npc untag}). */
    public static final String ADMIN_NPC_UNTAG = "rpgquest.admin.npc.untag";

    /** Lire le marquage de l'entité visée ({@code /rpgadmin npc info}) — lecture seule. */
    public static final String ADMIN_NPC_INFO = "rpgquest.admin.npc.info";

    /**
     * Emplacements de construction (issue #213) : obtenir l'outil, créer un emplacement d'un clic,
     * et lister les emplacements depuis le jeu.
     *
     * <p><strong>Nœud dédié, et pas une permission WorldEdit.</strong> L'outil n'a rien à voir avec
     * WorldEdit — il ne sélectionne aucune région et ne modifie aucun bloc — et un builder équipé de
     * WorldEdit n'a aucune raison de pouvoir créer des points d'ancrage de contenu. Réutiliser
     * {@code worldedit.wand} aurait lié deux surfaces d'autorisation sans rapport, et rendu
     * impossible d'accorder l'une sans l'autre.</p>
     *
     * <p>{@code default: false} : un opérateur ne l'obtient pas automatiquement par son statut OP,
     * mais il détient déjà {@link #LEGACY_ADMIN_WORLD}, qui l'implique comme toutes les autres
     * branches. Un compte non-OP peut donc recevoir ce seul nœud sans rien d'autre.</p>
     */
    public static final String ADMIN_BUILD_SITE = "rpgquest.admin.buildsite";

    /**
     * {@code true} si {@code who} peut gérer les emplacements de construction : son nœud dédié, ou
     * l'ombrelle historique.
     */
    public static boolean canManageBuildingSites(Permissible who) {
        return who != null
                && (who.hasPermission(ADMIN_BUILD_SITE) || who.hasPermission(LEGACY_ADMIN_WORLD));
    }

    /** {@code true} si {@code who} peut entrer dans {@code /rpgadmin} (sans rien y exécuter). */
    public static boolean canEnterAdminCommand(Permissible who) {
        return who != null && (who.hasPermission(ADMIN_COMMAND) || who.hasPermission(LEGACY_ADMIN_WORLD));
    }

    /**
     * {@code true} si {@code who} peut exécuter une sous-action PNJ : son nœud précis, le parent de
     * la branche, ou l'ombrelle historique.
     */
    public static boolean canRunNpcAction(Permissible who, String preciseNode) {
        if (who == null) {
            return false;
        }
        return who.hasPermission(LEGACY_ADMIN_WORLD)
                || who.hasPermission(ADMIN_NPC)
                || who.hasPermission(preciseNode);
    }

    /**
     * {@code true} si {@code who} peut exécuter une branche de {@code /rpgadmin} qui n'a pas encore
     * de nœud dédié.
     *
     * <p>Ces branches restent derrière l'ombrelle historique : le découpage fin du reste de
     * {@code /rpgadmin} appartient au backlog #27 et n'est pas nécessaire ici. Conséquence voulue :
     * un compte ne portant que {@link #ADMIN_COMMAND} (et éventuellement les nœuds PNJ) peut entrer
     * dans la commande mais <strong>ne peut exécuter ni reset joueur, ni économie, ni aucune autre
     * opération d'administration</strong>.</p>
     */
    public static boolean canRunLegacyAdminBranch(Permissible who) {
        return who != null && who.hasPermission(LEGACY_ADMIN_WORLD);
    }
}
