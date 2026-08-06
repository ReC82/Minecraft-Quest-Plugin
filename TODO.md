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
    `CustomItemRegistry`, `QuestJournalUi` (toutes `PluginService`, non
    implémentées)

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

## Persistance (fait)

-   [x] `DatabaseManager` SQLite asynchrone (`plugins/RPGQuest/data.db`)
-   [x] Migration de schéma versionnée (`PRAGMA user_version`), idempotente
-   [x] Tables `player_profiles`, `player_variables`
-   [x] Table `quest_progress` préparée (non exploitée)
-   [x] Cache de profils limité aux joueurs connectés, invalidé à la déconnexion
-   [x] Commande `/rpgquest profile [joueur]`

## MVP

-   [x] Architecture
-   [x] SQLite
-   [x] Moteur de quêtes (définitions YAML ; progression des joueurs à venir)
-   [ ] Dialogues
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
