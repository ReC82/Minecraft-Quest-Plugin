# RPGQuest

Plugin RPG pour Paper, sans mod client obligatoire.

## Objectifs

-   Quêtes pilotées par YAML
-   Dialogues à embranchements
-   Journal de quêtes
-   Armes, outils et ressources personnalisés
-   Recettes personnalisées
-   Resource pack optionnel envoyé par le serveur
-   Sauvegarde SQLite (asynchrone)

## Prérequis

-   Java 21
-   Paper 1.21.11
-   Gradle Wrapper (fourni, aucune installation manuelle requise)

## Développement

    gradlew.bat clean build     # Windows
    ./gradlew clean build       # Linux/macOS

    gradlew.bat test
    ./gradlew test

    gradlew.bat runServer       # démarre un serveur Paper local avec le plugin chargé
    ./gradlew runServer

`runServer` télécharge Paper 1.21.11 et démarre un serveur local dans `run/`.
Au premier lancement, il faut accepter l'EULA Mojang dans `run/eula.txt`
(`eula=true`) — uniquement pour un usage de test local.

## Commandes

-   `/rpgquest version` — affiche la version du plugin.
-   `/rpgquest help` — affiche l'aide.

## Structure du projet

Voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Règles du projet

Voir [PROJECT_RULES.md](PROJECT_RULES.md).

## Suivi des tâches

Voir [TODO.md](TODO.md).
