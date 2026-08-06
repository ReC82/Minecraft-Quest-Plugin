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
-   `/quest list` (`rpgquest.quest`) — liste les quêtes disponibles et ton état sur chacune.
-   `/quest accept <id>` (`rpgquest.quest`) — accepte une quête (vérifie prérequis, répétabilité, doublon).
-   `/quest progress [id]` (`rpgquest.quest`) — ta progression globale, ou le détail (compteurs) d'une quête active.
-   `/quest abandon <id>` (`rpgquest.quest`) — abandonne une quête active ; elle peut être ré-acceptée ensuite.
-   `/quest complete <id>` (`rpgquest.admin`) — force la fin d'une quête (outil de test admin uniquement).
-   `/quest admin reload` (`rpgquest.admin`) — recharge les définitions de
    quêtes et `messages.yml` depuis le disque, et reconstruit l'index/les
    écouteurs de progression en conséquence. Affiche un rapport (nombre
    chargé, erreurs par fichier). Un fichier invalide n'empêche pas le
    chargement des autres.
-   `/quest admin validate` (`rpgquest.admin`) — même chargement, mais sans
    appliquer le résultat (dry-run) : utile pour vérifier avant de recharger.
-   `/dialogue open <joueur> <dialogueId>` (`rpgquest.admin`) — ouvre un
    dialogue pour un joueur en ligne (administration/tests). En jeu, un
    dialogue s'ouvre aussi en cliquant sur une entité vivante dont le nom
    personnalisé correspond à un id de dialogue chargé.
-   `/quests` (`rpgquest.quest`) — ouvre le journal de quêtes (menu
    paginé : actives, disponibles, terminées).

## Quêtes

Les définitions de quêtes sont des fichiers YAML dans
`plugins/RPGQuest/quests/` (deux exemples générés automatiquement au premier
démarrage). Voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) pour le format
complet et les règles de validation.

La progression (acceptation, étapes, compteurs, remise, répétition) est
persistée par joueur et survit aux reconnexions/redémarrages. Les messages
envoyés aux joueurs sont personnalisables dans `plugins/RPGQuest/messages.yml`.

## Dialogues

Les définitions de dialogues sont des fichiers YAML dans
`plugins/RPGQuest/dialogues/` (un exemple — un garde qui propose la
première quête — généré automatiquement au premier démarrage). Un dialogue
est un graphe de nœuds (locuteur, texte, choix) reliés par des redirections
(`next`) ; chaque choix peut avoir des conditions (visibilité) et des
actions (démarrer/avancer/remettre une quête, donner/retirer un objet,
mémoriser une variable, exécuter une commande en liste blanche, ouvrir un
autre dialogue, fermer). Les sessions de dialogue **ne sont pas persistées**
(en mémoire uniquement) : une déconnexion en cours de dialogue ferme
simplement la session, comme un inventaire vanilla. Voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) pour le format complet, la
validation (dont la détection de boucles entre dialogues) et le choix du
renderer.

## Journal de quêtes

`/quests` ouvre un inventaire paginé (onglets actives/disponibles/
terminées, icône configurable par quête via `icon:` dans le YAML). Clic
gauche sur une quête : vue détail (description, étape, progression,
récompenses, prérequis). Clic droit : suit/ne suit plus la quête — le
suivi est persistant (survit à une reconnexion) et affiche en option une
bossbar de progression pour la quête suivie. Aucun objet du menu ne peut
être déplacé, volé ou dupliqué. Voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
pour le détail (disposition, protection anti-vol, rafraîchissement
événementiel).

## Configuration

`plugins/RPGQuest/config.yml` (généré automatiquement) : `debug`, `locale`
(code ISO 639-1), `database.file`, `resource-pack` (désactivé par défaut,
requis `url`/`sha1` uniquement si `enabled: true`), `dialogue` (`renderer`
— `chat` par défaut ou `paper-dialog` — et `allowed-commands`, la liste
blanche pour l'action `RUN_SAFE_COMMAND`) et `journal` (`tracker-enabled`,
la bossbar optionnelle de la quête suivie). Validée au démarrage et à chaque
`/rpgquest reload` ; voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

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
