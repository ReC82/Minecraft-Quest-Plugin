# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 21:18 (heure locale de la machine de build)
* Sujet : Issue #124 — MVP waypoints par instance de biome (génération persistante + découverte interactive)
* Statut : DONE (moteur) — validation en jeu `PENDING MANUAL VALIDATION` ; déploiement DEV non effectué (préparé)
* Branche Git : `feat/control-panel-admin-tools` (consigne explicite : rester sur la branche courante, ne rien merger)
* Commit actuel au démarrage : `7af1031`
* Début de la tâche : 2026-09-09 20:38:36
* Fin de la tâche : 2026-09-09 21:2x:xx (voir commit final — rapport rédigé à 21:18, commit + push juste après)
* Durée totale : ~00:45:00 (dont ~00:12:00 de `./gradlew build` sur une box très contrainte)

## Demande

Travailler sur l'issue #124 : livrer un **MVP waypoint réellement testable en jeu demain matin**.
Lire #124 et #122 (direction anti-grief future) sans implémenter le système anti-enfermement de #122.
Auditer l'architecture existante et **réutiliser les abstractions plutôt que créer un second système
parallèle**. Comportement cible : waypoints dans le monde `wild`, un par **instance réelle de
biome** (jamais `biomeType -> waypoint`), génération paresseuse et unique (anti-concurrence),
placement en surface à distance (pas au pied du joueur), persistance monde + découverte joueur
séparées, rendu V1 = barrière de pierre + bloc d'or + bouton, **modèle visuel abstrait/versionné**,
découverte **uniquement** par interaction avec le bouton, persistée par UUID, blocs du waypoint
protégés a minima (casse joueur, explosion, piston, feu), échec propre + retry borné. Performance :
pas de recherche terrain à chaque `PlayerMoveEvent`. Tests automatiques autant que MockBukkit le
permet ; documenter les tests manuels restants. Puis : tests ciblés, `./gradlew build`, docs,
rapport, commit + push (pas de merge). Déploiement DEV VeryGames **seulement si sûr** ; sinon
s'arrêter et donner la commande exacte. Mail de fin avec le rapport en pièce jointe.

## Analyse

### Audit de l'existant

- Bootstrap `.ai/SESSION_START.md` + audit Git : working tree propre, branche
  `feat/control-panel-admin-tools`, dernier commit `7af1031`.
- **Découverte majeure : un système `com.lodygames.rpgquest.waystone` existe déjà** (migration
  V17, tables `waystones` / `waystone_discoveries`, déployé en production). C'est un **réseau de
  voyage** : génération paresseuse sur une **grille de cellules carrées fixes** (`WaystoneCellPlanner`,
  décision déterministe seed+cellule), découverte individuelle au clic droit, **retour au Hub par
  canalisation**. Rendu en dur (deepslate + lodestone), **pas** de version de rendu, **pas** de
  protection de blocs.
- Infrastructure réutilisable identifiée : `DatabaseManager` (async, mono-thread FIFO),
  `SchemaMigrator` (migrations numérotées idempotentes, SQLite canonique + `SqlDialect` portable
  MariaDB), `PluginService` / `PluginServiceRegistry` (cycle de vie), `PlayerListenerService`
  (wrap d'un `Listener`), `RandomSafeLocationFinder#findAtColumn` (surface sûre d'une colonne),
  `ClaimService#claimAt`, `ClaimProtectionListener` (patron d'écouteur de protection), patron
  `*Repository` (voir `WaystoneRepository`), MockBukkit 4.110.0 (`WorldMock#setBiome`/`getBiome`
  disponibles).

### Décision d'architecture (structurante — documentée pour l'owner)

**Nouveau package `com.lodygames.rpgquest.waypoint`, purement additif, bâti sur l'infrastructure
partagée. Le système `waystone` n'est pas modifié.**

Justification :

1. L'identité demandée par #124 (**instance de biome**) est incompatible avec le modèle d'identité
   du système Waystone (**cellule de grille fixe**) : deux forêts dans la même cellule → une seule
   Waystone ; la grille est orthogonale aux biomes. Muter l'identité d'un système **déjà déployé
   avec des données de production** violerait la règle « ne jamais sacrifier l'intégrité des
   données » (CLAUDE.md / NIGHT_MODE).
2. #124 est spécifié comme sa propre fonctionnalité (identité biome, rendu versionné, protection),
   ne référence jamais les Waystones, et met la téléportation hors périmètre — alors que Waystone
   *a* déjà la téléportation.
3. « Réutiliser les abstractions » est respecté : le package waypoint réutilise `DatabaseManager`,
   `SchemaMigrator`, le patron repository + table de découverte, `PluginService`,
   `RandomSafeLocationFinder`, `PlayerListenerService`, le concept d'abstraction de pose de
   structure (généralisé + versionné). Ce n'est pas un « second système parallèle » au sens d'une
   infra dupliquée : c'est une seconde *fonctionnalité de domaine* sur une seule couche d'infra.
4. Choix **réversible** : nouveau package + migration additive V18 + nouvelle section de config.
   Aucune donnée ni schéma déployé touché.

Si l'owner préfère à terme fusionner les deux concepts (waypoint = évolution biome des Waystones),
c'est un refactor ultérieur ; ce MVP ne l'empêche pas et ne le présume pas.

### Stratégie « instance de biome » (le point critique de #124)

Paper n'expose aucun identifiant de zone de biome. Retenu pour le MVP :

```
instance = (monde, biomeKey, regionX, regionZ)
regionX = floor(blockX / region-size)   (region-size configurable, défaut 256)
biomeKey = World#getBiome(pos).getKey()  ex. "minecraft:forest"
```

- **Forme persistée** (`waypoints.biome_instance`, ex. `minecraft:forest@3,-1`),
  `(world, biome_instance)` unique en base.
- **id technique** (`waypoints.id`, ex. `wp_wild_forest_3_-1`) — jamais fonction du rendu.

Garantit : deux forêts séparées de plus de `region-size` → deux instances → deux waypoints ; deux
biomes différents dans la même tuile → deux instances ; calcul O(1), déterministe, stable au
redémarrage ; pas de scan terrain.

**Compromis documentés** (dans `docs/WAYPOINTS.md` §1) : une grande zone à cheval sur une frontière
de région peut donner 2 waypoints ; une micro-poche de biome dans une région déjà « prise » du
même biome n'a pas son propre waypoint ; ce n'est pas un vrai flood-fill de blob contigu (renvoyé
à un ticket ultérieur). La colonne `biome_instance` est stockée explicitement pour permettre de
migrer vers une identité flood-fill en réécrivant cette seule colonne, sans migration de schéma.

## Travail effectué

### Moteur (`com.lodygames.rpgquest.waypoint`)

- **`model/BiomeInstanceKey`** — record `(world, biomeKey, regionX, regionZ)` + `serialize()` +
  `waypointId()`. Doc détaillée de la stratégie et des compromis.
- **`model/Waypoint`** — record de la définition persistée (id, world, biomeInstance, biomeKey,
  regionX/Z, x/y/z = **ancre** de surface, facing, modelVersion, active, createdAt).
- **`WaypointIdentityResolver`** — pur, sans Bukkit : `regionOf` (floorDiv), `resolve`,
  `sameInstance`. `region-size` passé par appel (reload-safe).
- **`WaypointGenerationPlanner`** — pur : liste ordonnée et **déterministe** (seed = hash de l'id
  d'instance) de points candidats dans l'anneau `[min-distance, max-distance]` ; jamais au pied du
  joueur.
- **`render/WaypointModel`** (interface) + **`render/WaypointModelV1`** (version 1 : sol renforcé,
  `COBBLESTONE_WALL` = « barrière de pierre », `GOLD_BLOCK`, `STONE_BUTTON` latéral orienté vers le
  joueur ; dégagement d'air au-dessus) + **`render/WaypointModelRegistry`** (`version -> modèle`,
  version courante configurable avec repli sur la plus haute connue) + **`render/BlockOffset`**.
- **`WaypointPlacementGuard`** (interface fonctionnelle) — autorise/refuse la pose à une position ;
  en prod branchée sur `ClaimService` (jamais de destruction d'un claim / d'une construction).
- **`WaypointService` (`PluginService`)** — génération paresseuse **single-flight** (verrou mémoire
  `generating` + `INSERT OR IGNORE` + index unique), throttle par joueur + cache de la dernière
  instance, recherche de surface via `RandomSafeLocationFinder`, contrôle « zone naturelle libre »
  (air/herbe/neige/feuillage… + hors claim) sur toute l'empreinte du modèle, `minimum-spacing`,
  retry **borné** avec back-off `30 s → 2 min → 10 min → 1 h` puis plafonné (pas de boucle),
  découverte par UUID (`waypoint_discoveries`), feedback message + son, index `isProtectedBlock`
  pour la protection, API lecture `all()` / `byId()` / `discoveryCount()`.
- **`WaypointListener`** — `PlayerMoveEvent` uniquement au changement de bloc horizontal (le
  service applique ensuite son throttle temporel), `PlayerInteractEvent` (clic droit main
  principale, **bouton uniquement**), join/quit/world-change/teleport.
- **`WaypointProtectionListener`** — modelé sur `ClaimProtectionListener` : `BlockBreakEvent`,
  `BlockPlaceEvent`, `EntityExplodeEvent`/`BlockExplodeEvent` (retrait du `blockList`),
  `BlockPistonExtendEvent`/`RetractEvent`, `BlockBurnEvent`, `BlockIgniteEvent`,
  `BlockFromToEvent` (fluide), `EntityChangeBlockEvent` (sable/gravier/enderman). Bypass
  `rpgquest.admin.world`. Dépend d'un `ProtectedBlockLookup` (fonctionnel) → testable sans le
  service complet.

### Persistance — migration V18

`SchemaMigrator.CURRENT_VERSION` 17 → 18. `applyV18` (SQLite canonique via `dialect.ddl`,
idempotent, `CREATE TABLE IF NOT EXISTS`, aucun `ALTER`) :

- `waypoints (id PK, world, biome_instance, biome_key, region_x, region_z, x, y, z, facing,
  model_version, active DEFAULT 1, created_at)` + `CREATE UNIQUE INDEX idx_waypoints_instance
  (world, biome_instance)` ;
- `waypoint_discoveries (player_uuid, waypoint_id, discovered_at, PK(player_uuid, waypoint_id),
  FK player_profiles ON DELETE CASCADE)`.

`database/WaypointRepository` — JDBC pur, patron `WaystoneRepository` : `loadAll`, `findByInstance`,
`insertIfAbsent` (`INSERT OR IGNORE` via `dialect.rewrite`), `discoveriesFor`, `recordDiscovery`,
`discoveryCountForWaypoint`, `deleteDiscoveries`.

### Configuration

- `TravelConfig` : 4e composante `WaypointConfig` (+ constructeur historique à 3 composantes
  conservé pour tous les sites d'appel antérieurs → aucun changement forcé ailleurs).
- `ConfigValidator#validateWaypoint` : `enabled`, `region-size` (>=16), `min-distance` (>=8),
  `max-distance` (>= min), `candidate-attempts` (>0), `move-throttle-millis` (>=0),
  `minimum-spacing` (>=0), `model-version` (>0). Section absente → défauts sûrs.
- `src/main/resources/config.yml` : bloc `travel.waypoint` commenté. Ajouté automatiquement au
  `config.yml` d'un serveur existant par `ConfigFileCompleter` au prochain démarrage.
- `RPGQuestBootstrap` : construction + `registry.start` du service et des deux écouteurs, garde de
  placement = `claimService.claimAt(...).isEmpty()`.

## Fichiers créés

* `src/main/java/com/lodygames/rpgquest/waypoint/WaypointIdentityResolver.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/WaypointGenerationPlanner.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/WaypointPlacementGuard.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/WaypointService.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/WaypointListener.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/WaypointProtectionListener.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/model/BiomeInstanceKey.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/model/Waypoint.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/render/BlockOffset.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/render/WaypointModel.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/render/WaypointModelV1.java`
* `src/main/java/com/lodygames/rpgquest/waypoint/render/WaypointModelRegistry.java`
* `src/main/java/com/lodygames/rpgquest/database/WaypointRepository.java`
* `src/test/java/com/lodygames/rpgquest/waypoint/WaypointIdentityResolverTest.java` (7)
* `src/test/java/com/lodygames/rpgquest/waypoint/WaypointGenerationPlannerTest.java` (4)
* `src/test/java/com/lodygames/rpgquest/waypoint/render/WaypointModelRegistryTest.java` (5)
* `src/test/java/com/lodygames/rpgquest/waypoint/WaypointServiceTest.java` (11, MockBukkit)
* `src/test/java/com/lodygames/rpgquest/waypoint/WaypointProtectionListenerTest.java` (6, MockBukkit)
* `docs/WAYPOINTS.md`
* `.ai/SESSION_STATE.md`

## Fichiers modifiés

* `src/main/java/com/lodygames/rpgquest/database/SchemaMigrator.java` — V18 + `CURRENT_VERSION` 18
* `src/main/java/com/lodygames/rpgquest/config/TravelConfig.java` — `WaypointConfig`
* `src/main/java/com/lodygames/rpgquest/config/ConfigValidator.java` — `validateWaypoint`
* `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java` — câblage
* `src/main/resources/config.yml` — section `travel.waypoint`
* `src/test/java/.../database/SchemaMigratorTest.java` — assertions 17→18 + `migrateCreatesWaypointTables` + `reRunningV18IsIdempotent`
* `src/test/java/.../database/MySqlDialectTranslationTest.java` — traduction de l'index waypoints
* `src/test/java/.../database/MariaDbTestSupport.java` — tables `waypoints`/`waypoint_discoveries` au nettoyage
* `src/test/java/.../config/ConfigValidatorTest.java` — 6 cas `travel.waypoint`
* `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/TRAVEL.md`, `docs/MANUAL_TEST_PLAN.md`
  (TC-210), `README.md`, `TODO.md`, `.ai/ROADMAP.md`, `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations

Migration **V18** (`waypoints`, `waypoint_discoveries` + index unique). Additive, idempotente,
aucun `ALTER`. Appliquée automatiquement au démarrage par `SchemaMigrationRunner`. Portable
MariaDB (#41) : DDL SQLite canonique via `SqlDialect`, `INSERT OR IGNORE` via `dialect.rewrite`.
Un ancien JAR (schéma attendu 17) redémarre sans souci sur une base déjà en V18 (tables
surnuméraires ignorées).

## Configuration / données

Nouvelle section `config.yml` : `travel.waypoint` (8 clés, défauts sûrs). Ajout automatique par
`ConfigFileCompleter`, valeurs personnalisées jamais écrasées, section absente = défauts.
Nouveau comportement runtime : pose autonome d'une petite structure vanilla (barrière + or +
bouton) dans le monde `wild` à l'entrée d'un joueur dans un biome neuf — **additif, non
destructif** (contrôle hors-claim + blocs naturels + `minimum-spacing`).

## Tests automatiques

- **Ciblés** (box contrainte, `RPGQUEST_TEST_MAX_HEAP=640m`, `-Xmx512m`) : `com.lodygames.rpgquest.waypoint.*`
  + `SchemaMigratorTest` + `MySqlDialectTranslationTest` + `SchemaMigrationRunnerTest` +
  `ConfigValidatorTest` + `waystone.*` → **tous verts**. 33 tests waypoint (7+4+5+11+6).
- **Build complet** : `./gradlew build` → **BUILD SUCCESSFUL** en 11 min 36 s.
  - root `:test` : **1254 tests, 0 échec, 0 erreur**, 29 skipped (tests d'intégration MariaDB
    `@EnabledIfEnvironmentVariable`, pré-existants).
  - `:control-panel:test` : **279 tests, 0 échec**, 1 skip (pré-existant).
  - `:web-api:test` : UP-TO-DATE (inchangé).

Couverture des exigences de tests de #124 :

| Exigence #124 | Test |
|---|---|
| 1re entrée → un seul waypoint | `firstEntryIntoABiomeZoneGeneratesExactlyOneWaypoint` |
| 2e joueur / 2e entrée → pas de doublon | `twoPlayersEnteringSimultaneouslyNeverCreateTwoWaypoints`, `insertIfAbsentIsIdempotentAtTheRepositoryLevel` |
| 2 instances séparées du même biome → 2 waypoints | `twoSeparatedZonesOfTheSameBiomeGetTwoDistinctWaypoints`, `WaypointIdentityResolverTest` |
| Waypoint dans le bon biome | `firstEntryIntoABiomeZoneGeneratesExactlyOneWaypoint` (assert `biomeKey`) |
| Interaction bouton → découverte persistée | `onlyAnExplicitClickOnTheButtonValidatesDiscovery` |
| Proximité seule → rien | `walkingPastAWaypointDiscoversNothing` |
| Découverte A indépendante de B | `discoveryIsIndependentPerPlayer` |
| Blocs protégés | `WaypointProtectionListenerTest` (5), `isProtectedBlockCoversTheWholeStructure` |
| Reload/restart → même waypoint + découvertes | `aFreshServiceReloadsWaypointsAndDiscoveriesWithoutDuplicates` |
| Concurrence / idempotence | `twoPlayersEnteringSimultaneously…`, `insertIfAbsent…` |
| Changement de version de modèle ≠ changement d'identité | `changingTheRenderModelVersionNeverChangesTheBusinessIdentity` |
| Pas au pied du joueur | `firstEntry…` (distance), `WaypointGenerationPlannerTest` |
| Échec propre + retry borné | `aFailedGenerationSchedulesABoundedRetryWithoutLooping` |

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — voir **`docs/MANUAL_TEST_PLAN.md` TC-210** (procédure complète).
Ce que MockBukkit ne peut pas couvrir et qui **doit** être vérifié en jeu :

1. Distribution réelle des biomes du monde `wild` et pose effectivement dans le bon biome.
2. Rendu visuel de la structure (barrière + or + bouton, orientation).
3. Physique réelle : fluides (lave/eau), pistons, blocs à gravité contre la structure.
4. Suppression réelle du signal redstone du bouton au clic (l'événement est annulé).
5. Comportement du throttle sous déplacement réel + charge multi-joueurs.
6. Génération non déclenchée sous les pieds du joueur, structure jamais dans une construction.

## Résultat attendu

Un joueur qui explore `wild` déclenche, une fois par instance de biome, la création d'un repère
physique persistant partagé, à chercher (24-72 blocs), qu'il **découvre** en cliquant sur son
bouton. Les repères survivent aux redémarrages sans doublon, les découvertes sont par joueur, les
blocs sont protégés. Aucune téléportation (hors périmètre).

## Reset / retour à l'état initial

- `travel.waypoint.enabled: false` + `/rpgquest reload` → désactive génération et découverte,
  aucune donnée touchée.
- Vider `waypoints` / `waypoint_discoveries` à froid pour repartir de zéro (jamais nécessaire au
  fonctionnement).
- Retirer une structure déjà posée : la casser en `rpgquest.admin.world`, ou restaurer un backup
  `world_wild/`.
- Rollback JAR : un ancien JAR redémarre sans souci, les tables V18 restent inertes.

## Déploiement VeryGames

### À transférer

**DEV uniquement** : le nouveau JAR `plugins/RPGQuest-*.jar`. Rien d'autre (migration V18 et
section `travel.waypoint` appliquées au démarrage).

### Ne PAS transférer/altérer

Production. `data.db`. `plugins/Citizens/saves.yml`. La config existante (elle est complétée
automatiquement, jamais écrasée). Aucun monde à créer.

### Redémarrage requis

Oui — redémarrage complet après remplacement du JAR (`scripts/verygames-restart.sh`, RCON).

### Migration automatique

Oui — V18 au démarrage, additive et idempotente. Rien à lancer à la main.

### Déploiement effectué ?

**Non.** Raisons : (1) CLAUDE.md « aucun déploiement automatique en fin de tâche » ; (2)
`scripts/verygames.env` absent de la box de build → `deploy-verygames.sh` ne peut pas s'exécuter ;
(3) première mise en service d'un comportement de **pose de blocs autonome** → validation humaine
souhaitable sur la première génération en jeu ; (4) le rollback des structures déjà posées est
manuel (pas de script).

### Commande exacte à lancer demain (depuis `/srv/rpgquest/repo`)

```
# 1. renseigner scripts/verygames.env à partir de scripts/verygames.env.example (secrets FTP/RCON)
./gradlew build                    # doit être vert (l'était ce 2026-09-09)
scripts/deploy-verygames.sh        # upload FTP du JAR + backup daté automatique
scripts/verygames-restart.sh       # redémarrage RCON (stop -> attente du retour)
```

Puis suivre `docs/MANUAL_TEST_PLAN.md` TC-210.

## Rollback

`scripts/rollback-verygames.sh` (ancien JAR) + `scripts/verygames-restart.sh`. Tables V18
laissées en place (inertes). Structures de waypoint : suppression manuelle ou backup `world_wild/`.
`travel.waypoint.enabled: false` + reload = coupe-circuit sans rollback de JAR.

## Logs / diagnostic

- Démarrage : `Waypoints chargés : N.`
- Génération : `Waypoint « wp_wild_<biome>_<rx>_<rz> » généré en wild (x,y,z) [biome …, modèle v1].`
- Échec de placement : `Aucun emplacement de waypoint trouvé pour <instance> (essai n), nouvel essai dans <s> s.`
- Erreurs éventuelles : `Impossible de persister le waypoint …` / `Impossible de charger les waypoints persistés.`

## Documentation mise à jour

`docs/WAYPOINTS.md` (nouveau, référence complète + compromis), `docs/RPGQUEST_BIBLE.md` (§7 sous-
section + table des migrations V18 + « version courante = 18 » + classification des données + §19),
`docs/current_state.md`, `docs/TRAVEL.md` (section dédiée), `docs/MANUAL_TEST_PLAN.md` (§20 / TC-210
+ table de recette), `README.md`, `TODO.md` (suites #124 / #122), `.ai/ROADMAP.md` (journal),
`docs/deployment/SERVER_CHANGELOG.md` (entrée 2026-09-09, déploiement non exécuté + procédure).

## Limitations / travail restant

- **Validation en jeu non faite** (`PENDING MANUAL VALIDATION`, TC-210). Issue #124 **non fermée**.
- **Déploiement DEV non effectué** (préparé, commande fournie).
- **Identité « instance de biome » = approximation par tuile spatiale**, pas un vrai flood-fill de
  blob de biome contigu (compromis assumés dans `docs/WAYPOINTS.md` §1 ; migration future possible
  sans changement de schéma via la colonne `biome_instance`).
- **Control Panel** : la lecture `/waypoints` prévue par #124 n'est **pas** livrée (priorité au
  moteur, temps insuffisant après). `WaypointService` expose déjà `all()` / `byId()` /
  `discoveryCount()` pour la brancher ensuite sans changement de schéma.
- **Protection fonctionnelle anti-enfermement de proximité = #122**, hors périmètre (architecture
  compatible : service propriétaire d'un ensemble de positions protégées, découplé du rendu).
- **Décision d'architecture** (nouveau package plutôt qu'évolution du système Waystone) à valider
  par l'owner — voir section Analyse. Réversible.

## Prochaine étape suggérée

1. Renseigner `scripts/verygames.env`, déployer sur DEV, redémarrer, exécuter TC-210.
2. Si #124 poursuivi : lecture `/waypoints` (lecture seule) ou action agent `waypoint.list` dans
   PlugAdmin.
3. Sinon : issue **#122** (protection fonctionnelle anti-enfermement + signal vertical repérable).
