# Permissions RPGQuest — matrice et rôles recommandés (issue #27)

État au 2026-10-13, relevé dans le code (`permission.RpgPermissions`, `plugin.yml`) et vérifié par
`RpgPermissionsMatrixTest`. Compatible Bukkit/Paper ; LuckPerms reste optionnel (le pont PlugAdmin
#200 l'utilise s'il est présent).

## Nœuds

| Nœud | Défaut | Accorde | N'accorde pas |
|---|---|---|---|
| `rpgquest.build.hub.<monde>` | non | poser/casser dans **ce** Hub (nom du monde, ex. `rpgquest.build.hub.world_hub`) — permanent pour un compte **non OP** ; pour un OP, seulement en mode bypass (#35) | les autres Hubs, les claims, le monde Claims, les zones |
| `rpgquest.build.hub.*` | non | tous les Hubs | claims, monde Claims, zones |
| `rpgquest.build.wild` | non | **sans effet** aujourd'hui : le Wild n'a pas de protection de construction | aucun bypass |
| `rpgquest.bypass.claim` | non | modifier dans le claim d'un autre joueur | construction Hub, accès au monde Claims |
| `rpgquest.bypass.claimworld` | non | entrer et construire dans le monde Claims sans les conditions joueur | claims d'autres joueurs |
| `rpgquest.bypass.zone` | non | contourner les zones protégées | claims, Hub |
| `rpgquest.admin.command` | non | **entrer** dans `/rpgadmin`, sans aucune branche | — |
| `rpgquest.admin.npc` / `.npc.tag` / `.npc.untag` / `.npc.info` | non | les actions PNJ de `/rpgadmin npc` (toutes, ou une seule) | les autres branches de `/rpgadmin` |
| `rpgquest.admin.testing` | non | **paquet testeur (#64)** : entrer dans `/rpgadmin` + branches `player`, `quest`, `story`, **sans être OP** | construction au Hub, tout bypass, session `/rpgadmin bypass`, les autres branches, `player variable set` (exige `rpgquest.admin.debug`) |
| `rpgquest.admin.player` / `.quest` / `.story` | non | une seule branche de test (#64) : `player resetnew\|resetfull\|variable get`, `quest start\|complete\|reset`, `story …` — à combiner avec `rpgquest.admin.command` | les autres branches, tout bypass |
| `rpgquest.admin.buildsite` | non | emplacements de construction | — |
| `rpgquest.admin.city` | non | villes (outil, centre, nom) | — |
| `rpgquest.admin.travel.maintenance` | non | casser les blocs du réseau de voyage **après** `/rpgadmin travel maintenance on` | — |
| `rpgquest.admin.bypass` | **OP** | **ouvrir** une session de mode bypass (`/rpgadmin bypass on`, #35) — n'accorde aucun contournement à elle seule | tout bypass non détenu |
| `rpgquest.admin.hub.combat` | **OP** | frapper les entités protégées du Hub, **uniquement en mode bypass** (#35) | construction |
| `rpgquest.admin.hub.resources` | **OP** | commercer avec un villageois vanilla, ouvrir un coffre / tonneau / hopper et utiliser un poste de travail fermé du Hub (établi, four, enclume…, hors exception), **uniquement en mode bypass** (#675) | construction |
| `rpgquest.admin.debug` | **OP** | outils de débogage | — |
| `rpgquest.admin` | **OP** | commandes d'administration (`/rpgquest reload`, `/quest admin`…) | — |
| `rpgquest.admin.world` | **OP** | **ancien nœud global** : construction de tous les Hubs et tous les bypass (**uniquement en mode bypass**, #35 — permet aussi d'ouvrir la session), branches historiques de `/rpgadmin` | — |
| `rpgquest.quest`, `.item`, `.money`, `.market`, `.claim`, `.progression`, `.backpack`… | tous | commandes joueur | — |

PlugAdmin (rôles du panel) et OP Minecraft sont indépendants : changer l'un ne change pas l'autre.

## Mode bypass administrateur (#35, #321)

Être OP ne contourne plus **aucune règle de jeu**. Les nœuds `rpgquest.bypass.*`,
`rpgquest.admin.hub.combat`, `rpgquest.admin.hub.resources` et la partie « bypass » de `rpgquest.admin.world` ne comptent que pendant
une **session explicite** :

| Commande | Effet |
|---|---|
| `/rpgadmin bypass on` | ouvre la session (exige `rpgquest.admin.bypass`) ; barre d'action permanente « ⚠ MODE ADMIN / BYPASS ACTIF » |
| `/rpgadmin bypass off` | ferme la session : règles normales immédiatement |
| `/rpgadmin bypass status` | état courant (et durée restante si une durée maximale est réglée) |

- **OFF par défaut**, jamais en base : fermé à la déconnexion, au redémarrage, au rechargement.
- Le mode **n'ajoute aucun droit** : il arme seulement les nœuds de bypass que le compte détient
  déjà (un compte avec `rpgquest.bypass.zone` seul ne contourne que les zones).
- Les **droits d'administration** (commandes, outils) n'en dépendent pas et restent inchangés pour un OP.
- **Construction au Hub** (correctif du 23/10 : un OP bypass OFF cassait encore un bloc du Hub, parce
  que l'ombrelle `rpgquest.admin.world`, défaut OP, donnait la construction en permanence) :
  - un compte **non OP** portant `rpgquest.build.hub.<monde>` ou `rpgquest.build.hub.*` (attribué
    explicitement, par LuckPerms ou un groupe) construit **en permanence**, dans son seul périmètre ;
  - un **OP** ne construit au Hub **que bypass ON** (`/rpgadmin bypass on`), même s'il porte aussi un
    nœud builder : pour un OP, Bukkit (nœud non déclaré = défaut OP) et LuckPerms (`enable-ops`)
    répondent « oui » à tout, sans distinguer un octroi explicite ; l'ombrelle seule (compte non OP)
    suit la même règle et suffit à ouvrir la session ;
  - même règle pour la pose, les seaux, le feu, les cadres et tableaux (poser, casser, frapper,
    tourner, garnir), les porte-armures, la réécriture de panneaux, le piétinement de terre labourée
    et la poudre d'os ; les explosions ne cassent rien au Hub, pour personne ;
  - contrôlé à chaque action : `/rpgadmin bypass off` refuse immédiatement, sans reconnexion.
- Réglages globaux dans PlugAdmin > Paramètres généraux > Administration : mode proposé ou désactivé
  pour tous (désactiver coupe les sessions en cours, l'administrateur est prévenu), durée maximale
  (0 = jusqu'à la déconnexion). Aucun de ces réglages n'active le mode pour quelqu'un.
- Chaque activation, désactivation et fin automatique est journalisée (`[admin-bypass]`), et chaque
  entrée Claims accordée par le mode aussi (`[claims-access] … mode bypass administrateur`).
- Hors périmètre : le mode maintenance voyage (`/rpgadmin travel maintenance`, #191) reste un mode
  distinct, déjà explicite et limité à 5 minutes.

## Téléportation par commande : réservée à l'administration (#829)

Un joueur ordinaire (non OP) n'a **aucune** commande de téléportation : il voyage uniquement par les
mécaniques en jeu, sans rien taper — portails de monde (`hub_to_wild`, `hub_to_claims`), portails
historiques, bornes du Réseau de voyage (catégorie « Mon claim » comprise), Waystones du Wild,
Pierre de retour / Rune de rappel, bouton de secours du Hub.

Audit des droits effectifs sur DEV (25/10/2026, lecture seule : copie de la base LuckPerms, JAR des
plugins installés) :

| Commande | Nœud requis | Défaut | Joueur non OP sur DEV |
|---|---|---|---|
| `/tp`, `/teleport`, `/minecraft:tp` | `minecraft.command.teleport` | OP | refusé |
| `/mv tp`, `/mv spawn` (Multiverse-Core 5.7.3) | `multiverse.teleport.*`, `multiverse.core.spawn.*` | OP | refusé |
| `/npc tp`, `/npc tphere`, `/npc tpto` (Citizens) | `citizens.npc.tp*` | OP | refusé |
| Navigation WorldEdit (`/jumpto`, `/thru`, `/up`, `/unstuck`, boussole) | `worldedit.navigation.*` (non déclarés) | OP | refusé |
| `/rpgadmin world tp`, `/rpgadmin spawn tp`, `/rpgadmin waystone tp` | `rpgquest.admin.world` | OP | refusé |
| `/claim admin tp`, `/claim admin sendhome` | `rpgquest.admin.world` | OP | refusé |

- LuckPerms DEV (`storage-method: h2`, `enable-ops: true`, `auto-op: false`) : le groupe `default` ne
  porte **aucun nœud** ; seul le groupe PlugAdmin « Testeur RPGQuest » (`rpgquest.admin.testing`) est
  attribué. Un joueur ordinaire n'a donc que les défauts « tous » des `plugin.yml`, dont aucun ne
  téléporte. Le paquet testeur n'ouvre aucune commande de téléportation non plus.
- Un **OP** détient toutes ces commandes (défaut OP, et `enable-ops` de LuckPerms). Les gardes D1/D2
  restent une **défense en profondeur** contre un droit accordé par erreur ou un appel d'un autre plugin :
  vers le claim d'autrui, une téléportation commande/plugin/spectateur est refusée sans
  `/rpgadmin bypass on`, et `/rpgadmin world tp claims` / `/claim admin tp` exigent ce mode.
- Contrôle automatique : `TeleportCommandsNonOpAuditTest` exécute chaque commande RPGQuest de
  téléportation avec un profil non OP vide (celui de DEV) puis avec le paquet testeur : refus explicite,
  personne ne bouge (ni le joueur, ni la cible d'un `sendhome`) ; aucun nœud `rpgquest.admin*` ou
  `rpgquest.bypass*` n'est accordé par défaut. Les commandes des autres plugins ne sont pas exécutables
  en test : leur défaut OP est relevé dans leurs JAR (ci-dessus).
- **Ne jamais** accorder `minecraft.command.teleport`, `multiverse.teleport.*`, `citizens.npc.tp*`,
  `worldedit.navigation.*` ou `rpgquest.admin.world` à un groupe de joueurs.

Pour refaire l'audit : `lp group default permission info` (en jeu ; la sortie de LuckPerms ne remonte
pas par RCON), ou PlugAdmin > Joueurs > « Lire ses droits en jeu » pour un compte précis.

## Rôles recommandés (groupes LuckPerms, exemples)

```
/lp creategroup builder-hub
/lp group builder-hub permission set rpgquest.build.hub.world_hub true
/lp creategroup npc-editor
/lp group npc-editor permission set rpgquest.admin.command true
/lp group npc-editor permission set rpgquest.admin.npc true
/lp creategroup city-editor
/lp group city-editor permission set rpgquest.admin.command true
/lp group city-editor permission set rpgquest.admin.city true
```

### Testeur non-OP (#64)

Pour enchaîner `/rpgadmin player resetnew …`, `/rpgadmin quest …`, `/rpgadmin story …` et un test
« joueur réel » **sans basculer OP / dé-OP** :

```
/lp user <compte> permission set rpgquest.admin.testing true     # octroi
/lp user <compte> permission unset rpgquest.admin.testing        # révocation, immédiate
/lp user <compte> permission set rpgquest.admin.debug true       # facultatif : player variable set
```

Ou depuis PlugAdmin > Groupes : famille « Raccourcis de test /rpgadmin (sans OP) », puis rattacher
le compte au groupe. Le compte doit rester **non-OP** : un OP détient l'ombrelle par défaut et ne
teste donc plus en conditions joueur pour la construction au Hub. Le paquet n'est consulté par
**aucune** protection (Hub, Wild, claims, zones, waypoints) : le testeur y est un joueur ordinaire.
LuckPerms persiste l'attribution (reconnexion, redémarrage). `rpgquest.admin.debug` reste hors des
groupes du pont et s'accorde à la main. Aucune allowlist UUID dans le plugin : LuckPerms est déjà
installé sur DEV et identifie les comptes par UUID.

Le gamemode créatif n'est pas géré par RPGQuest : `minecraft.command.gamemode` via LuckPerms, en
sachant qu'il s'applique partout. Les groupes peuvent aussi se gérer depuis PlugAdmin > Groupes (#200).

## Fait / non fait / à tester

| Point | État |
|---|---|
| Construction limitée par Hub, sans droit sur les claims | **fait** (code + test automatique) |
| Bypass séparés claims / monde Claims / zones | **fait** |
| `/rpgadmin` : entrée séparée des branches, PNJ par action | **fait** |
| Pont PlugAdmin → LuckPerms (groupes, révocation ciblée) | **fait** (#200) |
| OP sans bypass implicite | **fait** (#35) : mode bypass explicite, OFF par défaut, visible ; tests automatiques OP/DEOP × ON/OFF × accès Claims — **validation en jeu PENDING** |
| `/rpgadmin player\|quest\|story` sans OP ni ombrelle (testeur) | **fait** (#64) : `rpgquest.admin.testing` ou nœud par branche ; tests automatiques — **validation en jeu PENDING** (TC-389) |
| Quêtes, dialogues, marchands, spawn en commande (`/quest admin`, `/merchant`…) : droits séparés | **non fait** : sous `rpgquest.admin` |
| `rpgquest.build.wild` effectif | **non fait** (aucune protection Wild à contourner) |
| Hub identifié par numéro (`hub.0`) | **non fait** : nom de monde (`hub.world_hub`) |
| Validation en jeu des comptes OP / non OP / builder | **à tester** (protocole ci-dessous) |

## Protocole de validation en jeu (sans toucher aux droits du propriétaire)

Avec un **second compte de test** (jamais le compte du propriétaire) :

1. Sans droit : casser un bloc au Hub → refusé ; entrer dans Claims sans `CLAIM_TIER_1` → refusé.
2. `/lp user <test> permission set rpgquest.build.hub.world_hub true` : casser au Hub → autorisé ;
   casser dans un claim d'un autre joueur → refusé ; entrer dans Claims sans palier → refusé.
3. Retirer le droit (`permission unset`) : casser au Hub → refusé **immédiatement**, sans reconnexion.
4. `rpgquest.admin.command` + `rpgquest.admin.npc.info` : `/rpgadmin npc info` → répond ;
   `/rpgadmin npc tag` → refusé ; `/rpgadmin ville outil` → refusé.
5. Compte OP, mode bypass OFF : mêmes résultats qu'un joueur normal pour 1. (Hub : la construction
   reste permise, c'est un droit de construction ; les entités du Hub restent protégées) ;
   `/rpgadmin` reste utilisable.
6. Compte OP, `/rpgadmin bypass on` : barre d'action visible ; entrée Claims sans palier autorisée
   (Pierre de retour reçue) ; `/rpgadmin bypass off` → refusé de nouveau. Se déconnecter avec le mode
   actif puis revenir → `/rpgadmin bypass status` dit « inactif ».

**Rappel pour toute validation** : un test « comme un joueur » se fait **mode bypass OFF**, même avec
un compte OP — vérifier l'absence de la barre d'action rouge avant de conclure.
