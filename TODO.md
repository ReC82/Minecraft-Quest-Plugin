# TODO

## Environnement (fait)

-   [x] Projet Gradle Kotlin DSL + wrapper (Gradle 9.6.1)
-   [x] Paper API 1.21.11 + `runServer` (xyz.jpenilla.run-paper 3.0.2)
-   [x] JUnit 5 + MockBukkit (mockbukkit-v1.21)
-   [x] Classe principale `RPGQuestPlugin` + `plugin.yml`
-   [x] Commandes `/rpgquest version` et `/rpgquest help`

## Persistance (fait)

-   [x] `DatabaseManager` SQLite asynchrone (`plugins/RPGQuest/data.db`)
-   [x] Migration de schéma versionnée (`PRAGMA user_version`), idempotente
-   [x] Tables `player_profiles`, `player_variables`
-   [x] Table `quest_progress` préparée (non exploitée)
-   [x] Cache de profils limité aux joueurs connectés, invalidé à la déconnexion
-   [x] Commande `/rpgquest profile [joueur]`

## MVP

-   [ ] Architecture
-   [x] SQLite
-   [ ] Moteur de quêtes
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
