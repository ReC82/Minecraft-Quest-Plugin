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

-   `/rpgquest version` — affiche la version du plugin (et un résumé de la
    configuration si `debug: true`).
-   `/rpgquest help` — affiche l'aide.
-   `/rpgquest profile [joueur]` — affiche le profil (UUID, dernier pseudo,
    dates de création/mise à jour) du joueur donné, ou le sien si omis.
-   `/rpgquest reload` (`rpgquest.admin`) — recharge `config.yml` sans
    recréer les services ni perdre de données. En cas de configuration
    invalide, un message explicite est affiché et l'ancienne configuration
    reste active.

## Configuration

`plugins/RPGQuest/config.yml` (généré automatiquement) : `debug`, `locale`
(code ISO 639-1), `database.file` et `resource-pack` (désactivé par défaut,
requis `url`/`sha1` uniquement si `enabled: true`). Validée au démarrage et
à chaque `/rpgquest reload` ; voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Persistance

Les données joueurs sont stockées dans `plugins/RPGQuest/data.db` (SQLite),
créée et migrée automatiquement au démarrage. Toutes les opérations SQL sont
asynchrones (thread dédié) ; voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Structure du projet

Voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Règles du projet

Voir [PROJECT_RULES.md](PROJECT_RULES.md).

## Suivi des tâches

Voir [TODO.md](TODO.md).
