# Permissions RPGQuest — matrice et rôles recommandés (issue #27)

État au 2026-10-13, relevé dans le code (`permission.RpgPermissions`, `plugin.yml`) et vérifié par
`RpgPermissionsMatrixTest`. Compatible Bukkit/Paper ; LuckPerms reste optionnel (le pont PlugAdmin
#200 l'utilise s'il est présent).

## Nœuds

| Nœud | Défaut | Accorde | N'accorde pas |
|---|---|---|---|
| `rpgquest.build.hub.<monde>` | non | poser/casser dans **ce** Hub (nom du monde, ex. `rpgquest.build.hub.world_hub`) | les autres Hubs, les claims, le monde Claims, les zones |
| `rpgquest.build.hub.*` | non | tous les Hubs | claims, monde Claims, zones |
| `rpgquest.build.wild` | non | **sans effet** aujourd'hui : le Wild n'a pas de protection de construction | aucun bypass |
| `rpgquest.bypass.claim` | non | modifier dans le claim d'un autre joueur | construction Hub, accès au monde Claims |
| `rpgquest.bypass.claimworld` | non | entrer et construire dans le monde Claims sans les conditions joueur | claims d'autres joueurs |
| `rpgquest.bypass.zone` | non | contourner les zones protégées | claims, Hub |
| `rpgquest.admin.command` | non | **entrer** dans `/rpgadmin`, sans aucune branche | — |
| `rpgquest.admin.npc` / `.npc.tag` / `.npc.untag` / `.npc.info` | non | les actions PNJ de `/rpgadmin npc` (toutes, ou une seule) | les autres branches de `/rpgadmin` |
| `rpgquest.admin.buildsite` | non | emplacements de construction | — |
| `rpgquest.admin.city` | non | villes (outil, centre, nom) | — |
| `rpgquest.admin.travel.maintenance` | non | casser les blocs du réseau de voyage **après** `/rpgadmin travel maintenance on` | — |
| `rpgquest.admin.bypass` | **OP** | **ouvrir** une session de mode bypass (`/rpgadmin bypass on`, #35) — n'accorde aucun contournement à elle seule | tout bypass non détenu |
| `rpgquest.admin.hub.combat` | **OP** | frapper les entités protégées du Hub, **uniquement en mode bypass** (#35) | construction |
| `rpgquest.admin.debug` | **OP** | outils de débogage | — |
| `rpgquest.admin` | **OP** | commandes d'administration (`/rpgquest reload`, `/quest admin`…) | — |
| `rpgquest.admin.world` | **OP** | **ancien nœud global** : construction de tous les Hubs, tous les bypass (**uniquement en mode bypass**, #35), branches historiques de `/rpgadmin` | — |
| `rpgquest.quest`, `.item`, `.money`, `.market`, `.claim`, `.progression`, `.backpack`… | tous | commandes joueur | — |

PlugAdmin (rôles du panel) et OP Minecraft sont indépendants : changer l'un ne change pas l'autre.

## Mode bypass administrateur (#35, #321)

Être OP ne contourne plus **aucune règle de jeu**. Les nœuds `rpgquest.bypass.*`,
`rpgquest.admin.hub.combat` et la partie « bypass » de `rpgquest.admin.world` ne comptent que pendant
une **session explicite** :

| Commande | Effet |
|---|---|
| `/rpgadmin bypass on` | ouvre la session (exige `rpgquest.admin.bypass`) ; barre d'action permanente « ⚠ MODE ADMIN / BYPASS ACTIF » |
| `/rpgadmin bypass off` | ferme la session : règles normales immédiatement |
| `/rpgadmin bypass status` | état courant (et durée restante si une durée maximale est réglée) |

- **OFF par défaut**, jamais en base : fermé à la déconnexion, au redémarrage, au rechargement.
- Le mode **n'ajoute aucun droit** : il arme seulement les nœuds de bypass que le compte détient
  déjà (un compte avec `rpgquest.bypass.zone` seul ne contourne que les zones).
- Les **droits d'administration** (commandes, outils) et de **construction** (`rpgquest.build.*`,
  construction au Hub via l'ombrelle) n'en dépendent pas et restent inchangés pour un OP.
- Réglages globaux dans PlugAdmin > Paramètres généraux > Administration : mode proposé ou désactivé
  pour tous (désactiver coupe les sessions en cours, l'administrateur est prévenu), durée maximale
  (0 = jusqu'à la déconnexion). Aucun de ces réglages n'active le mode pour quelqu'un.
- Chaque activation, désactivation et fin automatique est journalisée (`[admin-bypass]`), et chaque
  entrée Claims accordée par le mode aussi (`[claims-access] … mode bypass administrateur`).
- Hors périmètre : le mode maintenance voyage (`/rpgadmin travel maintenance`, #191) reste un mode
  distinct, déjà explicite et limité à 5 minutes.

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
| Quêtes, dialogues, marchands, spawn, reset joueur en commande : droits séparés | **non fait** : sous `rpgquest.admin` |
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
