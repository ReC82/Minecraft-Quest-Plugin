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
    /**
     * Issue #235 : aperçu du reset « nouveau joueur complet » — même lecture que
     * {@link #PLAYER_RESETNEW_PREVIEW}, mais annonce en plus le vidage de l'inventaire. Lecture
     * seule : aucune écriture, aucun objet retiré.
     */
    PLAYER_RESETFULL_PREVIEW("player.resetfull.preview"),
    /** Catalogue waypoints/bornes (issue #152) — lecture seule, aucun effet de bord. */
    TRAVEL_CATALOG("travel.catalog"),
    /**
     * Issue #156 : apparier une borne à <strong>une</strong> instance du Hub nommée par son
     * waypoint. Écrit dans le monde, mais sur cette seule instance : aucun balayage, aucun
     * rattrapage global, aucun seuil de densité ou d'espacement modifié.
     */
    TRAVEL_BEACON_PAIR("travel.beacon.pair"),
    /**
     * Issue #134 : renommer le nom d'affichage d'<strong>un</strong> waypoint. Seul le nom change :
     * id, position, découvertes et références de quête restent intacts.
     */
    WAYPOINT_RENAME("waypoint.rename"),
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
    /**
     * Issue #235 : reset « nouveau joueur complet » — tout ce que fait
     * {@link #PLAYER_RESETNEW_CONFIRM}, <strong>plus</strong> le vidage de l'inventaire, de
     * l'équipement et du coffre de l'Ender. Action distincte et non un paramètre, pour qu'un clic
     * ne puisse pas se tromper d'intention et que le journal d'audit dise laquelle a eu lieu.
     */
    PLAYER_RESETFULL_CONFIRM("player.resetfull.confirm"),
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
    /**
     * Issue #197 : <strong>le</strong> nom du personnage, changé sur toutes ses couches à la fois —
     * définition RPGQuest et nom Citizens affiché au-dessus de l'entité.
     *
     * <p>Les deux actions séparées ({@link #NPC_DEFINITION_UPDATE} et {@link #NPC_CITIZENS_RENAME})
     * restent utilisables, et le resteront : elles servent aux cas où l'on veut agir sur une seule
     * couche. Mais elles exposaient à l'administrateur une distinction purement technique, et un
     * geste oublié suffisait à faire divergier le catalogue du jeu. Cette action-ci est le chemin
     * normal : elle coordonne, relit les deux couches et refuse de présenter un succès partiel
     * comme un succès. L'identifiant logique n'est jamais touché.</p>
     */
    NPC_IDENTITY_RENAME("npc.identity.rename"),
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
     * Issue #381 — emplacements de PNJ. Comme pour les emplacements de construction, la
     * <strong>réservation</strong> n'a pas d'action agent : elle se fait en jeu, par clic droit avec
     * l'outil « Emplacements PNJ » ({@code /rpgadmin npc spot tool}). {@link #NPC_SPOT_CAPTURE} est l'exception
     * qui confirme la règle : la position vient du PNJ lui-même, jamais d'un formulaire.
     */
    NPC_SPOT_LIST("npc.spot.list"),
    NPC_SPOT_RENAME("npc.spot.rename"),
    NPC_SPOT_ORIENT("npc.spot.orient"),
    NPC_SPOT_RELEASE("npc.spot.release"),
    NPC_SPOT_DELETE("npc.spot.delete"),
    /** Place (déplace ou fait apparaître) un PNJ sur un emplacement réservé — jamais de doublon. */
    NPC_SPOT_PLACE("npc.spot.place"),
    NPC_SPOT_CAPTURE("npc.spot.capture"),
    /**
     * Issue #213 — emplacements de construction. Lecture seule : la <strong>création</strong> n'a
     * volontairement pas d'action agent, parce qu'un emplacement est défini par une position choisie
     * dans le monde. Un formulaire du panel devrait inventer des coordonnées ; le clic en jeu, lui,
     * les connaît. Les quatre autres actions ne font que modifier ou retirer une fiche existante.
     */
    BUILDING_SITE_LIST("building.site.list"),
    BUILDING_SITE_RENAME("building.site.rename"),
    BUILDING_SITE_DESCRIBE("building.site.describe"),
    /**
     * Issue #94 : le <strong>brief architectural</strong> d'un emplacement, distinct de la note
     * d'administrateur. Une note se lit dans une liste ; un brief décrit matériaux, étages, pièces
     * et contraintes, et sert d'entrée à la génération. Les confondre imposait au brief la borne de
     * la note (500 caractères), que le navigateur appliquait en tronquant la saisie sans le dire.
     */
    BUILDING_SITE_BRIEF("building.site.brief"),
    /**
     * Issue #94 : génère un bâtiment depuis un <strong>plan déclaratif</strong> produit par une IA.
     *
     * <p>Ce que l'action transporte est une <em>donnée</em>, pas du code : une taille, une ancre,
     * une orientation et une liste finie d'instructions au vocabulaire fermé
     * ({@code box}, {@code shell}, {@code walls}, {@code clear}, {@code gable}). Aucun chemin de
     * fichier, aucune commande, aucun identifiant de fichier ne vient du modèle — le nom du
     * {@code .schem} est dérivé de l'identifiant du plan, lui-même borné à {@code [a-z0-9_]}. Le
     * plan est revalidé par le plugin avant toute écriture.</p>
     */
    BUILDING_GENERATE("building.generate"),
    /**
     * {@code building.plan.audit} (#94, lot F) : juge un plan <strong>sans rien écrire</strong>.
     *
     * <p>Même lecture, même expanseur, même audit que {@link #BUILDING_GENERATE} — mais aucun
     * {@code .schem}, aucune définition, aucun rechargement de bibliothèque. C'est l'action que la
     * boucle de correction appelle entre deux relances du modèle : sans elle, il faudrait écrire un
     * bâtiment pour apprendre qu'il est mauvais.</p>
     *
     * <p>En lecture seule, donc, et exposée comme telle : elle n'a pas besoin du droit d'écriture
     * sur les bâtiments.</p>
     */
    BUILDING_PLAN_AUDIT("building.plan.audit"),
    /**
     * {@code building.plan.preview} (#94, lot E) : des <strong>coupes 2D</strong> du bâtiment que ce
     * plan produirait, plus son verdict, et aucune écriture.
     *
     * <p>Les coupes sont prises sur la même grille que l'audit, après la même expansion. C'est la
     * raison d'être de cette action : le ticket interdit un second moteur d'expansion dans le panel,
     * parce qu'un aperçu dessiné là-bas montrerait ce que le panel <em>croit</em> que le plan décrit.
     * La V1 a prouvé que les deux peuvent diverger de cinq blocs.</p>
     *
     * <p>L'aperçu est rendu même pour un plan refusé — c'est précisément quand on veut voir
     * pourquoi.</p>
     */
    BUILDING_PLAN_PREVIEW("building.plan.preview"),
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
     * Retire un bâtiment de la bibliothèque (issue #284) — la seule écriture sur une définition
     * venue du panel, et volontairement étroite : refusée si le bâtiment est posé quelque part ou
     * s'il s'agit d'un exemple embarqué ; les fichiers sont <strong>archivés</strong>, jamais
     * effacés, et rien ne réapparaît au redémarrage. Voir {@code BuildingRetirement}.
     */
    BUILDING_DEFINITION_RETIRE("building.definition.retire"),
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
    /**
     * Issue #234 — ce que donnerait une réorientation ou un remplacement. <strong>N'écrit
     * rien.</strong> Porte un jeton d'empreinte qui doit être renvoyé à la confirmation : un aperçu
     * périmé est refusé au lieu d'être appliqué.
     */
    BUILDING_PLACEMENT_RETARGET_PREVIEW("building.placement.retarget.preview"),
    /**
     * Issue #234 — réoriente le bâtiment posé, en repartant du terrain d'origine et de la
     * définition. <strong>Ce n'est pas une rotation des blocs en place</strong> : le monde est
     * ramené à son terrain d'origine puis le bâtiment est recollé, ce qui donne exactement le même
     * résultat qu'une pose initiale dans cette orientation.
     */
    BUILDING_PLACEMENT_REORIENT("building.placement.reorient"),
    /** Issue #234 — remplace le bâtiment posé par un autre, dans l'orientation demandée. */
    BUILDING_PLACEMENT_REPLACE("building.placement.replace"),
    /**
     * Issue #234 — le journal des opérations d'un emplacement. Lecture seule, et les
     * <strong>échecs y figurent</strong> : un journal muet au moment du problème ne sert à rien.
     */
    BUILDING_PLACEMENT_HISTORY("building.placement.history"),
    /**
     * Issue #246 — ce que donnerait la capture d'une construction posée comme nouveau bâtiment :
     * bornes, volume, conteneurs vidés, entités omises. <strong>N'écrit rien.</strong> Porte un jeton
     * à renvoyer à la confirmation.
     */
    BUILDING_PLACEMENT_CAPTURE_PREVIEW("building.placement.capture.preview"),
    /**
     * Issue #246 — capture la construction posée (état réel du monde) comme NOUVEAU bâtiment de
     * bibliothèque. Aucun bloc modifié, aucun fichier écrasé, l'original et le placement intacts.
     */
    BUILDING_PLACEMENT_CAPTURE("building.placement.capture"),
    /** Issue #178 — villes : relevé (lecture seule). */
    CITY_LIST("city.list"),
    /** Issue #178 — renommer une ville (l'identifiant ne change jamais). */
    CITY_RENAME("city.rename"),
    /** Issue #178 — régler la distance de détection (carré horizontal). */
    CITY_RESIZE("city.resize"),
    /** Issue #178 — activer / désactiver une ville. */
    CITY_ACTIVE("city.active"),
    /** Issue #178 — armer le déplacement du centre : le prochain clic d'outil du joueur désigné. */
    CITY_MOVE_ARM("city.move.arm"),
    /** Issue #178 — montrer le périmètre en particules au joueur désigné (~15 s). */
    CITY_PERIMETER_SHOW("city.perimeter.show"),
    /** Issue #178 — supprimer une ville et ses découvertes (confirmation explicite). */
    CITY_DELETE("city.delete"),
    /** Issue #322 — associer (ou retirer) le PNJ central d'une ville par son npc_id. */
    CITY_NPC_LINK("city.npc.link"),
    /** Issue #322 — l'arrivée de voyage devient la position actuelle d'un joueur connecté. */
    CITY_ARRIVAL_SET("city.arrival.set"),
    /** Issue #322 — revenir à l'arrivée au centre. */
    CITY_ARRIVAL_CLEAR("city.arrival.clear"),
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
    /**
     * Issue #214 : vérifier un fichier de profil rempli hors de PlugAdmin (parseur réel, références,
     * différences avec le profil existant) -- n'écrit rien.
     */
    MOB_DEFINITION_IMPORT_CHECK("mob.definition.import.check"),
    /**
     * Issue #214 : écrire ce profil importé, et lui seul (confirmation de remplacement, empreinte du
     * fichier vérifié, sauvegarde de l'ancien). Ne fait jamais apparaître de créature.
     */
    MOB_DEFINITION_IMPORT_APPLY("mob.definition.import.apply"),
    /** Throttle global du tirage aléatoire Wild (issue #169 lot 1). */
    MOB_SPAWN_SETTINGS_SET("mob.spawn-settings.set"),
    /** Spawn/suppression d'instances de test, distinctes des mobs ordinaires (issue #169 lot 1). */
    MOB_TEST_SPAWN("mob.test.spawn"),
    MOB_TEST_CLEAR("mob.test.clear"),
    /** Boss communautaires du Wild (issue #175) : état, réglages, apparition de test, retrait. */
    BOSS_COMMUNITY_STATUS("boss.community.status"),
    BOSS_COMMUNITY_SETTINGS_SET("boss.community.settings.set"),
    BOSS_COMMUNITY_TEST_SPAWN("boss.community.test.spawn"),
    BOSS_COMMUNITY_REMOVE("boss.community.remove"),
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
     * Issue #427 — publie un LOT (un pack d'histoire) en une seule opération : toutes les
     * ressources écrites, un seul rechargement de l'ensemble, tout ou rien. Avec
     * {@code dry_run=true}, le moteur juge le lot sans rien laisser derrière lui.
     */
    CONTENT_PUBLISH_BATCH("content.publish.batch"),
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
    /** Issue #335 — paramètres généraux du jeu, tels que relus dans {@code settings.yml}. Lecture seule. */
    SETTINGS_GET("settings.get"),
    /**
     * Issue #335 — modifie des paramètres généraux. Validés, écrits atomiquement, relus ; rien
     * n'est écrit si une valeur est invalide.
     */
    SETTINGS_SET("settings.set"),
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
    MC_RIGHTS_SYNC("mc.rights.sync"),
    /**
     * Issue #13 (étape 2) — PNJ récurrents : modèles chargés, erreurs de chargement, occurrences
     * par monde (position, PNJ Citizens, état, dernière erreur) et événements récents. Lecture seule.
     */
    ENCOUNTER_LIST("encounter.list"),
    /**
     * Issue #422 — droits à récompense : liste filtrable (joueur, statut), catalogue et
     * interrupteur. Lecture seule.
     */
    ENTITLEMENT_LIST("entitlement.list"),
    /**
     * Issue #422 — déclare un droit à récompense pour un joueur. Référence obligatoire : la clé
     * d'idempotence {@code admin:<référence>:<uuid>} rend un second envoi sans effet.
     */
    ENTITLEMENT_DECLARE("entitlement.declare"),
    /** Issue #422 — révoque un droit <strong>en attente</strong> ; un droit remis ne se révoque pas. */
    ENTITLEMENT_REVOKE("entitlement.revoke"),
    /**
     * Issue #177 (tranche 2) — état de {@code entitlements.yml} pour l'éditeur de tables : fichier
     * présent / valide / activé, erreurs, catalogue des récompenses et tables. Lecture seule.
     */
    REWARD_CATALOG_STATUS("reward.catalog.status"),
    /**
     * Issue #177 — crée ou remplace une table de récompenses aléatoires (section {@code tables} de
     * {@code entitlements.yml}) : validée par le vrai chargeur avant écriture, écriture atomique,
     * ancienne version remise si le rechargement échoue.
     */
    REWARD_TABLE_SAVE("reward.table.save"),
    /** Issue #177 — copie une table sous un nouvel identifiant, désactivée. */
    REWARD_TABLE_DUPLICATE("reward.table.duplicate"),
    /** Issue #177 — active ou désactive une table (ses chances et sa version ne changent pas). */
    REWARD_TABLE_TOGGLE("reward.table.toggle"),
    /**
     * Issue #474 — crée {@code entitlements.yml} quand il manque : désactivé, sans récompense ni table.
     * Refusé si un fichier existe déjà.
     */
    REWARD_CATALOG_INIT("reward.catalog.init"),
    /** Issue #474 — interrupteur général {@code enabled} du catalogue des récompenses. */
    REWARD_CATALOG_TOGGLE("reward.catalog.toggle"),
    /**
     * Issue #474 — crée ou remplace une récompense du catalogue (objets, enchantements, potion,
     * avantage) : mêmes garanties que {@code reward.table.save}, références vérifiées par le serveur.
     */
    REWARD_DEFINITION_SAVE("reward.definition.save"),
    /** Issue #474 — copie une récompense sous une nouvelle clé, désactivée. */
    REWARD_DEFINITION_DUPLICATE("reward.definition.duplicate"),
    /** Issue #474 — active ou désactive une récompense (son contenu ne change pas). */
    REWARD_DEFINITION_TOGGLE("reward.definition.toggle"),
    /**
     * Issue #336 — état de {@code prices.yml} (catalogue central des prix) : fichier présent /
     * valide, erreurs, avertissements, prix et marchands qui les citent. Lecture seule.
     */
    PRICE_CATALOG_STATUS("price.catalog.status"),
    /**
     * Issue #336 — crée ou remplace un prix (vente, rachat ou fabrication) : validé par le vrai
     * chargeur (boucles d'enrichissement comprises) avant écriture, écriture atomique, ancienne
     * version remise si le rechargement échoue. Le premier enregistrement crée le fichier.
     */
    PRICE_SAVE("price.save"),
    /** Issue #336 — copie un prix sous une nouvelle clé, désactivé. */
    PRICE_DUPLICATE("price.duplicate"),
    /** Issue #336 — active ou désactive un prix (son contenu ne change pas). */
    PRICE_TOGGLE("price.toggle"),
    /** Issue #13 — réactive une occurrence et la matérialise si sa zone est chargée. Cette occurrence seule. */
    ENCOUNTER_RETRY("encounter.retry"),
    /** Issue #13 — désactive une occurrence : son PNJ est retiré, l'instance ne génère plus rien. */
    ENCOUNTER_DISABLE("encounter.disable"),
    /**
     * Issue #13 — retire une occurrence (PNJ + ligne). L'instance pourra accueillir une nouvelle
     * rencontre à la prochaine visite ; les quêtes déjà acceptées ne sont pas touchées.
     */
    ENCOUNTER_REMOVE("encounter.remove"),
    /** Issue #13 — relit {@code encounters/*.yml}. Ne touche à aucun PNJ déjà apparu. */
    ENCOUNTER_TEMPLATES_RELOAD("encounter.templates.reload"),
    /** Issue #13 — efface les délais d'attente : les instances en échec seront retentées à la prochaine visite. */
    ENCOUNTER_BACKOFFS_CLEAR("encounter.backoffs.clear"),
    /**
     * Issue #13 (étape 3) — crée un modèle {@code encounters/<id>.yml} à partir de champs validés
     * (jamais de YAML brut), puis relit les modèles. Échoue si l'id existe déjà.
     */
    ENCOUNTER_TEMPLATE_CREATE("encounter.template.create"),
    /** Issue #13 — remplace un modèle existant. Les occurrences déjà apparues gardent leur PNJ et leur identité. */
    ENCOUNTER_TEMPLATE_UPDATE("encounter.template.update"),
    /** Issue #13 — active ou désactive un modèle (plus aucune nouvelle occurrence), sans toucher aux existantes. */
    ENCOUNTER_TEMPLATE_TOGGLE("encounter.template.toggle"),
    /**
     * Issue #13 — supprime un modèle après sauvegarde dans {@code encounter-backups/}. Refusé s'il a
     * encore des occurrences, sauf accord explicite ({@code acknowledge_sites=true}) : elles restent
     * alors en place, avec leur PNJ et leur dialogue.
     */
    ENCOUNTER_TEMPLATE_DELETE("encounter.template.delete"),
    /**
     * Issue #369 — catalogues de quêtes secondaires : entrées enrichies (titre, délai, existence),
     * modèles qui les utilisent, contrôles de validité, et liste des quêtes pour la recherche.
     */
    QUEST_CATALOG_LIST("quest.catalog.list"),
    /** Issue #369 — crée {@code quest-catalogs/<id>.yml} à partir de champs validés ; échoue si l'id existe. */
    QUEST_CATALOG_CREATE("quest.catalog.create"),
    /** Issue #369 — remplace un catalogue existant. Aucune progression de joueur n'est touchée. */
    QUEST_CATALOG_UPDATE("quest.catalog.update"),
    /**
     * Issue #369 — supprime un catalogue après sauvegarde dans {@code quest-catalog-backups/}. Refusé
     * s'il est attribué à un modèle, sauf {@code detach=true} (la référence est retirée des modèles).
     */
    QUEST_CATALOG_DELETE("quest.catalog.delete");

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
