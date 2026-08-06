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

## Installation (serveur de production)

1.  Construire le jar : `gradlew.bat build` / `./gradlew build` — le jar se
    trouve dans `build/libs/RPGQuest-<version>.jar`.
2.  Copier ce jar dans le dossier `plugins/` d'un serveur Paper 1.21.11.
3.  Démarrer le serveur une première fois : `config.yml`, `messages.yml` et
    les dossiers `quests/`, `dialogues/`, `items/`, `resource-nodes/`,
    `recipes/` sont générés automatiquement avec leurs exemples.
4.  Le driver SQLite (`org.xerial:sqlite-jdbc`) est résolu automatiquement
    au démarrage par le `LibraryLoader` de Paper — aucune installation
    manuelle de dépendance requise.
5.  (Optionnel) Configurer un resource pack — voir
    [RESOURCE_PACK.md](RESOURCE_PACK.md).

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
-   `/customitem give <joueur> <id> [quantité]` (`rpgquest.admin`) — donne
    un objet personnalisé.
-   `/customitem list` (`rpgquest.admin`) — liste les objets personnalisés chargés.
-   `/customitem inspect` (`rpgquest.item`) — identifie l'objet tenu en main.
-   `/resourcenode create <typeId>` (`rpgquest.admin`) — place un nœud de
    ressource récoltable sur le bloc visé (portée 6 blocs).
-   `/resourcenode remove` (`rpgquest.admin`) — retire le suivi du nœud sur
    le bloc visé (ne touche pas au bloc physique).
-   `/resourcenode inspect` (`rpgquest.admin`) — affiche le type et l'état
    (actif / épuisé, temps de respawn restant) du nœud sur le bloc visé.

### Permissions

| Permission | Défaut | Donne accès à |
|---|---|---|
| `rpgquest.quest` | tous | `/quest list\|accept\|progress\|abandon`, `/quests` |
| `rpgquest.item` | tous | `/customitem inspect` |
| `rpgquest.admin` | op | `/rpgquest reload`, `/quest admin`, `/quest complete`, `/dialogue open`, `/customitem give\|list`, `/resourcenode create\|remove\|inspect` |

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

## Objets personnalisés

Les définitions d'objets (armes, outils, ressources, objets de quête) sont
des fichiers YAML dans `plugins/RPGQuest/items/` (quatre exemples générés
automatiquement : `forest_blade`, `miner_pickaxe`, `spider_fang`,
`refined_crystal`). Chaque objet définit un matériau vanilla de base, un
nom/lore MiniMessage, une rareté, une empilabilité et une durabilité
propres, des attributs et enchantements, des tags de gameplay, et des
restrictions de fabrication (`craftable`/`required-permissions`, données
descriptives — non appliquées lors de la fabrication elle-même ; voir
[RECIPE_FORMAT.md](RECIPE_FORMAT.md) pour les vraies recettes). L'identification est **exclusivement**
portée par le PersistentDataContainer de l'objet : un objet vanilla
renommé pour en imiter un n'est jamais reconnu. Voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) pour le format complet.

### Comportements d'armes et d'outils

Une définition d'arme peut déclarer une section `combat:` (dégâts et
vitesse d'attaque en bonus, chance de critique, effet conditionnel à
cooldown, message/particule) ; une définition d'outil peut déclarer une
section `tool:` (bonus de vitesse de minage, blocs autorisés, coût de
durabilité personnalisé, bonus de récolte, capacité spéciale au clic
droit). Les cooldowns sont indexés par joueur et par capacité, jamais
partagés entre joueurs. Aucun comportement ne s'applique à un objet
contrefait, en main secondaire, ni à un événement déjà annulé par un
autre plugin. Voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) pour le
détail complet et les garanties de sécurité.

## Ressources personnalisées et récolte

Les **types** de nœuds de ressource (ex. `crystal_ore`) sont des fichiers
YAML dans `plugins/RPGQuest/resource-nodes/` (un exemple généré
automatiquement). Un type déclare un bloc vanilla actif et un bloc vanilla
temporaire affiché une fois épuisé (aucun bloc client personnalisé requis),
la liste des outils autorisés (vide = n'importe quel outil), un temps de
respawn et une table de drops pondérée (objets personnalisés et/ou
matériaux vanilla, un seul tirage par récolte).

Les **positions** de nœuds, elles, sont créées en jeu via
`/resourcenode create <typeId>` sur le bloc visé et persistées par monde
(SQLite, survit à un redémarrage). Récolter un nœud actif avec le bon outil
pose le bloc épuisé, dépose le butin tiré au sort, puis respawn
automatiquement une fois le délai écoulé — uniquement si le monde existe
encore et que le chunk est chargé naturellement (aucun chargement forcé de
chunk). Voir [RESOURCE_NODE_FORMAT.md](RESOURCE_NODE_FORMAT.md) pour le format
complet, et [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) pour le détail
d'implémentation et les garanties anti-exploitation (double cassage
simultané, mauvais outil, cooldown, monde/chunk absent).

## Recettes personnalisées

Les définitions de recettes (façonnées ou non) sont des fichiers YAML dans
`plugins/RPGQuest/recipes/` (trois exemples générés automatiquement :
`forest_blade_recipe`, `refined_crystal_recipe`, `miner_pickaxe_recipe`).
Chargées au démarrage, elles s'enregistrent comme de vraies recettes Bukkit
(visibles dans le livre de recettes vanilla). Un ingrédient personnalisé
n'est jamais satisfait par un objet vanilla qui l'imite (vérification par
PersistentDataContainer, doublée d'un contrôle à chaque préparation de
fabrication — clic simple, shift-clic ou recette automatique déclenchent
tous la même vérification). Voir [RECIPE_FORMAT.md](RECIPE_FORMAT.md) pour
le format complet.

## Configuration

`plugins/RPGQuest/config.yml` (généré automatiquement) : `debug`, `locale`
(code ISO 639-1), `database.file`, `resource-pack` (désactivé par défaut,
requis `url`/`sha1` uniquement si `enabled: true`, voir
[RESOURCE_PACK.md](RESOURCE_PACK.md)), `dialogue` (`renderer`
— `chat` par défaut ou `paper-dialog` — et `allowed-commands`, la liste
blanche pour l'action `RUN_SAFE_COMMAND`) et `journal` (`tracker-enabled`,
la bossbar optionnelle de la quête suivie). Validée au démarrage et à chaque
`/rpgquest reload` ; voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Persistance

Les données joueurs — et depuis l'étape des ressources personnalisées, les
positions des nœuds de ressource — sont stockées dans
`plugins/RPGQuest/data.db` (SQLite), créée et migrée automatiquement au
démarrage. Toutes les opérations SQL sont asynchrones (thread dédié) ; voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Sauvegarde et restauration

Tout l'état persistant tient dans le dossier `plugins/RPGQuest/` :

-   `data.db` (SQLite) — profils joueurs, progression de quêtes, variables,
    positions des nœuds de ressource. Le plugin verrouille le fichier tant
    qu'il est démarré ; sauvegarder à chaud (serveur en marche) reste
    possible avec les outils de sauvegarde SQLite habituels (ex. `.backup`),
    ou plus simplement arrêter le serveur avant de copier le fichier.
-   `config.yml`, `messages.yml`, `quests/`, `dialogues/`, `items/`,
    `resource-nodes/`, `recipes/` — contenu YAML éditable, à sauvegarder
    comme n'importe quel fichier de configuration.

Pour restaurer : arrêter le serveur, remplacer le dossier
`plugins/RPGQuest/` (ou seulement `data.db` pour ne restaurer que la
progression des joueurs) par la sauvegarde, puis redémarrer. Les migrations
de schéma (`PRAGMA user_version`) sont idempotentes : une base plus ancienne
est mise à niveau automatiquement au démarrage.

## Structure du projet

Voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Règles du projet

Voir [PROJECT_RULES.md](PROJECT_RULES.md).

## Suivi des tâches

Voir [TODO.md](TODO.md).

## Prochaines étapes

Le MVP (architecture, SQLite, quêtes, dialogues, journal, objets
personnalisés, armes/outils, ressources, craft, resource pack) est
complet. Fonctionnalités envisagées ensuite (voir aussi
[TODO.md](TODO.md)) : PNJ avancés (Citizens ou équivalent, optionnel),
métiers, donjons, boss, économie, factions.
