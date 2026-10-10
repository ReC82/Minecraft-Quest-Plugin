# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-10
* Heure : 13:40 → (voir « Fin de la tâche »)
* Sujet : Issue #156 — audit du réseau de voyage, diagnostic administrable, et correction ciblée du
  seul défaut démontré
* Statut : DONE (code, tests, build, documentation, déploiement) — validation manuelle en jeu
  restante, issue **non fermée**
* Branche Git : `feature/156-travel-network-audit` (créée depuis
  `feature/234-building-placement-lifecycle`)
* Commit actuel si disponible : `aa774be` (branche poussée, 4 commits)
* Début de la tâche : 2026-10-10 13:40:11
* Fin de la tâche : 2026-10-10 15:34:10
* Durée totale : 01:53:59

---

## Demande

> « Je veux enfin savoir FACTUELLEMENT pourquoi il y a 13 bornes. Ne pars pas du principe que 13 est
> faux. Ne pars pas du principe que 13 est correct. **Mesure.** »

Avec, dans l'ordre strict : audit complet des modèles de génération (Hub et Wild), chiffres réels de
la base, analyse géographique, accessibilité, puis un **diagnostic administrable** bien meilleur dans
PlugAdmin, puis la correction d'un bug **uniquement s'il est démontré**, puis 3 à 4 scénarios
d'équilibrage **chiffrés et proposés, pas activés**.

Interdits explicites, respectés : aucun chunk pré-généré, aucun scan global, aucun backfill massif,
aucun nouveau monde activé, aucun ratio changé, aucune des 13 bornes déplacée ou supprimée, aucune
fréquence augmentée, aucune borne ajoutée dans le Wild, aucune distance modifiée, aucun monde
modifié, aucun mob spawné, aucune progression de joueur touchée, rien fusionné.

---

## Analyse

### La réponse factuelle : POURQUOI 13 ?

**13 n'était ni faux ni correct : c'était un compte arrêté net le 4 octobre par un bug du
déclencheur, déjà corrigé le 8 octobre. Aujourd'hui il y en a 16.**

Le relevé de la base réelle (copie en lecture seule de `data.db`, aucune écriture) donne la
chronologie complète des bornes du Hub :

| # | Créée le | Instance |
|---:|---|---|
| 1 | 2026-10-03 20:15:13 | `minecraft:plains@2,-3` |
| 2 | 2026-10-03 20:17:15 | `minecraft:savanna@2,-3` |
| 3 | 2026-10-03 20:26:47 | `minecraft:forest@0,-2` |
| 4 | 2026-10-03 20:31:20 | `minecraft:swamp@1,-1` |
| 5 | 2026-10-03 20:37:33 | `minecraft:swamp@0,-1` |
| 6 | 2026-10-03 20:37:43 | `minecraft:dark_forest@0,-1` |
| 7 | 2026-10-03 21:02:37 | `minecraft:birch_forest@1,-1` |
| 8 | 2026-10-04 08:14:29 | `minecraft:plains@2,-4` |
| 9 | 2026-10-04 08:14:48 | `minecraft:beach@2,-4` |
| 10 | 2026-10-04 08:15:19 | `minecraft:plains@0,-3` |
| 11 | 2026-10-04 09:37:26 | `minecraft:sparse_jungle@2,-4` |
| 12 | 2026-10-04 09:48:53 | `minecraft:bamboo_jungle@3,-5` |
| 13 | 2026-10-04 09:49:03 | `minecraft:sparse_jungle@3,-5` |
| — | *(quatre jours sans aucune borne)* | |
| 14 | 2026-10-08 12:58:54 | `minecraft:forest@2,-3` |
| 15 | 2026-10-08 13:00:14 | `minecraft:savanna@1,-3` |
| 16 | 2026-10-08 22:43:47 | `minecraft:stony_shore@2,-5` |

Les **13 bornes** sont exactement celles des deux sessions d'exploration des 3 et 4 octobre. Puis
plus rien pendant quatre jours. Les trois suivantes apparaissent le 8 octobre, **après** le
déploiement du correctif `95c0d68 fix(travel): les instances du Hub restaient sans borne appariée
(#156)` (2026-10-08 01:38).

Le chemin exact, sans « probablement » :

1. `TravelBeaconListener` → `TravelBeaconService#handleHubMovement`, sur `PlayerMoveEvent`
   (changement de bloc horizontal), throttlé à `travel.waypoint.move-throttle-millis` = **1500 ms**.
2. Le monde doit être `hub.world` et `travel.waypoint.hub-enabled` = `true`.
3. L'instance est calculée en O(1) : `WaypointIdentityResolver#resolve(region-size, monde, biome, x, z)`
   → `(monde, biomeKey, floor(x/256), floor(z/256))` avec `travel.waypoint.region-size` = **256**.
4. Étape 1 : `WaypointService#ensureGenerated` — exactement le même mécanisme que le Wild.
5. Étape 2 : `ensureBeaconPaired` → `attemptPairBeacon`, qui exige que le waypoint soit **déjà
   persisté** (`waypointService.byId(...)`). Or la persistance est **asynchrone** : au tout premier
   passage, l'étape 2 ne trouve rien et abandonne.
6. **Le défaut** : avant le correctif, la méthode sortait d'emblée si le joueur était « déjà dans
   cette instance » (`instanceKey.equals(lastHubInstanceByPlayer.get(playerId))`). L'étape 2 n'avait
   donc **jamais de seconde chance** tant que le joueur ne quittait pas l'instance pour y revenir.

La preuve chiffrée du défaut est dans les délais waypoint → borne de la même instance :
**2 s, 17 s, 38 s, 42 s, 56 s, 60 s, 60 s, 145 s, 529 s, 545 s, 777 s, 1550 s, 4981 s, 42 649 s,
351 428 s, 405 537 s** — soit jusqu'à **4,7 jours**. Aucune n'est simultanée, et les plus lentes
correspondent à un retour du joueur des jours plus tard. Le **2 s** est la dernière, celle du
2026-10-08 22:43:47 : c'est la signature du comportement corrigé, où le passage suivant du joueur
suffit.

### État réel aujourd'hui (relevé du 2026-10-10, serveur DEV)

| Mesure | Valeur |
|---|---|
| Waypoints, tous mondes | **143** (120 `wild` + 23 `world_hub`) |
| Bornes de voyage | **16**, **toutes dans `world_hub`** |
| Bornes dans le Wild | **0** — par politique, il n'y en a jamais eu |
| Bornes posées à la main (`biome_instance` vide) | **0** |
| Instances du Hub connues | **23** |
| Instances équipées d'une borne | **16** |
| Instances sans borne | **7** |
| Bornes orphelines | **0** |
| Waystones (`wild`) | **7** existantes, **0 découverte** |
| Découvertes de waypoints | 52 (8 dans le Hub, 44 dans le Wild) |

### L'espacement n'est pas en cause, et la densité du Hub non plus

| Mesure (bornes du Hub) | Valeur |
|---|---|
| Distance à la borne la plus proche | min **99**, moyenne **131**, médiane **134**, max **165** blocs |
| Distance au spawn du Hub (`739, -680`) | min **29**, moyenne **431**, médiane **420**, max **849** blocs |
| Étendue couverte | X `25 → 885` (860 blocs) · Z `-1194 → -13` (1181 blocs) |
| Densité observée | une borne pour **63 478 blocs²**, soit une tous les ~252 blocs de côté |
| Écart borne ↔ son waypoint | 7 à 15 blocs — **tous** dans l'anneau configuré 6–16 |

`travel.waypoint.minimum-spacing` vaut **80** blocs et la plus petite distance entre deux bornes
observée est **99** : l'espacement minimal n'a bloqué aucun appariement. Les 7 instances sans borne
affichent **`attempts = 0`** dans le relevé `travel.catalog` en direct — c'est-à-dire **jamais
tenté** depuis le dernier redémarrage, et non « candidats refusés ».

### Les 7 instances sans borne

| Waypoint | Instance | Position | Découverte le | Déjà cliqué par un joueur ? |
|---|---|---|---|---|
| `wp_world_hub_savanna_0_-3` | `savanna@0,-3` | 252, -711 | 2026-10-03 20:24 | non |
| `wp_world_hub_windswept_forest_0_-2` | `windswept_forest@0,-2` | 136, -378 | 2026-10-03 20:27 | non |
| `wp_world_hub_dark_forest_0_-2` | `dark_forest@0,-2` | 44, -414 | 2026-10-03 20:27 | non |
| `wp_world_hub_mangrove_swamp_1_-1` | `mangrove_swamp@1,-1` | 481, -144 | 2026-10-03 20:34 | non |
| `wp_world_hub_swamp_0_0` | `swamp@0,0` | 161, 27 | 2026-10-03 20:38 | non |
| `wp_world_hub_birch_forest_0_0` | `birch_forest@0,0` | 243, 65 | 2026-10-03 20:42 | non |
| `wp_world_hub_sparse_jungle_2_-5` | `sparse_jungle@2,-5` | 676, -1026 | 2026-10-04 09:37 | non |

Toutes les sept datent d'**avant** le correctif, et **aucune n'a jamais été découverte** (clic droit)
par un joueur. Ce sont des instances traversées une fois, jamais revisitées.

### Le défaut qui reste, et il est démontré

Le correctif du 8 octobre répare les **futures** traversées. Il ne répare pas le **retard déjà
constitué** : l'appariement n'est déclenché **que** par le déplacement d'un joueur dans l'instance,
et les compteurs d'essai vivent en mémoire (un redémarrage les remet à zéro). Ces 7 instances
constituent donc un retard **permanent** que rien, dans le jeu, ne peut combler — sauf qu'un joueur
retraverse chacune des sept, par hasard.

Et le seul remède existant ne pouvait pas le combler : `/rpgadmin travel beacon set` crée une borne
avec `biome_instance` **vide**. Elle ne ferme donc aucun appariement — l'instance resterait listée
comme manquante, et un appariement automatique ultérieur pourrait y poser une **seconde** borne.

### Analyse géographique

Par quadrant, relativement au spawn du Hub (`739, -680`) :

| Quadrant | Bornes | Waypoints | Instances sans borne |
|---|---:|---:|---:|
| Ouest-Nord | 6 | 8 | 2 |
| Ouest-Sud | 7 | 12 | 5 |
| Est-Nord | 3 | 3 | 0 |
| **Est-Sud** | **0** | **0** | 0 |

Le quadrant Est-Sud est vide de bornes **parce qu'il est vide de waypoints** : personne ne l'a
exploré. Ce n'est pas un défaut de génération.

### Le Wild : un autre réseau, et le vrai problème de voyage

Confondre les trois réseaux était la source de la question. Mesures :

| Réseau | Mondes | État réel |
|---|---|---|
| Waypoints | Hub **et** Wild | 23 + 120 ; dans le Wild, voisin le plus proche médian **106 blocs** sur une étendue de 9570 × 9550 blocs |
| Bornes | **Hub uniquement** | 16, aucune ailleurs — politique du projet, pas un manque |
| Waystones | Wild | **7 existantes, 0 découverte** ; voisin le plus proche de **1395 à 2849 blocs** |

Le Wild est richement pourvu en **repères** (120 waypoints, 44 découvertes) mais son réseau de
**voyage** — les Waystones — est inexistant en pratique : 7 structures, et **aucune** n'a jamais été
découverte par un joueur, alors que 44 waypoints du même monde l'ont été. Avec
`cell-size: 1000` et `chance: 0.6`, la densité théorique est d'une Waystone pour ~1,67 km² ; les 7
existantes couvrent une étendue de ~26 km², soit une pour 3,7 km² réellement générée (la génération
est paresseuse, cellule par cellule visitée).

---

## Travail effectué

### 1. Audit, sans rien modifier

Lecture des deux modèles de génération (`WaypointService`, `TravelBeaconService`,
`WaypointIdentityResolver`, `WaypointGenerationPlanner`, `WaystoneService`), de la section `travel:`
de `config.yml`, et relevé des chiffres réels : copie **en lecture seule** de `data.db`, plus un
relevé `travel.catalog` **en direct** sur le serveur réel via PlugAdmin (compte jetable, supprimé en
fin de tâche). Aucun chunk généré, aucun scan, aucune écriture.

### 2. Enrichissement du relevé `travel.catalog`

Le relevé ne portait pas de quoi diagnostiquer : ni le monde Hub, ni son spawn, ni les seuils
réellement configurés, ni l'âge des structures, ni le réseau du Wild. L'écran aurait dû les
**supposer**. Ajoutés :

* `createdAt` sur chaque waypoint et chaque borne — c'est ce qui répond à « pourquoi aucune nouvelle
  borne récemment ? » ;
* `hubWorld`, `hubSpawnX/Z`, `hubSpawnKnown`, `instanceRegionSize`, `beaconPairMinSpacing`,
  `beaconPairMaxSpacing`, `waypointMinimumSpacing`, `hubBeaconGenerationEnabled` ;
* `waystoneNetworks` : par monde, existantes / découvertes / `cell-size` / `chance` /
  `minimum-spacing` / étendue — avec un nouveau `WaystoneRepository#totalDiscoveries()`.

Tout vient des index déjà en mémoire, sauf le `COUNT(*)` des découvertes de Waystones.

**Un défaut que j'ai introduit et corrigé avant de livrer** : ce `COUNT(*)` était d'abord consommé
*depuis* le bloc exécuté sur le thread principal, avec une attente bornée à 2 secondes. C'est
exactement ce que les contraintes du projet interdisent — jusqu'à 40 ticks de gel serveur, pour un
compteur de diagnostic. Un diagnostic n'a pas le droit de coûter des ticks à ce qu'il décrit. La
lecture est maintenant consommée **avant** le passage sur le thread principal, et le bloc principal
ne lit plus que des index déjà en mémoire (vérifié ligne à ligne : aucun accès base, aucune attente
de `Future`). Un échec est journalisé et donne « non relevé », jamais un relevé en échec.

### 3. Le calcul du diagnostic, isolé et testé

Nouvelle classe **pure** `panel.travel.TravelNetworkDiagnostic` : paires / manques / orphelines,
statistiques min-moyenne-médiane-max du voisin le plus proche et de la distance au spawn, rectangle
englobant, densité observée, fiches par borne, lignes par instance, dernières dates, réseau du Wild.
Aucun accès réseau, aucune base, aucun type Bukkit — donc chaque cas limite est exécutable en test.

Un principe tenu partout : **« inconnu » n'est jamais « zéro »**. Avec une seule borne il n'existe
pas de distance entre bornes, et afficher `0` laisserait croire à des bornes superposées.

### 4. `/travel` — cinq sections de diagnostic

* **Réseau Hub — diagnostic** : la politique (« bornes dans le Hub uniquement ») et le déclencheur
  réel (« une borne n'est cherchée que lorsqu'un joueur se déplace dans une instance qui n'en a pas
  encore ; il n'existe aucun balayage du monde ») énoncés **avant** toute liste, puis les cinq
  chiffres, les seuils réellement configurés, et les dernières dates.
* **Couverture du Hub** : distances (min / moyenne / médiane / max), étendue X et Z, densité
  observée.
* **Bornes du Hub — fiche par borne** : une fiche ouvrable par borne (`<details>` natif — la CSP
  interdit le script en ligne), avec l'instance servie, la distance à son waypoint **confrontée à
  l'anneau configuré**, la distance au spawn, la borne voisine, l'origine et l'âge.
* **Instances connues du Hub** : une ligne par instance réellement traversée, son état, et la cause
  d'un manque. Une instance jamais traversée n'y figure pas : elle n'est pas un manque.
* **Réseau du Wild — Waystones** : grille, densité théorique, espacement, étendue, et
  **existantes vs découvertes**, avec un avertissement explicite quand aucune n'est découverte.

Le diagnostic est affiché **uniquement sans filtre**. Une moyenne calculée sur un sous-ensemble
recherché serait présentée comme la couverture du réseau ; un filtre est une recherche, pas un
diagnostic. La page le dit au lieu de se taire.

Un cas de déploiement a été traité au passage, parce qu'il **se produira** : le panel se déploie
avant le plugin, donc pendant quelques minutes le dernier relevé vient d'une version qui n'envoyait
pas encore le référentiel du réseau. Un écran naïf en aurait conclu « aucun monde Hub configuré » —
une affirmation **fausse** sur la configuration du serveur. Le diagnostic distingue désormais « champ
absent du relevé » de « champ vide » et demande un rafraîchissement.

### 5. La correction : un rattrapage ciblé, et rien de plus

C'est exactement le remède que le ticket autorise (« action admin explicite ciblée »).

Nouvelle action `travel.beacon.pair`, permission dédiée `TRAVEL_PAIR_WRITE` (OWNER + ADMIN),
mutation **sensible** (confirmation explicite), proposée depuis la fiche d'une instance en manque.
Elle désigne l'instance **par son waypoint**, dans la forme exacte que le serveur fabrique — on ne
peut donc pas demander une borne « quelque part ».

`TravelBeaconService#pairHubInstance` réutilise `attemptPairBeacon` **tel quel** : la borne posée est
une borne auto-générée ordinaire, **appariée à son instance**. C'est ce qui la distingue de
`/rpgadmin travel beacon set`. Le backoff de réessai est volontairement ignoré (la demande est
humaine et explicite). Un refus est une **issue normale** et le message cite les distances réellement
configurées.

Ce qu'elle ne fait pas, par construction : aucun balayage du monde, aucune autre instance touchée,
aucun chunk pré-généré, aucune densité, distance ou probabilité modifiée. Un test le prouve sur deux
instances en manque : on en nomme une, l'autre reste intacte.

**Aucune borne n'a été posée depuis la machine.** L'action est livrée, pas exercée sur le monde réel.

---

## Fichiers créés

| Fichier | Rôle |
|---|---|
| `control-panel/src/main/java/com/lodygames/rpgquest/panel/travel/TravelNetworkDiagnostic.java` | tout le calcul du diagnostic, pur et testable |
| `control-panel/src/test/java/com/lodygames/rpgquest/panel/travel/TravelNetworkDiagnosticTest.java` | 14 tests, un par cas limite |
| `docs/claude-reports/2026-10-10_1340_audit-reseau-voyage.md` | ce rapport |

## Fichiers modifiés

| Fichier | Changement |
|---|---|
| `src/main/java/com/lodygames/rpgquest/travel/beacon/TravelBeaconService.java` | `pairHubInstance(waypointId)` ; `attemptPairBeacon` renvoie désormais si une borne a été posée |
| `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java` | `createdAt` sur les waypoints/bornes, `WaystoneNetworkSummary`, référentiel dans `TravelCatalogView`, `pairHubBeacon` |
| `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java` | relevé enrichi, `pairHubBeacon` sur le thread principal |
| `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java` | `TRAVEL_BEACON_PAIR` |
| `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java` | sérialisation des nouveaux champs, dispatch `travel.beacon.pair` |
| `src/main/java/com/lodygames/rpgquest/database/WaystoneRepository.java` | `totalDiscoveries()` |
| `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java` | câblage des sources de diagnostic du voyage, compteur de découvertes **asynchrone de bout en bout** |
| `control-panel/.../panel/web/AgentPages.java` | les cinq sections et le formulaire d'appariement ciblé |
| `control-panel/.../panel/authz/Permission.java`, `Role.java` | `TRAVEL_PAIR_WRITE`, accordée à ADMIN |
| `control-panel/.../panel/agent/AgentActionCatalog.java` | liste blanche + validation stricte du `waypointId` |
| `docs/RPGQUEST_BIBLE.md` | effet mesuré du correctif, les trois réseaux, les sections, l'action ciblée |
| `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md` | état réel, entrée serveur, index |
| tests existants (`AgentActionExecutorTest`, `StubAgentActions`, `TravelBeaconServiceTest`, `TravelCatalogTest`, `AgentActionCatalogTest`, `RolePermissionMatrixTest`) | nouveaux champs + nouveaux cas |

## Base de données / migrations

**Aucune migration.** Le ticket demandait de ne pas en créer « au cas où ». Tout ce qui est affiché
existait déjà en base : `waypoints`, `travel_beacons`, `waystones`, `waystone_discoveries`. Le seul
ajout SQL est un `COUNT(*)` en lecture.

## Configuration / données

**Aucun réglage modifié.** `travel.waypoint.region-size` (256), `minimum-spacing` (80),
`move-throttle-millis` (1500), `travel.beacon.hub-generation.pair-min/max-spacing` (6/16),
`travel.waystone.cell-size` (1000) et `chance` (0.6) sont **inchangés**. Les seuils sont désormais
*lus* par l'écran, jamais réécrits.

## Tests automatiques

| Suite | Résultat |
|---|---|
| `TravelNetworkDiagnosticTest` (nouveau) | 16 tests — réseau vide vs indisponible, borne seule, spawn inconnu, orpheline, borne posée à la main, coordonnées négatives, médiane paire, causes, extraction du biome, réseau Wild, champ manquant, **relevé antérieur au diagnostic** |
| `TravelBeaconServiceTest` | 38 tests (6 ajoutés) — appariement ciblé sans aucun joueur, **une seule instance touchée**, waypoint inconnu, waypoint hors du Hub, instance déjà équipée, refus citant les distances configurées |
| `TravelCatalogTest` | 8 tests ajoutés — les cinq sections, la portée écrite du geste, le masquage sous filtre, et le relevé antérieur au diagnostic qui demande un rafraîchissement au lieu d'affirmer qu'aucun Hub n'est configuré |
| `AgentActionExecutorTest` | 2 tests ajoutés — référentiel et réseau Wild dans le relevé, appariement ciblé et refus non fatal |
| `AgentActionCatalogTest` | 2 tests ajoutés — permission dédiée, mutation sensible, `waypointId` validé (un id de borne est refusé, une région négative acceptée) |
| `RolePermissionMatrixTest` | 1 test ajouté — ADMIN écrit, les rôles d'observation lisent sans écrire |
| `./gradlew test` | **voir « Résultat des suites » ci-dessous** |
| `./gradlew build` | **voir « Résultat des suites » ci-dessous** |

### Résultat des suites

Exécutées depuis un **worktree Git propre** (`/srv/rpgquest/worktree-nuit`, détaché sur `aa774be`),
parce que le `crystal_hunt.yml` réécrit par le propriétaire dans l'arbre de travail fait échouer
`CrystalHuntIntegrationTest` — le test attend une étape de fabrication et une récompense
`miner_pickaxe`, le fichier du propriétaire a trois objectifs `KILL_ENTITY` et une récompense
`IRON_PICKAXE`. Ce fichier n'est ni commité ni modifié par ce lot.

* `./gradlew test` : **BUILD SUCCESSFUL en 42 min 4 s**, `TEST_EXIT=0`
* `./gradlew build` : **BUILD SUCCESSFUL**, `BUILD_EXIT=0`
* **2233 plugin + 1234 panel + 30 web-api = 3497 tests, 0 échec, 0 erreur**, 38 ignorés (tous
  préexistants : MariaDB sans serveur, limites MockBukkit ; aucun dans les classes ajoutées).

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — à faire par le propriétaire, aucune de ces étapes n'a été simulée :

**TC-275 — diagnostic du réseau de voyage**
1. PlugAdmin → *Réseau de voyage* → **Rafraîchir**.
2. Vérifier la section « Réseau Hub — diagnostic » : **23 instances, 16 bornes, 16 paires, 7 sans
   borne, 0 orpheline**, et la phrase sur le déclencheur.
3. « Couverture du Hub » : voisin le plus proche min 99 / moyenne 131 / médiane 134 / max 165.
4. Ouvrir une fiche de borne : l'instance servie, la distance au waypoint entre 6 et 16.
5. « Instances connues du Hub » : 7 lignes « sans borne », cause « jamais tenté ».
6. « Réseau du Wild » : 7 Waystones, 0 découverte, et l'avertissement.
7. Saisir un filtre : le diagnostic disparaît avec sa raison écrite.

**TC-276 — rattrapage ciblé d'une instance**
1. Ouvrir la fiche de `wp_world_hub_swamp_0_0` (la plus proche du spawn, 161 / 27).
2. Cocher la confirmation, cliquer « Apparier cette instance ».
3. Rafraîchir : l'instance doit passer « borne présente », le compte des manques tomber à 6, et la
   nouvelle borne apparaître avec une distance à son waypoint entre 6 et 16.
4. **En jeu** : aller sur place, vérifier que la borne est posée sur un sol accessible et que son
   bouton ouvre bien le menu de voyage.
5. Relancer l'action sur la même instance : elle doit être **refusée** (« déjà une borne appariée »).

À ne pas confondre avec les tests déjà en attente : TC-273 (#235 onboarding), TC-274 (#234
bâtiments), TC-257 (#123), TC-271/TC-272 (#47). **Aucune donnée nécessaire à ces tests n'a été
touchée.**

## Résultat attendu

Un administrateur ouvre `/travel` et lit, sans ouvrir le jeu ni poser une question : combien
d'instances existent, combien ont leur borne, lesquelles n'en ont pas et **pourquoi**, à quelle
densité réelle le Hub est couvert, et que le Wild n'a pas de bornes du tout mais un réseau de
Waystones dont aucune n'est découverte. Et, pour chacune des instances en retard, il peut débloquer
**celle-là** sans toucher au reste.

## Reset / retour à l'état initial

Rien à réinitialiser : aucune donnée de jeu n'a été modifiée par ce lot. Une borne posée par
`travel.beacon.pair` est une borne auto-générée ordinaire — elle se retire comme les autres, par
`/rpgadmin travel` côté serveur.

## Déploiement VeryGames

**Fait le 2026-10-10, 15:30–15:32**, panel puis JAR, un seul redémarrage, **0 joueur connecté**
(vérifié avant et après). Détail complet et procédure de rollback :
`docs/deployment/SERVER_CHANGELOG.md`, entrée du 2026-10-10.

| | Valeur |
|---|---|
| JAR déployé | 2 216 617 o, SHA-256 `a58ca130…` — `DEPLOY_EXIT=0` |
| Backup JAR | `rpgquest-20261010T133044Z-predeploy.jar`, 2 213 495 o, SHA-256 `171020a6…` (= le JAR de #234) |
| Panel installé | 1 474 423 o, `/health` 200, `/login` public 200 |
| Redémarrage | `RESTART_EXIT=0`, 5 plugins verts |
| Migration | aucune |

**Vérifié sur le serveur réel, pas supposé** : les classes `TravelNetworkDiagnostic`,
`travel.beacon.pair` et `TRAVEL_PAIR_WRITE` sont présentes dans le JAR du panel **installé** ; un
relevé `travel.catalog` déclenché en direct a répondu **SUCCESS** avec « 143 waypoint(s), 16
borne(s) (7 sans borne appariée dans le Hub) », et portait bien le référentiel
(`hubWorld=world_hub`, spawn `738 / -680`, `instanceRegionSize=256`, anneau `6–16`,
`waypointMinimumSpacing=80`), le réseau du Wild (**7 Waystones, 0 découverte**), les `createdAt`, et
les 7 instances toutes à **`attempts = 0`**.

**Aucune borne n'a été posée.** Les 7 instances sont toujours en manque : c'est TC-276 qui les
débloquera.

### À transférer

* **JAR du plugin** : le relevé enrichi et `travel.beacon.pair` vivent dans le plugin.
* **Panel PlugAdmin** : les cinq sections, la permission et la liste blanche vivent dans le panel —
  `scripts/plugadmin/deploy.sh`. **Les deux déploiements sont nécessaires** : sans le panel, aucune
  section nouvelle ; sans le JAR, le panel afficherait un diagnostic amputé du référentiel et
  l'action serait refusée par l'agent.

### Ne PAS transférer/altérer

* `config.yml` du serveur — **aucun réglage de voyage ne change**.
* Les quêtes, dialogues et stories du propriétaire (fichiers non suivis, volontairement non ajoutés
  à Git).
* `data.db`, les mondes, les bâtiments, les PNJ.

### Redémarrage requis

Oui pour le JAR (comme tout déploiement de plugin). Le panel redémarre seul par systemd.

### Migration automatique

Aucune — pas de migration dans ce lot.

## Rollback

* **Plugin** : `scripts/rollback-verygames.sh --latest`, puis redémarrer. Rien à défaire en base.
* **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
* Une borne posée par l'action ciblée survit au rollback : c'est une borne ordinaire. Elle se retire
  par les outils `/rpgadmin travel` déjà en place.

## Logs / diagnostic

* Appariement réussi : `Borne de voyage « … » appariée en … [instance …]`.
* Refus : `Aucun emplacement de borne trouvé pour … (essai N), nouvel essai dans N s.`
* Le refus ciblé est aussi renvoyé à l'écran, avec les distances configurées.

---

## Scénarios d'équilibrage — CHIFFRÉS, PROPOSÉS, **NON ACTIVÉS**

Aucun n'est en place. Chacun est chiffré sur les données réelles mesurées aujourd'hui.

### Mesure préalable qui invalide deux intuitions

Avant de proposer, deux idées naturelles ont été **testées sur les données** et ne tiennent pas :

* **« Réduire `region-size` pour avoir plus d'instances »** — en re-découpant les 23 points connus du
  Hub à 128 puis 64 blocs, on obtient… **23 instances**, exactement. Chaque point connu tombe déjà
  dans sa propre tuile. Diviser la taille d'instance **n'ajouterait pas une seule borne dans le Hub
  exploré** ; cela n'aurait d'effet que sur l'exploration future. Même constat dans le Wild :
  120 waypoints → 120 instances à 256 comme à 128.
* **« Une borne tous les N mètres »** — l'emprise explorée du Hub (860 × 1181) représente ~20 tuiles
  de 256 blocs. Une borne par tuile donnerait ~20 bornes, contre **16** aujourd'hui : +4. La densité
  actuelle (une borne tous les ~252 blocs de côté, voisin le plus proche à 131 blocs en moyenne) est
  déjà de cet ordre.

**Conclusion mesurée : le Hub n'a pas un problème de densité.** Il a un retard de 7 instances, et le
Wild a un problème de voyage.

### Scénario 1 — Statu quo + rattrapage ciblé *(livré dans ce lot, non exercé)*

* **Geste** : 7 clics explicites, un par instance en retard.
* **Effet chiffré** : 16 → **23 bornes** (+44 %), 7 → 0 manque, 23/23 instances équipées.
* **Coût** : ~12 blocs posés par borne ; une ligne en base par borne ; aucune autre instance touchée ;
  aucun réglage modifié.
* **Risque** : nul sur l'équilibrage. Chaque pose est une décision humaine, tracée dans l'audit.
* **Recommandation** : c'est le seul scénario que je recommande d'appliquer tel quel, et il est déjà
  disponible — il suffit de cliquer.

### Scénario 2 — Rattrapage automatique borné du retard *(non implémenté)*

* **Geste** : une tâche qui retente **une** instance en retard toutes les N minutes, uniquement si
  son chunk est **déjà chargé** (jamais de chargement forcé).
* **Effet chiffré** : les 7 instances se résorbent en 7 × N minutes sans aucune action humaine.
  À N = 10, deux heures.
* **Coût** : une tâche répétée, une recherche d'emplacement par tick concerné ; nul le reste du temps
  (la liste est vide dès que le retard est résorbé).
* **Risque** : des bornes posées sans qu'aucun joueur ne soit à proximité — donc apparues « tout
  seules » entre deux sessions. C'est un changement de nature du système, qui devient partiellement
  autonome.
* **Pourquoi je ne l'ai pas fait** : le ticket autorise le rattrapage « au chargement / exploration
  naturelle **ou** action admin explicite ciblée ». J'ai choisi la seconde, qui ne change pas la
  nature du système. La première reste ouverte, et c'est une décision d'équilibrage.

### Scénario 3 — Rendre les Waystones du Wild trouvables *(non implémenté — le vrai problème du Wild)*

* **Constat** : 7 Waystones, **0 découverte**, alors que 44 waypoints du même monde ont été
  découverts. Voisin le plus proche de 1395 à 2849 blocs. Le réseau de voyage du Wild n'existe pas en
  pratique.
* **Geste** : aucune nouvelle structure. Par exemple, indiquer la Waystone la plus proche lors de la
  découverte d'un waypoint du Wild, ou l'exposer dans le menu de voyage.
* **Effet chiffré** : jusqu'à **7 destinations** rendues utilisables immédiatement, sans générer
  quoi que ce soit.
* **Coût** : contenu et interface seulement — **zéro** structure, zéro ligne en base, zéro chunk.
* **Risque** : réduit l'exploration « à l'aveugle », qui est peut-être voulue. C'est un choix de
  design, pas une correction.
* **Recommandation** : meilleur rapport effet/coût de la liste, et aucun effet sur la densité.

### Scénario 4 — Densifier la grille des Waystones *(non implémenté)*

* **Geste** : `travel.waystone.cell-size` 1000 → **700** et `chance` 0.6 → **0.8**.
* **Effet chiffré** : densité théorique d'une Waystone pour 1,67 km² → **une pour 0,61 km²**
  (× 2,7). Sur l'étendue déjà parcourue (~26 km²) : de ~15 attendues à ~43. Les
  `minimum-spacing: 300` restent compatibles avec une grille de 700.
* **Coût** : × 2,7 structures dans le Wild à terme, générées paresseusement au fil de l'exploration.
* **Risque** : banalise le voyage dans le Wild, qui est le monde dangereux. Et **ne corrige rien
  aujourd'hui** : avec 0 découverte, multiplier les Waystones ne crée aucune destination utilisable
  tant que le scénario 3 n'est pas traité.
* **Recommandation** : à n'envisager **qu'après** le scénario 3, sinon on densifie un réseau que
  personne ne trouve.

### Hors scénarios : bornes dans le Wild

Techniquement possible (120 waypoints du Wild pourraient recevoir une borne appariée), mais c'est un
**changement de politique du projet**, pas un réglage d'équilibrage. Chiffre pour la décision :
**jusqu'à +120 structures**. Explicitement hors du périmètre autorisé, et non préparé.

---

## Documentation mise à jour

* `docs/RPGQUEST_BIBLE.md` — effet mesuré du correctif du 8 octobre, tableau des trois réseaux, les
  cinq sections de `/travel`, l'action `travel.beacon.pair` et sa permission.
* `docs/current_state.md` — état réel du réseau et du diagnostic.
* `docs/deployment/SERVER_CHANGELOG.md` — entrée de déploiement (JAR + panel).
* `docs/MANUAL_TEST_PLAN.md` — fiches **TC-275** (diagnostic) et **TC-276** (rattrapage ciblé).
* `docs/claude-reports/README.md` — ligne d'index de ce rapport.

## Limitations / travail restant

* **TC-275 et TC-276 restent à faire en jeu** par le propriétaire. #156 **n'est pas fermée.**
* Les 7 instances en retard **existent toujours** : l'outil est livré, je ne l'ai pas exercé sur le
  monde réel.
* Les compteurs d'essai restent **en mémoire** : un redémarrage les remet à zéro, et l'écran le dit.
  Les persister serait un autre lot.
* La densité d'instance reste **inchangée**, conformément à la consigne. Les quatre scénarios sont
  proposés, aucun n'est activé.
* Le diagnostic décrit ce qui est **enregistré en base** au dernier relevé, jamais une vérification
  physique des blocs — `/rpgadmin travel diagnose` et `repair`/`restore` restent la voie pour cela.

## Prochaine étape suggérée

1. TC-275 puis TC-276 (5 minutes chacun).
2. Choisir parmi les scénarios — ma recommandation : scénario 1 maintenant (7 clics), scénario 3
   ensuite comme vrai lot de gameplay pour le Wild.
3. Puis **#229 lot A** (matrice d'API visuelle des entités Paper 1.21.11), prêt à démarrer.

## Commits

Branche `feature/156-travel-network-audit`, poussée. Aucun merge, aucun push vers `main`.

| Commit | Objet |
|---|---|
| `6cc1260` | `feat(travel)` — relevé enrichi et appariement ciblé d'une instance du Hub |
| `770481a` | `feat(panel)` — diagnostic du réseau de voyage et rattrapage ciblé |
| `b305c22` | `docs(#156)` — audit, TC-275/TC-276 et scénarios chiffrés |
| `aa774be` | `fix(travel)` — ne jamais attendre une lecture SQL sur le thread principal |

Les fichiers de contenu non suivis du propriétaire (7 fichiers `??`) ont été **vérifiés avant chaque
commit** et **n'ont jamais été ajoutés au suivi** — chaque commit a été composé par énumération
explicite des fichiers, jamais par `git add -A`.
