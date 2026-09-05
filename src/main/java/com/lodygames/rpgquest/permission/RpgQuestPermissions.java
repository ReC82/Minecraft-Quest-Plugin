package com.lodygames.rpgquest.permission;

import java.util.Locale;

/**
 * Source unique des nœuds de permission RPGQuest — jamais de littéral {@code "rpgquest.*"} recopié
 * dans un listener ou une commande (issue #27). L'arbre complet, ses valeurs {@code default} et sa
 * hiérarchie {@code children} sont déclarés dans {@code plugin.yml} ; cette classe n'en est que le
 * miroir typé côté Java.
 *
 * <p><strong>Séparation volontaire</strong> (principe central de l'issue #27) :</p>
 * <ul>
 *   <li>{@code rpgquest.build.*} — construction dans les zones de build gérées par RPGQuest
 *       (Hubs, Wild, mondes spécialisés). N'accorde <strong>jamais</strong>, à aucun niveau, le
 *       droit de contourner la protection d'un claim joueur.</li>
 *   <li>{@code rpgquest.admin.<action>} — une action d'administration du monde par nœud
 *       ({@code /rpgadmin flatten|zone|portal|mob|npc|spawn|worlds|waystone|story|player|guide}),
 *       toutes filles de {@code rpgquest.admin.world} (l'OP garde donc tout).</li>
 *   <li>{@code rpgquest.claim.bypass} / {@code rpgquest.claim.admin} — contournement et
 *       administration des claims, strictement à part du build.</li>
 * </ul>
 */
public final class RpgQuestPermissions {

    // ---- Build (ne contourne JAMAIS un claim joueur) ----------------------------------------------

    /** Construire dans toutes les zones de build gérées par RPGQuest (tous Hubs + Wild + mondes spécialisés). */
    public static final String BUILD_ALL = "rpgquest.build.*";
    /** Construire dans tous les Hubs, quel que soit leur identifiant. */
    public static final String BUILD_HUB_ALL = "rpgquest.build.hub.*";
    /** Construire des structures administratives dans le Wild (aucun bypass de claim). */
    public static final String BUILD_WILD = "rpgquest.build.wild";
    /** Construire / interagir librement à l'intérieur de n'importe quelle zone protégée RPGQuest. */
    public static final String BUILD_ZONE = "rpgquest.build.zone";

    private static final String BUILD_HUB_PREFIX = "rpgquest.build.hub.";
    private static final String BUILD_WORLD_PREFIX = "rpgquest.build.world.";

    /** Nœud d'un Hub précis, ex. {@code rpgquest.build.hub.0}. */
    public static String buildHub(String hubId) {
        return BUILD_HUB_PREFIX + hubId.toLowerCase(Locale.ROOT);
    }

    /** Nœud d'un monde spécialisé précis, ex. {@code rpgquest.build.world.claims}. */
    public static String buildWorld(String worldKey) {
        return BUILD_WORLD_PREFIX + worldKey.toLowerCase(Locale.ROOT);
    }

    // ---- Administration du monde (une action = un nœud, toutes filles de admin.world) ------------

    /** Parapluie historique : accorde toutes les actions ci-dessous + build + claim.bypass/admin. */
    public static final String ADMIN_WORLD = "rpgquest.admin.world";
    public static final String ADMIN_FLATTEN = "rpgquest.admin.flatten";
    public static final String ADMIN_ZONE = "rpgquest.admin.zone";
    public static final String ADMIN_PORTAL = "rpgquest.admin.portal";
    public static final String ADMIN_MOB = "rpgquest.admin.mob";
    public static final String ADMIN_NPC = "rpgquest.admin.npc";
    public static final String ADMIN_SPAWN = "rpgquest.admin.spawn";
    public static final String ADMIN_WORLDS = "rpgquest.admin.worlds";
    public static final String ADMIN_WAYSTONE = "rpgquest.admin.waystone";
    public static final String ADMIN_STORY = "rpgquest.admin.story";
    public static final String ADMIN_PLAYER = "rpgquest.admin.player";
    public static final String ADMIN_GUIDE = "rpgquest.admin.guide";

    // ---- Claims (strictement séparé du build) ---------------------------------------------------

    /** Ignorer la protection d'un claim joueur (casse/pose/conteneurs/animaux...). */
    public static final String CLAIM_BYPASS = "rpgquest.claim.bypass";
    /** Outils d'administration des claims ({@code /claim} — suppression d'un claim tiers, etc.). */
    public static final String CLAIM_ADMIN = "rpgquest.claim.admin";

    private RpgQuestPermissions() {
    }
}
