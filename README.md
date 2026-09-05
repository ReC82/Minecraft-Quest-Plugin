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
-   Économie (portefeuille, paiements), marchands PNJ et marché entre joueurs
-   Portails et téléportation entre le village et les zones d'aventure
-   Claims de terrain protégés, créés et gérés par les joueurs

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
(`eula=true`) — uniquement pour un usage de test local. Voir
[docs/LOCAL_SERVER.md](docs/LOCAL_SERVER.md) pour le cycle de développement
complet (arrêt propre, persistance entre redémarrages, tâches VS Code).

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
    dialogue s'ouvre aussi en cliquant sur un PNJ identifié via
    `/rpgadmin npc tag` (identifiant logique stable, indépendant de son nom
    affiché — voir [docs/RPGQUEST_BIBLE.md](docs/RPGQUEST_BIBLE.md) section 5)
    dont l'id correspond à un dialogue chargé.
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
-   `/rpgadmin flatten <rayon> [hauteur]` (`rpgquest.admin.world`) — aperçu
    d'aplatissement de terrain centré sur le joueur.
-   `/rpgadmin flatten confirm|cancel|undo` (`rpgquest.admin.world`) —
    exécute, annule, ou défait le dernier aplatissement. Voir
    [docs/ADMIN_FLATTEN.md](docs/ADMIN_FLATTEN.md).
-   `/rpgadmin zone wand|create|delete|list|info` (`rpgquest.admin.world`)
    — crée/gère des zones protégées (village central, safe zone...). Voir
    [docs/SAFE_ZONE.md](docs/SAFE_ZONE.md).
-   `/rpgadmin portal create|delete|list|info <id>` (`rpgquest.admin.world`)
    — crée/gère des portails (zone d'activation depuis le même outil de
    sélection `wand`). Voir [docs/TRAVEL.md](docs/TRAVEL.md).
-   `/rpgadmin portal setdestination <id> <destinationId>` (`rpgquest.admin.world`)
    — fixe la destination d'un portail à ta position actuelle.
-   `/rpgadmin mob spawn <id>` (`rpgquest.admin.world`) — invoque une
    variante de mob spécial à ta position (outil de test, contourne les
    restrictions de spawn naturel).
-   `/rpgadmin mob list|inspect <id>|reload|metrics` (`rpgquest.admin.world`)
    — liste/détaille les variantes chargées, recharge depuis le disque, ou
    affiche les métriques de spawns/capacités. Voir
    [SPECIAL_MOB_FORMAT.md](SPECIAL_MOB_FORMAT.md).
-   `/claim wand` (`rpgquest.claim`) — outil de sélection pour un claim de
    terrain (dédié, distinct de la sélection de zone).
-   `/claim create <id>` (`rpgquest.claim`) — crée un claim depuis la
    sélection courante.
-   `/claim delete|info|trust <joueur>|untrust <joueur>|flag redstone <true|false>`
    (`rpgquest.claim`) — agissent sur le claim où tu te trouves
    (`delete`/`flag` réservés au propriétaire).
-   `/claim list` (`rpgquest.claim`) — liste tes claims.
-   `/money` (`rpgquest.money`) — affiche ton solde.
-   `/money pay <joueur> <montant>` (`rpgquest.money`) — envoie des pièces à
    un joueur en ligne (transfert atomique).
-   `/money admin give|take|set <joueur> <montant>` (`rpgquest.admin`) —
    crédite, débite ou fixe le solde d'un joueur.
-   `/merchant reload|validate|list` (`rpgquest.admin`) — recharge/valide
    les marchands, ou les liste. Voir [docs/ECONOMY.md](docs/ECONOMY.md).
-   `/market` (`rpgquest.market`) — ouvre la vitrine du marché entre
    joueurs (toutes offres actives, paginée).
-   `/market sell <prix>` (`rpgquest.market`) — met en vente l'objet tenu
    en main.
-   `/market cancel <id>` (`rpgquest.market`) — annule une de tes offres et
    récupère l'objet (aussi possible en cliquant dessus dans la vitrine).
-   `/market admin list` (`rpgquest.admin`) — liste en lecture seule
    toutes les offres actives (modération).
-   `/profile` (`rpgquest.progression`) — résumé de tes niveaux RPG (une
    ligne par piste).
-   `/skills` (`rpgquest.progression`) — détail de tes compétences (XP dans
    le niveau courant / XP requise pour le suivant).
-   `/skills admin grant|set <joueur> <compétence> <montant>`
    (`rpgquest.admin`) — outil de test : accorde de l'XP (dédupliquée,
    mirroir GLOBAL, affichage) ou fixe l'XP totale directement. Voir
    [docs/PROGRESSION.md](docs/PROGRESSION.md).
-   `/backpack` (`rpgquest.backpack`) — ouvre ton backpack (aussi possible
    via l'objet dédié, clic droit).
-   `/backpack recover [numéro]` (`rpgquest.backpack`) — liste ou réclame
    les objets en attente de récupération (surplus après une réduction de
    taille).
-   `/backpack admin grant <joueur> <taille>` (`rpgquest.admin`) — accorde
    un palier (SMALL/MEDIUM/LARGE), redimensionne immédiatement en
    conservant le contenu existant.
-   `/backpack admin revoke <joueur>` (`rpgquest.admin`) — retire
    l'avantage explicite. Voir [docs/BACKPACKS.md](docs/BACKPACKS.md).
-   `/store history [joueur|uuid]` (`rpgquest.admin`) — historique des
    commandes/livraisons de la boutique web. Voir [docs/STORE.md](docs/STORE.md).

### Permissions

| Permission | Défaut | Donne accès à |
|---|---|---|
| `rpgquest.quest` | tous | `/quest list\|accept\|progress\|abandon`, `/quests` |
| `rpgquest.item` | tous | `/customitem inspect` |
| `rpgquest.money` | tous | `/money`, `/money pay` |
| `rpgquest.market` | tous | `/market`, `/market sell\|cancel` |
| `rpgquest.claim` | tous | `/claim` |
| `rpgquest.progression` | tous | `/profile`, `/skills` |
| `rpgquest.backpack` | tous | `/backpack`, `/backpack recover` |
| `rpgquest.backpack.free` | tous | palier SMALL sans avantage explicite (permission de secours) |
| `rpgquest.admin` | op | `/rpgquest reload`, `/quest admin`, `/quest complete`, `/dialogue open`, `/customitem give\|list`, `/resourcenode create\|remove\|inspect`, `/money admin`, `/merchant`, `/market admin`, `/skills admin`, `/backpack admin`, `/store history` |
| `rpgquest.admin.world` | op | `/rpgadmin flatten` (terrain), `/rpgadmin zone` (zones protégées), `/rpgadmin portal` (portails), `/rpgadmin mob` (mobs spéciaux) |

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
autre dialogue, ouvrir la vitrine d'un marchand, fermer). Les sessions de dialogue **ne sont pas persistées**
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

## Zones protégées

`/rpgadmin zone` gère des zones cuboïdes protégées (PvP, casse/pose,
explosions, feu, lave, pistons traversant la frontière et spawn hostile
bloqués par défaut ; portes/boutons/leviers/PNJ autorisés par défaut,
conteneurs publics bloqués). Un exemple (`central_village`) est généré
automatiquement. Voir [docs/SAFE_ZONE.md](docs/SAFE_ZONE.md) pour le détail
complet et la liste des permissions configurables.

## Économie et marchands

Chaque joueur a un portefeuille (solde entier, aucune virgule), persisté en
SQLite et modifié uniquement via des opérations atomiques (débit, crédit,
paiement) — jamais de solde négatif, jamais de paiement à moitié appliqué.
`/money`/`/money pay` sont les commandes joueur ; `/money admin` est la
seule façon d'introduire de la monnaie hors marchand.

Les marchands PNJ sont des vitrines YAML dans `plugins/RPGQuest/merchants/`
(un exemple généré automatiquement : `village_merchant`), qui vendent et/ou
achètent des objets vanilla ou personnalisés, avec des conditions d'accès
optionnelles (permission, quête, niveau). Un marchand ne s'ouvre **que**
depuis une action de dialogue (`OPEN_MERCHANT`) — pas de clic direct sur un
PNJ, pour ne pas dupliquer le mécanisme d'identification déjà utilisé par
les quêtes/dialogues. Voir [docs/ECONOMY.md](docs/ECONOMY.md) et
[MERCHANT_FORMAT.md](MERCHANT_FORMAT.md) pour le détail complet.

`/market` ouvre en plus un marché **entre joueurs** : n'importe qui peut
mettre en vente l'objet tenu en main (`/market sell <prix>`), l'objet
complet (méta, PDC compris) est mis en dépôt jusqu'à achat ou annulation.
Cliquer sur l'offre d'un autre joueur l'achète (paiement + objet échangés
atomiquement, vendeur crédité même hors ligne) ; cliquer sur sa propre
offre l'annule et restitue l'objet. Voir [docs/ECONOMY.md](docs/ECONOMY.md)
pour le détail complet (garanties anti-duplication comprises).

## Portails et téléportation

`/rpgadmin portal create <id>` crée un portail (zone d'activation cuboïde,
depuis la même sélection `wand` que les zones protégées) ; `/rpgadmin
portal setdestination <id> <destinationId>` fixe sa destination à la
position exacte de l'administrateur. Un portail peut exiger permission,
quête, niveau et/ou un coût en pièces (débité uniquement si la
téléportation réussit). Entrer dans sa zone démarre une canalisation à
délai (actionbar de progression), annulée si le joueur bouge, subit des
dégâts ou se déconnecte. À la fin, la destination est vérifiée sûre (jamais
le vide, la lave ou un bloc solide) avant toute téléportation — sinon,
aucun débit, aucun déplacement, juste un message d'erreur. Le cooldown par
joueur/portail est persisté et survit à une reconnexion. Voir
[docs/TRAVEL.md](docs/TRAVEL.md) pour le détail complet.

## Claims de terrain

`/claim wand` puis `/claim create <id>` réclame un terrain cuboïde
(propriétaire = ton UUID, jamais ton pseudo). Refusé s'il chevauche un
claim existant, une zone protégée, se trouve trop près d'un portail, ou
dépasse la taille/le nombre maximal autorisés. Protège blocs, conteneurs,
animaux, armor stands et pistons traversant la frontière pour tout
non-membre ; seule la redstone (boutons, leviers, portes, dalles de
pression) est configurable (`/claim flag redstone <true|false>`).
`/claim trust`/`untrust <joueur>` gère les membres de confiance,
`/claim delete`/`info` agissent sur le claim où tu te trouves. Aucun
avantage payant à cette étape. Voir [docs/CLAIMS.md](docs/CLAIMS.md) pour
le détail complet.

## Mobs spéciaux

Des variantes de mobs entièrement vanilla, pilotées par YAML dans
`plugins/RPGQuest/mobs/*.yml` (quatre exemples générés automatiquement :
`red_creeper`, `golden_creeper`, `creeper_pig`, `splitting_zombie`). Un spawn
naturel qui correspond au type d'entité, aux mondes/biomes/zones autorisés et
qui n'a pas atteint sa limite de population peut être upgradé en variante
(attributs, nom MiniMessage, particule/son, capacités, table de drops).
Identification uniquement par PersistentDataContainer, jamais par le nom
affiché. Trois capacités : explosion renforcée, cochon explosif agressif
(balayage périodique, pas de goal d'IA vanilla disponible via l'API), et
zombie qui se divise à chaque coup non mortel (profondeur et nombre d'enfants
bornés pour empêcher toute croissance incontrôlée). Voir
[SPECIAL_MOB_FORMAT.md](SPECIAL_MOB_FORMAT.md) pour le détail complet.

## Progression RPG

Six pistes de progression indépendantes de l'XP vanilla (`GLOBAL`,
`COMBAT`, `MINING`, `FARMING`, `FISHING`, `EXPLORATION`) : niveaux et XP
suivent une courbe configurable, alimentées par la mort de mobs, le
minage, la récolte de cultures mûres, la pêche, la découverte de zones et
la fin de quêtes. Protections anti-farm (blocs posés par un joueur, mobs
de spawner, descendants de division, cultures non mûres, répétition
excessive) et déduplication garantissent qu'aucune action ne récompense
deux fois. `/profile` et `/skills` affichent la progression du joueur ;
l'XP vanilla reste intacte pour les enchantements. Voir
[docs/PROGRESSION.md](docs/PROGRESSION.md) pour le modèle d'équilibrage
complet.

## Backpacks

Un inventaire virtuel persistant par joueur, en trois paliers configurables
(Small/Medium/Large), obtenu via un objet dédié ou `/backpack`, sauvegardé
à la fermeture/déconnexion/arrêt. Anti-abus complet (aucune imbrication de
backpack, objets explicitement interdits configurables, jamais plus d'une
instance ouverte à la fois, aucun vecteur de vol par hopper — inventaire
purement virtuel). Un changement de palier conserve tous les objets
(upgrade) et bascule le surplus dans une boîte de récupération plutôt que
de le perdre (downgrade). Premier consommateur concret d'une interface
`EntitlementService` générique, prête pour de futurs avantages. Voir
[docs/BACKPACKS.md](docs/BACKPACKS.md) pour le détail complet.

## Portail web

Un module séparé du plugin (`web-api/`, projet Gradle indépendant, aucune
dépendance vers Paper ni accès direct à `data.db`) expose une API
authentifiée en lecture seule (statut, joueurs, classements, catalogue,
annonces) et un site public minimal (accueil, statut, classements, wiki).
Le plugin exporte périodiquement un instantané JSON
(`web-export:` dans `config.yml`, désactivé par défaut) que ce module lit
seul — jamais la base SQLite directement. Mode dégradé automatique si le
serveur Minecraft est arrêté ou n'a jamais exporté. Ni paiement, ni
connexion joueur, ni modification de données à cette étape. Voir
[docs/WEB_API.md](docs/WEB_API.md) pour le détail complet (déploiement,
authentification, endpoints).

## Boutique web

Vend des avantages de **confort uniquement** (backpacks, pass VIP de test,
cosmétiques) via le portail web — jamais d'avantage compétitif (voir
[docs/STORE.md](docs/STORE.md), politique pay-to-convenience). Paiement en
mode sandbox (aucune carte bancaire jamais demandée ni stockée) ; les
commandes/livraisons sont idempotentes (une livraison ne peut jamais être
appliquée deux fois) et le serveur de jeu — jamais web-api — reste
l'autorité finale pour tout avantage accordé. `/store history [joueur|uuid]`
(`rpgquest.admin`) consulte l'historique. Désactivé par défaut
(`store.enabled: false`). Voir [docs/STORE.md](docs/STORE.md) pour le
détail complet (gestion du remboursement, joueur hors ligne, upgrade,
produit déjà possédé...).

## Mod client (prototype)

Un prototype de mod client Fabric, **entièrement séparé** du plugin
(`client-mod/`, projet Gradle indépendant avec son propre wrapper — jamais
un sous-module du build racine, jamais empaqueté dans le jar Paper).
Ajoute un vrai bloc/objet de démonstration, un indicateur cosmétique de
variante de mob et un HUD de statut. Le serveur reste l'autorité pour la
progression, les drops, l'économie, les droits et les achats ; le mod ne
peut jamais s'auto-déclarer posséder un objet ou avoir terminé une action.
Détection de compatibilité par handshake (`client-mod:` dans
`config.yml`, client vanilla autorisé avec repli par défaut). Voir
[docs/CLIENT_MOD.md](docs/CLIENT_MOD.md) pour le choix Fabric/NeoForge, le
protocole complet et l'installation.

## Configuration

`plugins/RPGQuest/config.yml` (généré automatiquement) : `debug`, `locale`
(code ISO 639-1), `database.file`, `resource-pack` (désactivé par défaut,
requis `url`/`sha1` uniquement si `enabled: true`, voir
[RESOURCE_PACK.md](RESOURCE_PACK.md)), `dialogue` (`renderer`
— `paper-dialog` par défaut, alternative `chat` — et `allowed-commands`, la
liste blanche pour l'action `RUN_SAFE_COMMAND`), `journal` (`tracker-enabled`,
la bossbar optionnelle de la quête suivie), `web-export` (export
périodique pour le [portail web](docs/WEB_API.md), désactivé par défaut),
`store` (sondage des livraisons de la [boutique web](docs/STORE.md),
désactivé par défaut) et `client-mod` (détection du
[mod client prototype](docs/CLIENT_MOD.md), client vanilla autorisé par
défaut). Validée au démarrage et à chaque `/rpgquest reload` ; voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Persistance

Les données joueurs — et depuis l'étape des ressources personnalisées, les
positions des nœuds de ressource — sont stockées dans
`plugins/RPGQuest/data.db` (SQLite), créée et migrée automatiquement au
démarrage. Toutes les opérations SQL sont asynchrones (thread dédié) ; voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Sauvegarde et restauration

Tout l'état persistant tient dans le dossier `plugins/RPGQuest/` :

-   `data.db` (SQLite) — profils joueurs, progression de quêtes, variables,
    positions des nœuds de ressource, portefeuilles, transactions, offres
    du marché entre joueurs (objets en dépôt), cooldowns de portails et
    claims de terrain (avec leurs membres). Le plugin verrouille le fichier
    tant qu'il est démarré ; sauvegarder à chaud (serveur en marche) reste
    possible avec les outils de sauvegarde SQLite habituels (ex. `.backup`),
    ou plus simplement arrêter le serveur avant de copier le fichier.
-   `config.yml`, `messages.yml`, `quests/`, `dialogues/`, `items/`,
    `resource-nodes/`, `recipes/`, `merchants/`, `portals/`,
    `destinations/` — contenu YAML éditable, à sauvegarder comme n'importe
    quel fichier de configuration.

Pour restaurer : arrêter le serveur, remplacer le dossier
`plugins/RPGQuest/` (ou seulement `data.db` pour ne restaurer que la
progression des joueurs) par la sauvegarde, puis redémarrer. Les migrations
de schéma (`PRAGMA user_version`) sont idempotentes : une base plus ancienne
est mise à niveau automatiquement au démarrage.

## Structure du projet

Voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Règles du projet

Voir [PROJECT_RULES.md](PROJECT_RULES.md).

## Bible technique

[docs/RPGQUEST_BIBLE.md](docs/RPGQUEST_BIBLE.md) — référence exhaustive de
toutes les commandes et procédures du projet (installation, admin, quêtes,
dialogues, PNJ, portails, mondes, claims, items, économie, tests,
dépannage...), vérifiée contre le code. Complète le
[docs-site](docs-site/index.html) (navigation visuelle).

## Déploiement VeryGames

Voir [docs/deployment/VERYGAMES.md](docs/deployment/VERYGAMES.md) pour la
procédure de déploiement (installation neuve, mise à jour du JAR,
migration de données) et
[docs/deployment/SERVER_CHANGELOG.md](docs/deployment/SERVER_CHANGELOG.md)
pour l'historique daté des actions serveur nécessitées par chaque
changement.

## Suivi des tâches

Voir [TODO.md](TODO.md).

## Prochaines étapes

Le MVP (architecture, SQLite, quêtes, dialogues, journal, objets
personnalisés, armes/outils, ressources, craft, resource pack, zones
protégées, économie, marchands PNJ, marché entre joueurs, portails et
téléportation, claims de terrain) est complet, ainsi que mobs spéciaux
vanilla, XP RPG, backpacks, le portail web read-only, la boutique web
sandbox et le prototype de mod client. Fonctionnalités envisagées ensuite
(voir aussi [TODO.md](TODO.md)) : prestataire de paiement réel, connexion
joueur sur le portail web, contenu client réel synchronisé serveur (au-delà
du prototype), PNJ avancés (Citizens ou équivalent, optionnel), métiers,
donjons, boss, factions.
