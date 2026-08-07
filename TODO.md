# TODO

## Environnement (fait)

-   [x] Projet Gradle Kotlin DSL + wrapper (Gradle 9.6.1)
-   [x] Paper API 1.21.11 + `runServer` (xyz.jpenilla.run-paper 3.0.2)
-   [x] JUnit 5 + MockBukkit (mockbukkit-v1.21)
-   [x] Classe principale `RPGQuestPlugin` + `plugin.yml`
-   [x] Commandes `/rpgquest version` et `/rpgquest help`

## Architecture modulaire (fait)

-   [x] `PluginService` (start/stop) + `PluginServiceRegistry` (ordre
    garanti au démarrage, LIFO à l'arrêt, rollback si un service échoue)
-   [x] `config.yml` (`debug`, `locale`, `database.file`, `resource-pack`)
    validé au démarrage avec des messages d'erreur lisibles
-   [x] `/rpgquest reload` (`rpgquest.admin`) : recharge sans recréer les
    services ni perdre de données ; config précédente conservée si invalide
-   [x] Interfaces des futurs moteurs : `QuestEngine`, `DialogueEngine`,
    `CustomItemRegistry`, `QuestJournalUi` (toutes `PluginService`) —
    `QuestEngine` et `DialogueEngine` sont désormais implémentées
    (`YamlQuestEngine`, `YamlDialogueEngine`), `CustomItemRegistry` et
    `QuestJournalUi` restent des interfaces marqueurs

## Définitions de quêtes (fait)

-   [x] Modèles immuables (`QuestDefinition`, étapes, objectifs, récompenses,
    textes localisables) avec identifiants namespacés (`NamespacedKey`)
-   [x] 7 types d'objectifs (BREAK_BLOCK, PLACE_BLOCK, KILL_ENTITY,
    COLLECT_ITEM, CRAFT_ITEM, TALK_TO_NPC, REACH_LOCATION) et 4 types de
    récompenses (EXPERIENCE, ITEM, VARIABLE, COMMAND)
-   [x] `QuestLoader` : validation par fichier (doublons de champs, valeurs
    négatives, types inconnus, champs obligatoires), un fichier invalide ne
    bloque pas les autres, doublons d'id et prérequis manquants détectés
    entre fichiers
-   [x] `/quest admin reload` et `/quest admin validate` (`rpgquest.admin`)
-   [x] Deux quêtes d'exemple générées automatiquement (`first_steps`,
    `woodcutters_request`)

## Progression des quêtes (fait)

-   [x] États `NOT_STARTED` (implicite, absence de ligne), `ACTIVE`,
    `READY_TO_TURN_IN`, `COMPLETED`, `FAILED` (état modélisé, non déclenché
    par le gameplay actuel), `ABANDONED`
-   [x] Acceptation (prérequis, doublon, répétition contrôlée), abandon
    (ré-acceptable ensuite), progression par événement, remise automatique
    à la fin de la dernière étape (octroi des récompenses)
-   [x] Écouteurs Bukkit enregistrés **uniquement** pour les types
    d'objectifs réellement utilisés par les quêtes chargées (recalculé à
    chaque `/quest admin reload`)
-   [x] `QuestObjectiveIndex` : aucune quête non concernée n'est parcourue
    par événement
-   [x] Anti double-incrément (compteur plafonné au requis) et anti
    double-remise (bascule mémoire synchrone avant toute persistance async)
-   [x] `quest_objective_progress` (migration V2) : étape courante, compteurs
    et état persistés, rechargés à la reconnexion
-   [x] Commandes `/quest list|accept|progress|abandon|complete`
-   [x] Permissions distinctes `rpgquest.quest` (joueur) / `rpgquest.admin`
-   [x] Messages MiniMessage configurables (`messages.yml`)

## Dialogues (fait)

-   [x] Modèles immuables (`DialogueDefinition`, nœuds, choix, conditions,
    actions) — réutilisent `LocalizedText`/`QuestState` de `quest.model`
-   [x] 4 types de conditions (QUEST_STATE, HAS_ITEM, HAS_PERMISSION,
    VARIABLE_EQUALS) et 9 types d'actions (START_QUEST, ADVANCE_QUEST,
    TURN_IN_QUEST, GIVE_ITEM, TAKE_ITEM, SET_VARIABLE, RUN_SAFE_COMMAND,
    OPEN_DIALOGUE, CLOSE)
-   [x] `DialogueLoader` : validation par fichier (dont liste blanche des
    commandes), un fichier invalide ne bloque pas les autres, doublons
    d'id et références `OPEN_DIALOGUE` manquantes détectés entre fichiers,
    **détection de boucles entre dialogues** (les boucles `next` internes à
    un même dialogue restent autorisées)
-   [x] `DialogueRenderer` derrière une interface : `ChatDialogueRenderer`
    (texte cliquable, par défaut) et `PaperDialogRenderer` (API Dialog
    native de Paper, marquée expérimentale — désactivée par défaut)
-   [x] `DialogueSessionEngine` : conditions/actions évaluées de façon
    asynchrone, sessions en mémoire uniquement (pas persistées)
-   [x] `/dialogue open <joueur> <dialogueId>` (`rpgquest.admin`) + clic sur
    une entité nommée
-   [x] Dialogue d'exemple du garde (propose/refuse/explique la récompense)

## Journal de quêtes (fait)

-   [x] `/quests` : inventaire paginé (54 slots, 1 ligne de chrome + 45 slots
    de contenu) avec onglets **Actives**/**Disponibles**/**Terminées**
-   [x] Icône configurable par quête (`icon:` dans le YAML, `BOOK` par
    défaut) ; lore MiniMessage : description, catégorie, état, étape et
    progression des objectifs, récompenses, prérequis (avec le nom des
    quêtes référencées)
-   [x] Clic gauche : ouvre une vue détail dédiée (icône, bouton retour,
    bouton suivre/ne plus suivre, bouton fermer)
-   [x] Clic droit : suit/ne suit plus la quête directement depuis la liste
    (étoile dans le titre de l'icône)
-   [x] Boutons précédent/suivant (liste), retour (détail), fermer (les deux)
-   [x] **Aucun** déplacement, vol ou duplication possible : tout clic ou
    drag touchant un inventaire du journal est annulé, quel que soit le
    type (gauche, droit, shift, double clic, touche numérique, drag)
-   [x] Bossbar Adventure optionnelle (`config.yml` → `journal.tracker-enabled`)
    pour la quête suivie, avec progression en direct
-   [x] Suivi persistant (stocké via `player_variables`, survit à une
    reconnexion) — indépendant du fait que la quête soit déjà acceptée
-   [x] Affichage rafraîchi uniquement en réaction à un changement réel de
    progression (`QuestProgressEngine.onProgressChanged`) : aucune tâche
    répétitive, aucun sondage
-   [x] Nettoyage à la déconnexion (session + bossbar) et à la désactivation
    du plugin (fermeture des inventaires ouverts, bossbars masquées)

## Objets personnalisés (fait)

-   [x] `CustomItemDefinition` immuable (id namespacé, type, matériau de
    base, nom + lore MiniMessage, rareté, model data et/ou item model,
    empilabilité, durabilité, attributs, enchantements, tags de gameplay,
    comportement spécial, restrictions de fabrication)
-   [x] 4 types (`WEAPON`, `TOOL`, `RESOURCE`, `QUEST_ITEM`)
-   [x] `ItemLoader` : validation par fichier, un fichier invalide ne
    bloque pas les autres, doublons d'id détectés entre fichiers
-   [x] `YamlCustomItemRegistry` : centralise la création (`create`) et
    l'identification (`identify`/`isCustomItem`/`resolve`) — l'id est
    stocké **uniquement** dans le PersistentDataContainer, jamais déduit
    du nom ou du lore
-   [x] 4 objets d'exemple générés automatiquement : `forest_blade`
    (arme), `miner_pickaxe` (outil), `spider_fang` (ressource),
    `refined_crystal` (objet de quête)
-   [x] `/customitem give|list` (`rpgquest.admin`) et `/customitem inspect`
    (`rpgquest.item`, identifie l'objet en main)
-   [x] Empilement autorisé/interdit réellement appliqué (`ItemMeta#setMaxStackSize`,
    quantité invalide refusée à la création plutôt que tronquée)

## Comportements d'armes et d'outils (fait)

-   [x] Armes (`combat:`) : dégâts et vitesse d'attaque en bonus additifs
    (compatibles Tranchant/attributs vanilla), chance de critique +
    multiplicateur, effet conditionnel à cooldown, message/particule
    configurables
-   [x] Outils (`tool:`) : bonus de vitesse de minage (attribut
    `MINING_EFFICIENCY`), blocs autorisés, consommation de durabilité
    personnalisée, bonus de récolte probabiliste, capacité spéciale à
    cooldown (clic droit)
-   [x] `CooldownManager` indexé par (UUID, id de capacité), purgé
    activement (tâche async 5 min) et à la déconnexion
-   [x] Toutes les valeurs validées au chargement : pas de NaN/infini, pas
    de valeurs négatives incohérentes (chance hors `[0,1]`, cooldown/durabilité
    négatifs, objet empilable + durable en même temps déjà rejeté)
-   [x] Anti-exploitation : main secondaire jamais consultée, armor stand
    exclu des cibles, dégâts de projectile jamais traités comme un coup
    d'arme, identification exclusivement PersistentDataContainer (jamais
    nom/lore)
-   [x] `@EventHandler(ignoreCancelled = true)` partout + vérification
    explicite `isCancelled()` (sauf `PlayerInteractEvent`, dont la méthode
    est dépréciée) : un événement annulé par un autre plugin n'est jamais traité
-   [x] Dégâts appliqués une seule fois (`event.setDamage` unique, jamais
    d'appel `entity.damage()` en plus)
-   [x] Logs debug activables via `config.yml` → `debug` (déjà existant,
    réutilisé), lus en direct donc appliqués immédiatement après
    `/rpgquest reload`

## Persistance (fait)

-   [x] `DatabaseManager` SQLite asynchrone (`plugins/RPGQuest/data.db`)
-   [x] Migration de schéma versionnée (`PRAGMA user_version`), idempotente
-   [x] Tables `player_profiles`, `player_variables`, `quest_progress`,
    `quest_objective_progress`
-   [x] Cache de profils limité aux joueurs connectés, invalidé à la déconnexion
-   [x] Commande `/rpgquest profile [joueur]`

## Ressources personnalisées et récolte (fait)

-   [x] `ResourceNodeDefinition` (`resource.model`) : id namespacé, blocs
    actif/épuisé vanilla, outils requis (vide = tout outil), temps de
    respawn, table de drops pondérée (`ResourceDrop` scellé :
    `CustomItemDrop`/`VanillaItemDrop`) — invariants validés par le record
    lui-même (blocs distincts, poids/quantités positifs)
-   [x] `ResourceNodeDefinitionParser`/`ResourceNodeLoader` : même
    conception à deux phases que `QuestLoader`/`ItemLoader` (un fichier
    invalide n'empêche pas les autres, id dupliqués rejetés entre fichiers)
-   [x] `ResourceNodeRegistry` (`PluginService`) : charge les types depuis
    `plugins/RPGQuest/resource-nodes/`, un exemple (`crystal_ore`, donnant
    `refined_crystal` ou du quartz brut selon une probabilité configurable)
    généré automatiquement au premier démarrage
-   [x] `/resourcenode create|remove|inspect` (`rpgquest.admin`) : agissent
    sur le bloc visé par le joueur
-   [x] Persistance des positions par monde (`resource_nodes`, migration
    V3, SQLite asynchrone, upsert par `(world, x, y, z)`)
-   [x] `ResourceNodeService` : récolte (vérifie outil requis, dépose un
    tirage pondéré, remplace le bloc par le bloc épuisé), respawn par
    balayage périodique **sans jamais charger de chunk de force** (respawn
    différé tant que le chunk n'est pas naturellement chargé, ou que le
    monde n'existe plus)
-   [x] Anti-exploitation : `@EventHandler(ignoreCancelled = true)` +
    vérification explicite `isCancelled()`, nœud marqué épuisé de façon
    synchrone avant tout traitement (anti double-cassage simultané), bloc
    physique modifié manuellement détecté et ignoré plutôt que deviné
-   [x] Tests : récolte valide, mauvais outil, bon outil, nœud en cooldown,
    événement annulé, double cassage simultané, chunk déchargé au respawn,
    monde supprimé au respawn, respawn différé après redémarrage simulé,
    création sur type inconnu / position déjà occupée, suppression

## Recettes personnalisées, resource pack et intégration RPG complète (fait)

-   [x] `crafting.model` (`RecipeDefinition` scellé : `ShapedRecipeDefinition`/
    `ShapelessRecipeDefinition`, `RecipeResult`/`RecipeIngredient` scellés)
    — invariants validés par les records (motif cohérent, total shapeless
    ≤ 9)
-   [x] `RecipeDefinitionParser`/`RecipeLoader` (deux phases, mêmes
    conventions que les autres chargeurs)
-   [x] `YamlCraftingRegistry` (`PluginService`) : résout les objets
    personnalisés référencés via le registre d'objets, enregistre de
    vraies recettes Bukkit (`Bukkit.addRecipe`), désenregistre proprement
    au `reload()`
-   [x] Ingrédients personnalisés vérifiés par PersistentDataContainer
    (`RecipeChoice.ExactChoice` sur l'objet canonique) + garde en
    profondeur (`RecipeCraftGuardListener` sur `PrepareItemCraftEvent`,
    couvre clic simple/shift-clic/recette automatique par construction)
-   [x] 3 recettes d'exemple : `forest_blade_recipe` (façonnée),
    `refined_crystal_recipe` (sans forme), `miner_pickaxe_recipe` (sans
    forme, amélioration)
-   [x] `resource-pack/` (pack.mcmeta + modèles JSON), tâches Gradle
    `buildResourcePack`/`resourcePackSha1` (zip reproductible + SHA-1)
-   [x] `config.yml` → `resource-pack.required` ; `ResourcePackListener`
    (envoi à la connexion, gestion accepté/refusé/échec/succès, jamais de
    déconnexion automatique)
-   [x] Parcours RPG complet jouable de bout en bout : dialogue (garde) →
    quête `crystal_hunt` → combat (araignées, `spider_fang` via
    `SpiderFangDropListener`) → récolte (`crystal_ore`) → fabrication
    (`forest_blade_recipe`) → remise (`TALK_TO_NPC`) → récompense
    (`miner_pickaxe` via `COMMAND`) — couvert par
    `CrystalHuntIntegrationTest`, qui exerce le plugin réellement démarré
-   [x] Tests : validation stricte des ingrédients personnalisés (inconnu
    → recette rejetée seule), craft normal, recette inconnue, resource
    pack absent (démarrage propre), configuration resource pack invalide
    (déjà couvert + étendu pour `required`), cycle métier complet
    simulable

## Serveur local intégré au workspace (fait)

-   [x] `runServer` (déjà présent depuis l'étape 1) confirmé sur la même
    version que `paper-api` (1.21.11)
-   [x] `.gitignore` : `run/` déjà ignoré dans son intégralité (mondes,
    logs, `data.db` locale, EULA, fichiers temporaires)
-   [x] `.vscode/tasks.json` (committé) : tâches « Gradle: clean build »,
    « Gradle: test », « Gradle: runServer »
-   [x] `docs/LOCAL_SERVER.md` : premier lancement, EULA, cycle dev,
    persistance, pourquoi jamais `/reload` (vanilla)
-   [x] Vérifié réellement dans cet environnement : démarrage complet sans
    stack trace, tous les services RPGQuest actifs, ressources embarquées
    chargées sur un vrai serveur Paper (voir `docs/LOCAL_SERVER.md`)

## Outil admin d'aplatissement de terrain (fait)

-   [x] `/rpgadmin flatten <rayon> [hauteur]|confirm|cancel|undo`
    (`rpgquest.admin.world`, joueur uniquement — jamais la console)
-   [x] `AdminFlattenConfig` (`config.yml` → `admin.flatten`) : rayon max,
    forme par défaut (carré/cercle), matériaux de surface/sous-couche,
    profondeur de sous-couche, hauteur de nettoyage au-dessus, délai
    d'expiration de confirmation, blocs par tick, mondes interdits
-   [x] `FlattenService` : aperçu pur (aucune écriture) avec estimation de
    colonnes/blocs, confirmation à expiration, traitement par lots via
    tâche répétée (jamais tout d'un coup, jamais de gel), un bloc déjà
    correct n'est jamais réécrit, annulation unique (`undo`) enregistrant
    l'état d'origine de chaque bloc réellement modifié
-   [x] Tests : rayon valide/invalide/hors limite, hauteur hors limites du
    monde, estimation carré vs cercle, aperçu expiré, opération déjà
    active, annulation (aperçu en attente et opération en cours),
    traitement multi-tick vérifié, undo disponible/indisponible/refusé
    pendant une opération, configuration invalide (rayon, forme,
    matériau non-bloc, valeurs négatives)
-   [x] `docs/ADMIN_FLATTEN.md`

## Village central et safe zone (fait)

-   [x] `zone.model` : `ZoneDefinition` (cuboïde, correct par construction)
    + `ZoneFlags` (7 permissions bloquées par défaut, 5 autorisées par
    défaut dont conteneurs publics bloqué — décision documentée)
-   [x] `ZoneDefinitionParser`/`ZoneLoader` (deux phases + rejet des
    chevauchements entre fichiers, même monde uniquement)
-   [x] `ZoneRegistry` (`PluginService`) : charge/persiste
    `plugins/RPGQuest/zones/*.yml`, `create()`/`delete()` écrivent
    directement sur disque puis rechargent, index par monde pour une
    vérification bon marché par événement
-   [x] `/rpgadmin zone wand|create|delete|list|info`
    (`rpgquest.admin.world`) — sélection par outil PDC (jamais par nom)
-   [x] `ZoneProtectionListener` : PvP (mêlée + projectiles), casse/pose de
    bloc, explosions (destruction de blocs seulement, pas l'événement
    entier), feu, lave, pistons traversant la frontière, spawn hostile
    naturel, portes/boutons/leviers/conteneurs, bypass admin sur l'acteur
    direct, affichage entrée/sortie sans sondage (`PlayerMoveEvent` filtré
    aux changements de bloc)
-   [x] Zone d'exemple `central_village` générée automatiquement
-   [x] Tests : intérieur/extérieur/frontière, zone invalide, chevauchement
    (fichiers et créations successives), pas de chevauchement inter-monde,
    id dupliqué, rechargement, persistance réelle sur disque, protection
    bloc/PvP/explosion à l'intérieur/extérieur, bypass, événement déjà
    annulé laissé intact, aucune exception sur un monde sans zone
-   [x] `docs/SAFE_ZONE.md`

## MVP

-   [x] Architecture
-   [x] SQLite
-   [x] Moteur de quêtes (définitions YAML + progression des joueurs)
-   [x] Dialogues
-   [x] Journal de quêtes
-   [x] Objets personnalisés
-   [x] Armes
-   [x] Outils
-   [x] Ressources
-   [x] Craft
-   [x] Resource pack

## Plus tard

-   [ ] PNJ avancés
-   [ ] Métiers
-   [ ] Donjons
-   [ ] Boss
-   [ ] Économie
-   [ ] Factions
