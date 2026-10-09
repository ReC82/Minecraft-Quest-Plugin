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
 *       {@link #NPC_CITIZENS_LIST}, {@link #DIALOGUE_LIST}, {@link #PLAYER_RESETNEW_PREVIEW}.</li>
 *   <li><strong>Mutations</strong> (confirmation exigée côté panel) : {@link #PLAYER_ITEM_GIVE},
 *       {@link #QUEST_START}, {@link #QUEST_COMPLETE}, {@link #QUEST_RESET}, {@link #STORY_ADVANCE},
 *       {@link #STORY_COMPLETE}, {@link #PLAYER_VARIABLE_SET}, {@link #PLAYER_RESETNEW_CONFIRM},
 *       {@link #NPC_DEFINITION_CREATE}, {@link #NPC_DEFINITION_UPDATE}, {@link #QUEST_GIVER_SET}
 *       (écritures de contenu : définitions PNJ {@code npcs/*.yml} et champ {@code giver:} des
 *       quêtes — jamais de YAML brut ni de chemin arbitraire), {@link #NPC_CITIZENS_LINK}
 *       (liaison définition ↔ PNJ Citizens existant, jamais de spawn/rebind — issue #81 phase 1),
 *       {@link #NPC_CITIZENS_CREATE} (crée physiquement un PNJ Citizens depuis une définition puis
 *       le lie ; rollback si la liaison échoue — issue #81 phase 2),
 *       {@link #DIALOGUE_DEFINITION_CREATE} (crée un squelette de dialogue minimal valide —
 *       V1 {@code /dialogues}), {@link #DIALOGUE_NODE_CREATE} / {@link #DIALOGUE_NODE_UPDATE} /
 *       {@link #DIALOGUE_CHOICE_ADD} / {@link #DIALOGUE_CHOICE_UPDATE} /
 *       {@link #DIALOGUE_CHOICE_DELETE} (édition guidée d'un dialogue existant — nœud simple et
 *       choix simple uniquement, réécriture canonique re-parsée puis rechargée avant validation,
 *       issue #82 phase 1).</li>
 * </ul>
 */
public enum AgentActionType {

    PLAYER_VARIABLE_GET("player.variable.get"),
    PLAYER_LIST("player.list"),
    PLAYER_CATALOG("player.catalog"),
    QUEST_LIST("quest.list"),
    QUEST_PLAYER_STATUS("quest.player.status"),
    STORY_LIST("story.list"),
    STORY_PLAYER_STATUS("story.player.status"),
    ITEM_LIST("item.list"),
    NPC_LIST("npc.list"),
    /** Export versionné du contenu déclaratif (issue #108) — lecture seule, aucun effet de bord. */
    CONTENT_EXPORT("content.export"),
    PLAYER_RESETNEW_PREVIEW("player.resetnew.preview"),
    /** Catalogue waypoints/bornes (issue #152) — lecture seule, aucun effet de bord. */
    TRAVEL_CATALOG("travel.catalog"),
    /** Catalogue des profils de mobs spéciaux/boss + throttle Wild (issue #169) — lecture seule. */
    MOB_LIST("mob.list"),
    /** Issue #172 : catalogues réels du serveur (entités, particules, sons, biomes, mondes). */
    MOB_CATALOGS("mob.catalogs"),
    /**
     * Issue #196 : catalogue complet des matériaux de la version installée, pour les icônes de
     * quête et les récompenses d'objet — lecture seule, aucun effet de bord.
     */
    ITEM_CATALOGS("item.catalogs"),
    /**
     * Issue #194 : supprime la définition d'une quête ou d'une story sur le serveur, avec
     * sauvegarde. Jamais de cascade, jamais de progression joueur touchée.
     */
    CONTENT_DEFINITION_DELETE("content.definition.delete"),

    PLAYER_ITEM_GIVE("player.item.give"),
    QUEST_START("quest.start"),
    QUEST_COMPLETE("quest.complete"),
    QUEST_RESET("quest.reset"),
    STORY_ADVANCE("story.advance"),
    STORY_COMPLETE("story.complete"),
    PLAYER_VARIABLE_SET("player.variable.set"),
    PLAYER_RESETNEW_CONFIRM("player.resetnew.confirm"),
    PLAYER_BAN("player.ban"),
    PLAYER_UNBAN("player.unban"),
    NPC_DEFINITION_CREATE("npc.definition.create"),
    NPC_DEFINITION_UPDATE("npc.definition.update"),
    /**
     * Issue #226 : supprime la <strong>définition logique seule</strong> ({@code npcs/<id>.yml}),
     * après sauvegarde côté serveur. Ne touche ni au PNJ Citizens, ni à la liaison, ni au dialogue :
     * la cascade silencieuse est refusée par construction. Le serveur <strong>revalide</strong> les
     * références de contenu au moment d'exécuter, et refuse si la suppression laisserait une quête
     * pointant un donneur inexistant.
     */
    NPC_DEFINITION_DELETE("npc.definition.delete"),
    QUEST_GIVER_SET("quest.giver.set"),
    NPC_CITIZENS_LIST("npc.citizens.list"),
    NPC_CITIZENS_LINK("npc.citizens.link"),
    /**
     * Issue #226 : retire la liaison RPGQuest ↔ Citizens en <strong>laissant le PNJ Citizens
     * vivre</strong>. Exige l'identifiant numérique attendu : une divergence arrête l'opération au
     * lieu de délier la mauvaise liaison.
     */
    NPC_CITIZENS_UNLINK("npc.citizens.unlink"),
    /**
     * Issue #226 : détruit le PNJ Citizens <strong>physique</strong> et retire la liaison qui le
     * désignait. Exige l'identifiant numérique, et ne détruit que l'entité dont l'UUID <em>et</em>
     * l'identifiant numérique correspondent à cette liaison — c'est ce qui garantit qu'on ne détruit
     * jamais le voisin. Ne supprime ni la définition, ni le dialogue.
     */
    NPC_CITIZENS_DELETE("npc.citizens.delete"),
    NPC_CITIZENS_CREATE("npc.citizens.create"),
    /** Issue #165 : nom affiché en jeu d'un PNJ Citizens (jamais l'id logique RPGQuest). */
    NPC_CITIZENS_PROVISION("npc.citizens.provision"),
    NPC_CITIZENS_MOVE("npc.citizens.move"),
    NPC_CITIZENS_RENAME("npc.citizens.rename"),
    /** Issue #165 : skin d'un PNJ Citizens depuis une URL MineSkin validée côté serveur. */
    NPC_CITIZENS_SKIN("npc.citizens.skin"),
    /**
     * Issue #165 : « regarder les joueurs » (trait Citizens {@code lookclose}). Le paramètre
     * {@code enabled} porte un état <strong>explicite</strong> — jamais une bascule, pour qu'un
     * rejeu ne puisse pas inverser l'état.
     */
    NPC_CITIZENS_LOOKCLOSE("npc.citizens.lookclose"),
    /**
     * Issue #165 : promenade (fournisseur {@code wander} du trait Citizens {@code waypoints}).
     * Refuse d'écraser une patrouille existante sans {@code confirm_replace}.
     */
    NPC_CITIZENS_WANDER("npc.citizens.wander"),
    /**
     * Issue #213 — emplacements de construction. Lecture seule : la <strong>création</strong> n'a
     * volontairement pas d'action agent, parce qu'un emplacement est défini par une position choisie
     * dans le monde. Un formulaire du panel devrait inventer des coordonnées ; le clic en jeu, lui,
     * les connaît. Les quatre autres actions ne font que modifier ou retirer une fiche existante.
     */
    BUILDING_SITE_LIST("building.site.list"),
    BUILDING_SITE_RENAME("building.site.rename"),
    BUILDING_SITE_DESCRIBE("building.site.describe"),
    /** Corrige l'orientation cardinale d'un emplacement — jamais sa position. */
    BUILDING_SITE_FACING("building.site.facing"),
    /**
     * Supprime le <strong>marqueur logique</strong> d'un emplacement. Ne touche aucun bloc du monde :
     * ce lot ne sait rien poser, donc il n'y a rien à défaire en jeu. Idempotent.
     */
    BUILDING_SITE_DELETE("building.site.delete"),
    /**
     * Issue #213, lot « placement » — la bibliothèque de bâtiments, en lecture seule.
     *
     * <p>Aucune action de création, de téléversement ni d'édition : une définition de bâtiment est
     * du <strong>contenu déclaratif versionné</strong>, qui se modifie dans son fichier YAML et se
     * relit au démarrage. Un formulaire web qui l'écrirait contournerait la revue.</p>
     */
    BUILDING_DEFINITION_LIST("building.definition.list"),
    /**
     * Calcule rotation et emprise pour un couple (emplacement, bâtiment). <strong>N'écrit
     * rien</strong>, ni dans le monde, ni en base : c'est ce qui permet de montrer l'emprise avant
     * de demander confirmation.
     */
    BUILDING_PLACEMENT_PREVIEW("building.placement.preview"),
    /**
     * Pose réellement le bâtiment. Sauvegarde la zone écrasée <strong>avant</strong> de coller, et
     * n'enregistre le placement qu'après un collage réussi — donc un échec ne laisse jamais un faux
     * placement.
     */
    BUILDING_PLACEMENT_PLACE("building.placement.place"),
    /**
     * Restaure la zone telle qu'elle était avant la pose, depuis la sauvegarde prise à ce
     * moment-là. <strong>Refusé s'il n'y a pas de sauvegarde</strong> : remettre de l'air dans
     * l'emprise détruirait le terrain d'origine, ce qui serait une destruction déguisée en
     * annulation.
     */
    BUILDING_PLACEMENT_ROLLBACK("building.placement.rollback"),
    DIALOGUE_LIST("dialogue.list"),
    DIALOGUE_DEFINITION_CREATE("dialogue.definition.create"),
    DIALOGUE_NODE_CREATE("dialogue.node.create"),
    DIALOGUE_NODE_UPDATE("dialogue.node.update"),
    DIALOGUE_CHOICE_ADD("dialogue.choice.add"),
    DIALOGUE_CHOICE_UPDATE("dialogue.choice.update"),
    DIALOGUE_CHOICE_DELETE("dialogue.choice.delete"),

    /** Écriture/toggle d'un profil de mob spécial/boss ({@code mobs/<id>.yml}, issue #169 lot 1). */
    MOB_DEFINITION_CREATE("mob.definition.create"),
    MOB_DEFINITION_UPDATE("mob.definition.update"),
    MOB_DEFINITION_TOGGLE("mob.definition.toggle"),
    /** Throttle global du tirage aléatoire Wild (issue #169 lot 1). */
    MOB_SPAWN_SETTINGS_SET("mob.spawn-settings.set"),
    /** Spawn/suppression d'instances de test, distinctes des mobs ordinaires (issue #169 lot 1). */
    MOB_TEST_SPAWN("mob.test.spawn"),
    MOB_TEST_CLEAR("mob.test.clear"),
    /**
     * Issue #95 — annonce globale aux joueurs connectés. Le message est envoyé comme
     * <strong>texte</strong> : jamais exécuté comme commande, jamais interprété comme du
     * MiniMessage (un {@code <click:run_command:…>} dans une annonce ferait exécuter une commande à
     * tous les joueurs qui cliquent).
     */
    SERVER_ANNOUNCE("server.announce"),
    /**
     * Issue #95 — dernières lignes de console captées par le plugin, pour la page « Exploitation
     * serveur ». Lecture seule et bornée ; aucune commande, aucun chemin de fichier.
     */
    SERVER_LOGS_TAIL("server.logs.tail"),
    /**
     * Issue #131 — <strong>aperçu</strong> du rechargement de contenu : valide les familles
     * demandées et leurs références croisées <em>sans rien appliquer</em>. Lecture seule.
     */
    CONTENT_RELOAD_PREVIEW("content.reload.preview"),
    /**
     * Issue #47 — l'état DEV du contenu : quels fichiers sont présents, avec quelle empreinte, et
     * quels identifiants le moteur porte réellement. <strong>Lecture seule.</strong>
     *
     * <p>C'est ce relevé qui permet au panel de distinguer « différent » de « conflit »
     * <em>avant</em> de proposer de publier quoi que ce soit.</p>
     */
    CONTENT_DEV_STATE("content.dev.state"),
    /**
     * Issue #47 — publie une ressource de contenu sur CE serveur, puis prouve qu'elle est chargée.
     *
     * <p>Le contenu voyage dans le paramètre {@code yaml} : l'agent est <strong>sortant</strong>,
     * donc le panel ne peut pas pousser un fichier — c'est le serveur qui le reçoit et l'écrit
     * lui-même, dans un dossier issu d'une liste blanche. Le navigateur n'envoie jamais de chemin.</p>
     */
    /**
     * Issue #47 — lit le CONTENU d'un seul fichier de DEV, pour une comparaison lisible.
     *
     * <p>Lecture seule et ciblée : {@code content.dev.state} ne transporte que des empreintes, ce
     * qui suffit pour détecter un écart mais pas pour le <em>montrer</em>. Faire voyager tous les
     * fichiers dans le relevé d'état serait disproportionné ; on lit donc celui qu'on regarde.</p>
     */
    CONTENT_DEV_READ("content.dev.read"),
    CONTENT_PUBLISH("content.publish"),
    /**
     * Issue #47 — défait une publication : restaure la sauvegarde si elle existe, retire le fichier
     * si la ressource était nouvelle. Jamais l'un déguisé en l'autre.
     */
    CONTENT_PUBLISH_ROLLBACK("content.publish.rollback"),
    /**
     * Issue #131 — rechargement effectif du contenu dans le runtime, après validation. N'applique
     * rien si une famille est invalide ou si une référence croisée serait cassée : l'ancien runtime
     * valide est conservé. Jamais un {@code /reload} Bukkit.
     */
    CONTENT_RELOAD("content.reload"),
    /**
     * Issue #210 — statut <strong>OP Minecraft</strong>. Distinct d'un rôle PlugAdmin, d'un droit de
     * construction et d'un bypass de gameplay : cette action ne touche qu'{@code OfflinePlayer#setOp}.
     */
    PLAYER_OP("player.op"),
    PLAYER_DEOP("player.deop"),
    /** Issue #210 — renvoi d'un joueur connecté à une position sûre du Hub. Préserve tout. */
    PLAYER_SEND_HUB("player.send.hub"),
    /** Issue #210 — expulsion d'un joueur connecté avec une raison affichée. */
    PLAYER_KICK("player.kick"),
    /** Issue #210 — whitelist, en précisant si elle est réellement appliquée par le serveur. */
    PLAYER_WHITELIST_ADD("player.whitelist.add"),
    PLAYER_WHITELIST_REMOVE("player.whitelist.remove"),
    /**
     * Issue #140 — solde réel et journal des transactions d'un joueur. Lecture seule : le
     * portefeuille persistant est l'unique source de vérité, aucun inventaire n'est interprété
     * comme de la monnaie.
     */
    ECONOMY_BALANCE("economy.balance"),
    /**
     * Issue #140 — crédit administratif. <strong>Crée de la monnaie</strong> : raison obligatoire,
     * enregistrée dans le journal des transactions.
     */
    ECONOMY_CREDIT("economy.credit"),
    /**
     * Issue #140 — débit administratif. Ne peut <strong>jamais</strong> rendre le solde négatif :
     * fonds insuffisants = refus métier lisible, rien n'est modifié.
     */
    ECONOMY_DEBIT("economy.debit"),
    /**
     * Issue #16 (second lot) — récompenses monétaires de quête <strong>restées dues</strong> après
     * un crash ou une panne SQL. Lecture seule : montant, occurrence, tentatives et motif d'échec
     * réels, tels qu'enregistrés à la complétion.
     */
    ECONOMY_DEBTS("economy.debts"),
    /**
     * Issue #16 (second lot) — reprise d'une récompense due. Réutilise l'identité de paiement
     * <strong>initiale</strong> : jamais un nouvel identifiant, donc aucun risque de payer deux
     * fois. Le montant est celui figé à la complétion, pas celui de la définition courante.
     */
    ECONOMY_DEBT_RETRY("economy.debt.retry"),
    /**
     * Issue #16 (second lot) — marque une récompense due comme <strong>réglée à la main</strong>
     * (compensation administrative). Ne touche <strong>pas</strong> au portefeuille : elle empêche
     * seulement la même récompense d'être payée une seconde fois par une reprise.
     */
    ECONOMY_DEBT_SETTLE("economy.debt.settle"),
    /**
     * Issue #200 — état réel du pont vers les droits Minecraft : LuckPerms est-il là, et quels
     * droits gérés un joueur porte-t-il réellement, avec leur provenance. Lecture seule.
     */
    MC_RIGHTS_READ("mc.rights.read"),
    /**
     * Issue #200 — pousse la définition d'un groupe du pont dans LuckPerms (ses droits gérés, avec
     * contexte de monde). Idempotente : rejouée, elle ne change rien et le dit.
     */
    MC_GROUP_SYNC("mc.group.sync"),
    /**
     * Issue #200 — retire un groupe du pont dans LuckPerms. Ses membres perdent exactement les
     * droits qu'il portait, et rien d'autre.
     */
    MC_GROUP_DELETE("mc.group.delete"),
    /**
     * Issue #200 — fait correspondre les appartenances <strong>du pont</strong> d'un joueur à
     * l'état voulu. Ne touche jamais un nœud posé directement sur l'utilisateur, ni une
     * appartenance à un groupe externe.
     */
    MC_RIGHTS_SYNC("mc.rights.sync");

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
