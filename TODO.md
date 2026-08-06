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

## Persistance (fait)

-   [x] `DatabaseManager` SQLite asynchrone (`plugins/RPGQuest/data.db`)
-   [x] Migration de schéma versionnée (`PRAGMA user_version`), idempotente
-   [x] Tables `player_profiles`, `player_variables`, `quest_progress`,
    `quest_objective_progress`
-   [x] Cache de profils limité aux joueurs connectés, invalidé à la déconnexion
-   [x] Commande `/rpgquest profile [joueur]`

## MVP

-   [x] Architecture
-   [x] SQLite
-   [x] Moteur de quêtes (définitions YAML + progression des joueurs)
-   [x] Dialogues
-   [ ] Journal de quêtes
-   [ ] Objets personnalisés
-   [ ] Armes
-   [ ] Outils
-   [ ] Ressources
-   [ ] Craft
-   [ ] Resource pack

## Plus tard

-   [ ] PNJ avancés
-   [ ] Métiers
-   [ ] Donjons
-   [ ] Boss
-   [ ] Économie
-   [ ] Factions
