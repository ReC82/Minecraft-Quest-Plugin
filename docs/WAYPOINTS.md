# Waypoints par instance de biome (issue #124)

Ce document décrit le **MVP waypoint** : génération persistante et découverte interactive d'un
repère physique par *instance réelle de biome* dans le monde d'exploration (`travel.wild-world`).

> **Waypoint ≠ Waystone.** Les Waystones (`waystone.WaystoneService`, migration V17) sont un réseau
> de voyage généré sur une **grille de cellules fixes**, avec retour au Hub par canalisation. Les
> waypoints (ce document, migration V18) sont des **repères par zone de biome**, sans téléportation
> dans ce MVP. Les deux systèmes cohabitent : le waypoint est bâti sur la même infrastructure
> partagée (`DatabaseManager`, `SchemaMigrator`, `PluginService`, `RandomSafeLocationFinder`,
> `PlayerListenerService`) mais dans un package isolé `com.lodygames.rpgquest.waypoint`, sans
> toucher au système Waystone déjà déployé.

---

## 1. Qu'est-ce qu'une « instance de biome » ?

L'exigence de #124 : **un waypoint par instance réelle de biome rencontrée**, jamais un simple
`biomeType -> waypoint`. Deux forêts éloignées doivent produire deux waypoints ; deux zones du même
type de biome, si elles sont séparées, sont deux instances.

Minecraft **n'expose aucun identifiant de zone de biome** : un biome est un résultat de bruit, une
« forêt » contiguë n'a pas d'ID. Les seules primitives Paper disponibles sont
`World#getBiome(x, y, z)` (le type de biome à une coordonnée) et la seed du monde.

### Stratégie retenue (MVP) : tuile spatiale biome-typée

```
instance de biome = (monde, biomeKey, regionX, regionZ)

regionX = floor(blockX / region-size)
regionZ = floor(blockZ / region-size)
biomeKey = World#getBiome(pos).getKey()   ex. "minecraft:forest"
```

`region-size` est configurable (`travel.waypoint.region-size`, défaut **256** blocs).

Implémentation : `waypoint.WaypointIdentityResolver` (pur, sans Bukkit) →
`waypoint.model.BiomeInstanceKey`.

- **Forme métier persistée** (`waypoints.biome_instance`) : `biomeKey + "@" + regionX + "," +
  regionZ`, p.ex. `minecraft:forest@3,-1`. `(world, biome_instance)` est **unique** en base.
- **Identifiant technique** (`waypoints.id`, PK) : `wp_<world>_<biome sans namespace>_<regionX>_<regionZ>`,
  p.ex. `wp_wild_forest_3_-1`. **Jamais fonction du modèle de rendu.**

### Propriétés

| Exigence #124 | Résultat |
|---|---|
| Deux forêts éloignées → deux waypoints | ✅ garanti dès que la distance dépasse `region-size` (régions différentes) |
| Pas de `biomeType -> waypoint` global | ✅ la clé inclut `regionX/regionZ` |
| Deux biomes différents dans la même zone | ✅ `biomeKey` différent → instances différentes |
| Stable au redémarrage | ✅ calcul pur sur coordonnées + biome (figé par la seed) |
| Pas de scan terrain coûteux | ✅ O(1), un seul `getBiome` par évaluation throttlée |
| Idempotent en concurrence | ✅ verrou mémoire `generating` + index unique SQL |

### Compromis assumés (à lever dans un ticket ultérieur)

1. **Zone à cheval sur une frontière de région.** Une même grande forêt qui chevauche deux tuiles
   peut produire **deux** waypoints (un par région où un joueur est entré). Ce n'est pas un bug —
   juste deux repères pour une très grande zone — et c'est borné par `minimum-spacing`.
2. **Petite poche de biome ignorée.** Une micro-zone de biome entièrement contenue dans une région
   déjà « prise » par le même `biomeKey` ne reçoit pas son propre waypoint. Acceptable pour le MVP.
3. **Pas de vrai *blob* de biome contigu.** Un flood-fill borné (BFS sur les chunks chargés,
   plafonné) donnerait une identité plus proche de la perception joueur, au prix d'un coût CPU et
   d'un déterminisme plus difficiles. Renvoyé à un futur ticket (voir #122 pour la partie
   protection/zone associée).

La colonne `waypoints.biome_instance` est stockée **explicitement** (pas seulement dérivée) :
migrer vers une identité « flood-fill » se fera en réécrivant cette seule colonne, sans changement
de schéma.

---

## 2. Génération

Déclencheur : `WaypointListener#onMove` → `WaypointService#handleMovement`.

1. **Filtres bon marché d'abord** : monde == `wild-world` ; `enabled` ; changement de **bloc**
   horizontal (jamais à chaque micro-mouvement) ; throttle temporel par joueur
   (`move-throttle-millis`, défaut 1500) ; l'instance courante diffère de la dernière instance vue
   par ce joueur (cache `lastInstanceByPlayer`).
2. Si l'instance a déjà un waypoint (`byInstance`) → rien à faire (la découverte exige le bouton).
3. Si une génération est déjà en cours pour cette instance (`generating`, `Set#add` atomique) →
   abandon silencieux. **Deux joueurs entrant en même temps ne lancent qu'une génération.**
4. Si un retry est programmé et pas encore dû (`retryNotBefore`) → abandon.
5. **Recherche d'emplacement** (`WaypointGenerationPlanner`, pur, déterministe par `seed =
   hash(instanceKey)`) : `candidate-attempts` points tirés à un angle uniforme et une distance
   uniforme dans `[min-distance, max-distance]` (défaut 24..72). `min-distance >= 8` est validé :
   **le waypoint n'apparaît jamais au pied du joueur**.
6. Pour chaque candidat, dans l'ordre :
   - le candidat doit rester **dans la même instance** (même `biomeKey` + même tuile) ;
   - surface sûre via `RandomSafeLocationFinder#findAtColumn` (sol solide non dangereux, 2 blocs
     d'air) — réutilisé tel quel du système de voyage ;
   - **zone libre** : chaque bloc du modèle est « naturel » (air, herbe, neige, feuillage,
     fleurs…) et hors claim (`WaypointPlacementGuard`, branché sur `ClaimService` en prod) — on ne
     détruit **jamais** une construction joueur pour poser un waypoint ;
   - `minimum-spacing` respecté vis-à-vis des autres waypoints.
7. Premier candidat valable → `WaypointRepository#insertIfAbsent` (`INSERT OR IGNORE`, l'index
   unique tranche toute course résiduelle) ; si insertion réelle → pose de la structure + indexation
   en mémoire.
8. **Aucun candidat valable** → `generating` libéré, retry programmé avec back-off **borné**
   `30 s → 2 min → 10 min → 1 h` puis plafonné. Jamais de boucle, jamais de scan répété par
   déplacement.

Toute la partie « lecture terrain » est **synchrone** (thread principal, chunks du joueur déjà
chargés) ; seule la persistance est asynchrone (`DatabaseManager`), avec re-marshalling sur le
thread principal pour poser les blocs.

---

## 3. Modèle de rendu (abstrait et versionné)

`waypoint.render` :

- `WaypointModel` — interface : `version()`, `place(World, ancre, facing)`, `interactor(facing)`,
  `protectedBlocks(facing)`. Tous les décalages sont **relatifs à l'ancre** (colonne de surface).
- `WaypointModelV1` (`version() == 1`) — exigence #124 « support en barrière de pierre + bloc d'or
  + bouton » :

  ```
  (0, -1, 0)  sol renforcé en pierre s'il n'était pas solide
  (0,  0, 0)  COBBLESTONE_WALL   (support ; Minecraft n'a pas de « barrière de pierre »,
                                  COBBLESTONE_WALL est la barrière de la famille pierre)
  (0,  1, 0)  GOLD_BLOCK         (élément visuel principal)
  (f,  1, 0)  STONE_BUTTON       (interacteur, posé sur la face « facing » du bloc d'or)
  ```

  `f` = vecteur cardinal de `facing` (le bouton regarde le joueur qui a déclenché la génération).

- `WaypointModelRegistry` — `version -> modèle`. La logique métier ne connaît qu'un **numéro**
  (`waypoints.model_version`). Introduire un `WaypointModelV2` (autre structure, ou un schematic)
  puis basculer `travel.waypoint.model-version` sur `2` n'affecte **ni** l'identité des waypoints
  existants **ni** les découvertes. Re-poser les waypoints existants avec un nouveau modèle sera une
  passe explicite et coordonnée (hors périmètre MVP) — l'ancre + `facing` + `model_version`
  persistés suffisent.

Test de non-régression : `changingTheRenderModelVersionNeverChangesTheBusinessIdentity`.

---

## 4. Découverte

- **La proximité ne découvre rien.** Aucun scan de rayon, aucune détection au passage.
- `WaypointListener#onInteract` : clic **droit**, main principale, `RIGHT_CLICK_BLOCK`. Le service
  ne réagit **que si le bloc cliqué est exactement l'interacteur** (le bouton) d'un waypoint —
  cliquer le bloc d'or ou le support ne fait rien.
- Première découverte pour ce joueur → `waypoint_discoveries (player_uuid, waypoint_id,
  discovered_at)` via `INSERT OR IGNORE` ; retour joueur : message MiniMessage + son
  (`BLOCK_BEACON_ACTIVATE`). Les découvertes suivantes du même joueur sont silencieuses (clic
  consommé, aucune ré-écriture).
- Les découvertes sont **par UUID** et indépendantes entre joueurs ; elles survivent au reload
  (rechargées à la connexion, `handleJoin`).

---

## 5. Protection (MVP)

`WaypointProtectionListener` (même conception que `claim.ClaimProtectionListener`) protège
**les blocs constitutifs** listés par le modèle :

| Vecteur | Événement | Effet |
|---|---|---|
| Casse joueur | `BlockBreakEvent` | annulé (sauf `rpgquest.admin.world`) |
| Remplacement direct | `BlockPlaceEvent` | annulé si la cible est un bloc protégé |
| Explosion (creeper, TNT, lit…) | `EntityExplodeEvent`, `BlockExplodeEvent` | blocs protégés retirés de `blockList()` |
| Piston | `BlockPistonExtendEvent`, `BlockPistonRetractEvent` | annulé si un bloc déplacé/poussé touche un bloc protégé |
| Feu | `BlockBurnEvent`, `BlockIgniteEvent` | annulé sur un bloc protégé |
| Fluide entrant | `BlockFromToEvent` | annulé si la destination est protégée |
| Sable/gravier/enderman | `EntityChangeBlockEvent` | annulé sur un bloc protégé |

**Hors périmètre (→ issue #122)** : protection *fonctionnelle* de proximité (anti-enfermement,
zone tampon, dégagement vertical, chemin praticable garanti, signal vertical repérable de loin,
auto-heal / watchdog). L'architecture est compatible : le service est **propriétaire** d'un ensemble
de positions protégées, découplé du rendu — #122 pourra ajouter une couche « zone » et une
validation d'accès sans toucher au reste.

---

## 6. Persistance (migration V18)

Séparation stricte **définition monde** / **progression joueur**, sans couplage MariaDB
supplémentaire (SQL SQLite canonique passé par `SqlDialect`, portable #41).

```sql
CREATE TABLE waypoints (
    id TEXT PRIMARY KEY,            -- wp_<world>_<biome>_<rx>_<rz>, stable, indépendant du rendu
    world TEXT NOT NULL,
    biome_instance TEXT NOT NULL,   -- "minecraft:forest@3,-1" — identité métier
    biome_key TEXT NOT NULL,        -- "minecraft:forest" (dénormalisé, lecture Control Panel)
    region_x INTEGER NOT NULL,
    region_z INTEGER NOT NULL,
    x INTEGER NOT NULL,             -- ANCRE (colonne de surface), pas l'interacteur
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    facing TEXT NOT NULL,           -- NORTH | SOUTH | EAST | WEST
    model_version INTEGER NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);
CREATE UNIQUE INDEX idx_waypoints_instance ON waypoints (world, biome_instance);

CREATE TABLE waypoint_discoveries (
    player_uuid TEXT NOT NULL,
    waypoint_id TEXT NOT NULL,
    discovered_at TEXT NOT NULL,
    PRIMARY KEY (player_uuid, waypoint_id),
    FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
);
```

`waypoint_discoveries` **ne duplique jamais** la définition physique : `player_uuid + waypoint_id`
+ une date. Aucune clé étrangère vers `waypoints` (une découverte survit à une régénération d'id).

---

## 7. Configuration (`config.yml`, section `travel.waypoint`)

| Clé | Défaut | Rôle |
|---|---|---|
| `enabled` | `true` | coupe complètement le système |
| `region-size` | `256` | côté (blocs) de la tuile d'instance de biome |
| `min-distance` | `24` | rayon minimal de génération autour du joueur (validé `>= 8`) |
| `max-distance` | `72` | rayon maximal |
| `candidate-attempts` | `12` | points candidats testés avant retry borné |
| `move-throttle-millis` | `1500` | intervalle min entre deux évaluations pour un même joueur |
| `minimum-spacing` | `80` | distance minimale entre deux waypoints |
| `model-version` | `1` | version de rendu stampée sur les nouveaux waypoints |

Validation : `ConfigValidator#validateWaypoint`. Section absente → tous les défauts.

---

## 8. Ce qui n'est PAS livré dans ce MVP

- téléportation / fast travel entre waypoints ; coût de voyage ; menus ;
- waypoint de quête ;
- protection fonctionnelle anti-enfermement de proximité (#122) ;
- migration MariaDB (les tables sont déjà portables) ;
- éditeur PlugAdmin de waypoints ;
- lecture `/waypoints` dans PlugAdmin — non livrée faute de temps après le moteur ;
  `WaypointService` expose déjà `all()`, `byId(String)`, `discoveryCount(String)` pour la brancher
  ensuite sans changement de schéma.

## 9. Tests

Automatisés (`src/test/java/com/lodygames/rpgquest/waypoint/`) :

- `WaypointIdentityResolverTest` — identité stable, deux forêts éloignées ≠, biomes ≠, `floorDiv`
  sur coordonnées négatives, frontière de région.
- `WaypointGenerationPlannerTest` — candidats dans l'anneau, jamais au pied du joueur, déterminisme.
- `WaypointModelRegistryTest` — v1 (or + bouton latéral), interacteur protégé et ≠ bloc d'or,
  repli de version, refus des versions dupliquées.
- `WaypointServiceTest` (MockBukkit) — 1 seul waypoint à la 1re entrée, biome correct, pas au pied
  du joueur, concurrence 2 joueurs → 1 waypoint, `insertIfAbsent` idempotent, 2 zones du même biome
  → 2 waypoints, proximité sans découverte, découverte **bouton uniquement**, découvertes A/B
  indépendantes, reload sans doublon + découvertes conservées, changement de version de rendu sans
  changement d'identité, échec propre + retry borné.
- `WaypointProtectionListenerTest` (MockBukkit) — casse joueur refusée, bypass admin, explosion,
  piston, feu.

Voir `docs/MANUAL_TEST_PLAN.md` pour la validation en jeu (rendu réel, biomes réels du monde
`wild`, physique fluides/pistons/gravité, suppression réelle du redstone du bouton).
