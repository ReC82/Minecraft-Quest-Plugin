# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 14:23 (locale, UTC sur cette machine)
* Sujet : Spawn d'un PNJ Citizens depuis une définition RPGQuest — issue #81, **phase 2**
* Statut : DONE (chemin nominal `CREATED` couvert par les tests ; premier spawn réel = `PENDING MANUAL VALIDATION`)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : code `d9901fd` + docs `e56930c` + correctif NPE `b9f3beb` ; rapport + « Exécution réelle » du `SERVER_CHANGELOG` : commit de suivi
* Début de la tâche : 2026-09-08 13:55:26 (heure locale réelle)
* Fin de la tâche : 2026-09-08 14:52:00
* Durée totale : 00:56:34

## Demande

Ticket principal **#81 — phase 2 : préparer puis implémenter le spawn Citizens depuis `/npcs`**.
Permettre depuis `/npcs` de créer **physiquement** un PNJ Citizens à partir d'une `NpcDefinition`
existante, puis de le binder immédiatement à son id RPGQuest :
`NpcDefinition → Preview → npc.citizens.create → Citizens créé → binding RPGQuest → npc.list → LINKED`.
Pas de validation Minecraft manuelle possible → garde-fous forts + validation automatique maximale.

Exigences détaillées : audit de l'API Citizens (créer / nommer / spawn / numeric id / supprimer /
vérifier le registry) sans passer par une commande console si l'API le permet ; action mutation
`npc.citizens.create` à paramètres **métier uniquement** (`npc_id`, `world`, `x`, `y`, `z`, `yaw`,
`pitch` — yaw/pitch optionnels à défaut sûr), nom = `NpcDefinition.displayName`, le navigateur
n'envoie jamais de nom Citizens libre / commande console / YAML / chemin / UUID / script ;
**preview obligatoire** avant l'action (définition, nom, monde, coordonnées, orientation, état du
binding, confirmation qu'aucun Citizens n'est lié) + `confirm=true` ; **validations avant
création** : définition présente + valide + `enabled=true` + pas déjà liée (`NPC_ALREADY_LINKED`),
monde existant/chargé et autorisé (whitelist logique si possible, aucune création de monde
implicite), coordonnées finies + bornées + Y valide (jamais « corrigées » en silence), Citizens
disponible (`CITIZENS_UNAVAILABLE`) ; **sécurité de position** : uniquement mondes autorisés
RPGQuest, sinon documenter la limite et garder monde chargé + coordonnées bornées ; **threading** :
tout Citizens/Bukkit sur le thread principal, persistance DB async, étapes séparées ;
**atomicité/rollback** : si Citizens créé mais binding échoue → supprimer **uniquement** le
Citizens créé par cette action, jamais un préexistant ; **idempotence** : `npc_id` déjà lié →
refus propre, pas de détection heuristique par nom ; **résultat structuré**
(`success/failure`, `code`, `message`, `citizens_id`, `npc_id`, `effects`, `rolled_back`) ;
**Control Panel `/npcs`** : bloc « Créer le PNJ Citizens » sur les cartes `definition=OUI /
binding=NON`, formulaire monde + X/Y/Z + yaw + pitch, pré-remplissage seulement si source sûre
(sinon champs vides), monde en **liste déroulante** issue de l'agent/heartbeat ; **après succès** :
refresh `npc.citizens.list` + `npc.list`, carte → `LINKED` ; **pas de suppression Citizens
générale** (seul le rollback interne immédiat) ; **cas `woodcutter_bob`** : valider tout le
pipeline jusqu'à la preview/action sans spawn réel tant qu'aucune position sûre n'est fournie, ne
pas choisir soi-même une position en prod DEV ; **préparer la suite** (choix position depuis la
map, spawn réel, déplacement, suppression/recréation, téléportation admin) sans la coder ; tests
métier / bridge / agent / Control Panel ; `./gradlew :test`, `:control-panel:test`, `build` verts ;
déployer AWS + VeryGames DEV ; **ne pas fermer #81** ; hors scope : MariaDB, Claims, onboarding,
progression joueur, éditeur de dialogues, suppression générale Citizens, rebind, portail public,
nginx/TLS, merge vers `main`.

## Analyse

### Audit de l'API Citizens (`citizensapi:2.0.43-SNAPSHOT`, `compileOnly`)

- **Créer** : `NPCRegistry.createNPC(EntityType, String name)` — crée un PNJ (non spawné), nom
  posé. `createNPC(EntityType, String, Location)` crée + spawn.
- **Nom** : passé à `createNPC` ; `NPC.setName(String)` sinon.
- **Spawn** : `NPC.spawn(Location)` / `NPC.spawn(Location, SpawnReason)` — `SpawnReason.CREATE`
  disponible.
- **Numeric id** : `NPC.getId()` (affiché aux admins, non garanti stable entre sessions).
- **UUID** : `NPC.getUniqueId()` (clé stable de persistance).
- **Supprimer (rollback)** : `NPC.destroy()` — despawn + retrait définitif du registre.
  `NPCRegistry.deregister(NPC)` existe aussi. **`destroy()` retenu** (le plus complet).
- **Vérifier l'enregistrement** : `NPCRegistry.getById(int)` / `getByUniqueId(UUID)`.
- **Persistance** : `NPCRegistry.saveToStore()` après `create`/`destroy`.
- **Aucune commande console nécessaire** : toute l'opération passe par l'API.

Toute référence `net.citizensnpcs.*` reste isolée dans `CitizensNpcBridge` (package-private,
instancié uniquement si Citizens est actif — inchangé).

### Décisions

1. **Deux classes pures, aucune dépendance Bukkit/SQL** (testables sans serveur) :
   - `CitizensSpawnPlanner.plan(...)` : **toutes** les préconditions logiques avant la moindre
     création. Ordre : Citizens actif → définition présente → `enabled` → `npc_id` pas déjà lié →
     monde de la liste blanche → position finie et bornée. Une position invalide est **refusée**
     (`INVALID_POSITION`), jamais « corrigée ». `positionError(...)` réutilisé côté panel.
   - `CitizensSpawnCoordinator.run(npcId, positionLabel, Spawner, Binder)` : transaction
     applicative `create → bind → success`, sinon `delete Citizens créé → failure`. `Spawner` et
     `Binder` renvoient des `CompletableFuture` (l'implémentation réelle hoppe sur le thread
     principal ; les tests complètent synchrones).
2. **Liste blanche des mondes** : les **trois mondes RPGQuest de la config** (`hub().world()`,
   `claims().world()`, `travel().wildWorld()`), injectés dans `BukkitAgentActions` via un
   `Supplier<Set<String>>` (aucun couplage à `PluginConfig`). Pas de notion d'« admin world »
   dans le projet → **pas d'ACL géographique** ; le monde doit en plus être **chargé** et `y`
   dans `[minHeight, maxHeight)` du monde réel (garde-fou secondaire sur le thread principal).
   Le panel propose en **liste déroulante** les mondes annoncés `loaded:true` par le heartbeat
   (`worlds_json`) — convenance UX ; l'autorité reste le plugin.
3. **Rollback ciblé** : `CitizensNpcBridge.destroyIfMatches(numericId, uuid)` ne détruit que si
   **les deux** clés correspondent — impossible de toucher un PNJ préexistant. Appelé uniquement
   par le coordinator quand le binding échoue après création.
4. **Réutilisation phase 1** : le binding passe par `NpcIdentityService.bindCitizens` (donc
   `CitizensBindPlanner` + `insertIfAbsent` + rafraîchissement du cache), qui protège aussi
   contre une course `NPC_ID_TAKEN` / `CITIZENS_TAKEN` survenant entre les pré-checks et
   l'écriture → dans ce cas on **rollback** le PNJ qu'on venait de créer.
5. **Permission dédiée `NPC_SPAWN_WRITE`** (distincte de `NPC_BIND_WRITE` de la phase 1) : créer
   une entité physique est un cran de risque au-dessus de lier une entité existante. `OWNER` la
   possède via `EnumSet.allOf`.
6. **Sémantique de résultat** : succès `CREATED` (`value = citizens_id`) ; sinon
   `AgentActionOutcome.FAILED` + code + message + `details.{code, npc_id, citizens_id, world,
   rolled_back, effects}` — patron cohérent avec les autres mutations. Codes :
   `CITIZENS_UNAVAILABLE`, `UNKNOWN_NPC`, `NPC_DISABLED`, `NPC_ALREADY_LINKED`, `UNKNOWN_WORLD`,
   `INVALID_POSITION`, `CREATE_FAILED`, `BIND_FAILED_ROLLED_BACK`, `ERROR`.

## Travail effectué

- Audit API Citizens (ci-dessus).
- **`CitizensSpawnPlanner`** (nouveau, pur) + **`CitizensSpawnCoordinator`** (nouveau, pur).
- **`CitizensNpcBridge`** : `createAndSpawn(String, Location)` (crée `PLAYER`, `spawn(loc, CREATE)`,
  nettoie le PNJ à moitié créé si le spawn échoue, `saveToStore()`), `destroyIfMatches(int, UUID)`,
  `safeDestroy`.
- **`NpcIdentityService`** : `createCitizensNpc(String, Location)`, `destroyCitizensNpc(int, UUID)`
  (délèguent au bridge ; vides/false si Citizens inactif).
- **`AgentActions`** : record `CitizensCreateResult` + `citizensCreate(npcId, world, x, y, z,
  yaw, pitch)`.
- **`AgentActionType`** : `NPC_CITIZENS_CREATE("npc.citizens.create")`.
- **`BukkitAgentActions`** : `Supplier<Set<String>> allowedSpawnWorlds` (ctor) ; `citizensCreate`
  orchestre `loadAll` (async) → `CitizensSpawnPlanner` (pur) → `onMain` (monde chargé + Y réel) →
  `CitizensSpawnCoordinator.run` avec un `Spawner` qui hoppe sur le thread principal pour
  `createCitizensNpc` / `destroyCitizensNpc` et un `Binder = npcIdentityService::bindCitizens`.
- **`AgentActionExecutor`** : `WORLD_NAME` pattern, `case NPC_CITIZENS_CREATE`,
  `npcCitizensCreate` (valide `npc_id` / `world` / `x`/`y`/`z` finis / `yaw`/`pitch` finis à
  défaut `0`), résultat structuré.
- **`RPGQuestBootstrap`** : `rpgWorldWhitelist()` (hub/claims/exploration de la config) passé au
  constructeur de `BukkitAgentActions`.
- **Control Panel** : `Permission.NPC_SPAWN_WRITE` ; `AgentActionCatalog` entrée
  `npc.citizens.create` (`NPC_SPAWN_WRITE`, mutation) + `validate` (format monde, coordonnées
  finies, bornes miroir du plugin, yaw/pitch défaut `0`, pitch `[-90, 90]`) ; `AgentPages` :
  `loadedWorldNames(agentId)` depuis le heartbeat, `canSpawn`, bloc `<details>` « Créer le PNJ
  Citizens » sur les cartes définies `NOT_LINKED` `enabled` → `citizensCreateForm` (**preview**
  id + nom + « aucun Citizens lié », monde en liste déroulante ou champ libre si heartbeat
  absent, X/Y/Z/yaw/pitch à saisir, `confirmBox`, résultat du dernier spawn). `Layout` : CSS
  `.preview` / `.coord-row` (responsive).
- **Documentation** (voir § dédié).
- **Build & tests locaux** : `./gradlew build` → **BUILD SUCCESSFUL**
  (`:test` **1165 / 0 échec / 29 ignorés** ; `:control-panel:test` **111 / 0 / 0**).
- **Déploiement** AWS + VeryGames DEV + validation live — la validation live a révélé un
  **bug de sérialisation** (`AgentActionOutcome` recopie ses détails via `Map.copyOf`, qui
  **refuse les valeurs `null`** ; `details.put("citizens_id", null)` sur les chemins de refus →
  `NullPointerException`). Corrigé : `citizens_id` vaut désormais `-1` quand aucun PNJ Citizens
  n'est impliqué (refus avant création). Test de régression ajouté
  (`NpcCitizensPayloadTest#citizensCreateRejectionWithoutCitizensIdStillProducesAValidOutcome`).
  Redéployé, revalidé (voir § « Logs / diagnostic »).

## Fichiers créés

Plugin :

- `src/main/java/com/lodygames/rpgquest/npc/CitizensSpawnPlanner.java`
- `src/main/java/com/lodygames/rpgquest/npc/CitizensSpawnCoordinator.java`
- `src/test/java/com/lodygames/rpgquest/npc/CitizensSpawnPlannerTest.java` (14 tests)
- `src/test/java/com/lodygames/rpgquest/npc/CitizensSpawnCoordinatorTest.java` (4 tests)

Documentation :

- `docs/claude-reports/2026-09-08_1423_npc-citizens-create-81-phase2.md` (ce fichier)

## Fichiers modifiés

Plugin :

- `src/main/java/com/lodygames/rpgquest/npc/CitizensNpcBridge.java`
- `src/main/java/com/lodygames/rpgquest/npc/NpcIdentityService.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/StubAgentActions.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/AgentActionExecutorTest.java` (+2 tests + fake)
- `src/test/java/com/lodygames/rpgquest/web/agent/NpcCitizensPayloadTest.java` (+2 tests + stub)

Control Panel :

- `control-panel/src/main/java/com/lodygames/rpgquest/panel/authz/Permission.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalog.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/Layout.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/NpcsCatalogTest.java` (+2 tests + helper)

Documentation :

- `docs/control-panel/AGENT.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
  `docs/control-panel/ROADMAP.md`, `NPC_FORMAT.md`,
  `docs/deployment/SERVER_CHANGELOG.md` (entrée #81 phase 2 + « Exécution réelle » en suivi),
  `docs/claude-reports/README.md` (index)

## Base de données / migrations

**Aucune migration.** La table `npc_citizens_bindings` (migration V12) est réutilisée telle
quelle. `npc.citizens.create` y fait au plus un `INSERT OR IGNORE` (non destructif) via la couche
métier de la phase 1. Aucune colonne, aucun index ajouté.

## Configuration / données

Aucun nouveau fichier de configuration. Aucun format YAML modifié. Aucune commande en jeu
ajoutée. La liste blanche des mondes de spawn est **dérivée** de la config existante
(`hub`/`claims`/`travel.wildWorld`), pas d'un nouveau réglage. Les fichiers **Citizens**
(`plugins/Citizens/saves.yml` ou base) évoluent dès qu'un PNJ est **réellement** créé.

## Tests automatiques

- `./gradlew build` (AWS, `GRADLE_OPTS` plafonné) → **BUILD SUCCESSFUL**.
  - Plugin `:test` : **1165 / 0 échec / 29 ignorés** (les 29 ignorés sont les `PENDING MANUAL`
    préexistants).
  - `:control-panel:test` : **111 / 0 / 0**.
- Couverture ajoutée :
  - `CitizensSpawnPlannerTest` : PROCEED ; monde insensible à la casse ; refus
    `CITIZENS_UNAVAILABLE` / `UNKNOWN_NPC` / `NPC_DISABLED` / `NPC_ALREADY_LINKED` /
    `UNKNOWN_WORLD` (hors whitelist et blanc) / `INVALID_POSITION` (NaN, Infinity, X/Z hors bord,
    Y hors bornes, pitch > 90, yaw non fini) ; **ordre** des contrôles (définition avant monde
    avant position) ; helper `positionError`.
  - `CitizensSpawnCoordinatorTest` : `create → bind OK` = `CREATED` sans rollback ; échec de
    spawn = `CREATE_FAILED` sans tentative de bind ; `bind` échoue après création → rollback qui
    vise **exactement** le PNJ créé (`BIND_FAILED_ROLLED_BACK`, `rolled_back=true`) ; rollback
    lui-même en échec → message « NON supprimé » pour intervention manuelle.
  - `AgentActionExecutorTest` : `npc.citizens.create` valide `npc_id` / `world` / coordonnées
    finies / présence de `x` ; SUCCESS délègue avec les bons paramètres et expose
    `citizens_id`/`rolled_back` ; échec de binding = `FAILED` lisible avec `rolled_back=true`.
  - `NpcCitizensPayloadTest` : payload JSON réel de `npc.citizens.create` (whitelist, params
    invalides → `REJECTED` sans appel métier, SUCCESS structuré `code`/`citizens_id`/`npc_id`/
    `rolled_back`, cas rollback → `FAILED` `BIND_FAILED_ROLLED_BACK`, **refus sans `citizens_id`**
    → `FAILED` avec `citizens_id: -1`, aucun NPE de sérialisation).
  - `NpcsCatalogTest` : formulaire de spawn **uniquement** sur une carte définie `NOT_LINKED`
    `enabled` (jamais sur `LINKED`) ; mondes proposés = mondes **chargés** du heartbeat (monde
    non chargé exclu) ; preview « aucun Citizens lié » ; champs de coordonnées présents ;
    `confirm` obligatoire ; coordonnée non finie / Y hors bornes → refusés à la validation.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — nécessitent un client Minecraft, à faire par l'owner :

1. `/npcs` : sur une carte définie `NOT_LINKED` (`woodcutter_bob`), le bloc « Créer le PNJ
   Citizens » affiche la preview (id, nom, « aucun Citizens lié ») et la liste des mondes
   chargés.
2. Fournir une **position explicite et sûre** (ex. dans `world_hub`), confirmer → `SUCCESS
   CREATED` ; un PNJ Citizens apparaît en jeu ; `npc.list` suivant → `state: LINKED` ;
   l'interaction/dialogue du PNJ fonctionne **sans redémarrage**.
3. Re-soumettre pour le même `npc_id` → refus `NPC_ALREADY_LINKED` (aucun second PNJ).
4. Monde hors whitelist / Y aberrant / définition désactivée → refus lisible, **aucun PNJ créé**.
5. Cas rollback (difficile à provoquer sans course) : vérifier qu'un `BIND_FAILED_ROLLED_BACK`
   ne laisse **aucun** PNJ Citizens résiduel.
6. Responsive : preview + `select` monde + grille de coordonnées + `confirmBox` lisibles en
   largeur mobile.

## Résultat attendu

- Un admin peut, **sans être en jeu**, faire apparaître un PNJ Citizens à partir d'une
  `NpcDefinition` et le lier en une action, à condition que **toutes** les validations passent.
- Une position invalide, un monde non autorisé, une définition absente/désactivée, un `npc_id`
  déjà lié → **refus lisible, aucune entité créée**.
- Un échec de liaison après création → le PNJ créé est **détruit** (aucun PNJ physique
  orphelin) ; un préexistant n'est jamais touché.
- Aucune suppression Citizens générale n'est possible par cette action.

## Reset / retour à l'état initial

- Un PNJ Citizens **réellement** créé se retire par `/npc select <id>` + `/npc remove` en jeu
  **puis** `DELETE FROM npc_citizens_bindings WHERE citizens_uuid = ?` (ou `/rpgadmin npc untag`
  en visant l'entité). Non exposé par cette phase.
- Revenir au code d'avant : `git revert b9f3beb d9901fd` (correctif NPE + feature). Aucune donnée
  à nettoyer si aucun spawn réel n'a eu lieu.

## Déploiement VeryGames

### À transférer

- **Le seul JAR RPGQuest** `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` via
  `scripts/deploy-verygames.sh -y` (backup automatique de l'ancien JAR).
- Côté AWS : `scripts/plugadmin/deploy.sh` (le Control Panel embarque `NPC_SPAWN_WRITE` +
  `npc.citizens.create`).

### Ne PAS transférer/altérer

- `plugins/RPGQuest/data.db` (sauvegardé par précaution, **non remplacé**).
- `plugins/Citizens/*`, `plugins/RPGQuest/npcs/*.yml`, les mondes, la config, la progression
  joueur.

### Redémarrage requis

Oui — `scripts/verygames-restart.sh` (`stop` RCON → relance auto VeryGames) pour que l'agent
charge le nouveau type d'action.

### Migration automatique

Aucune.

## Rollback

- **VeryGames** : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`
  (restaure `…144029Z-predeploy.jar`).
- **AWS** : `scripts/plugadmin/rollback.sh app` (release précédente `20260908-143939` conservée).
- Aucune migration à défaire. Un PNJ Citizens éventuellement créé se retire manuellement (ci-dessus).

## Logs / diagnostic

Déploiement du 2026-09-08 (~14:22–14:45 UTC), branche `feat/control-panel-admin-tools`.

### AWS (Control Panel)

- `scripts/plugadmin/deploy.sh` ×2 (une fois pour `e56930c`, une fois après le correctif NPE
  `b9f3beb` — le correctif est **côté plugin**, le JAR panel est resté identique).
  Release `/opt/plugadmin/releases/20260908-143939` ; `control-panel-0.1.0-SNAPSHOT.jar`
  SHA-256 `bff44c5b08c86a89b6afd989db4a2589e5e2a40edcb232e677244c9db33bec65` (== build local).
- `NPC_SPAWN_WRITE` + `npc.citizens.create` présents dans le JAR déployé.
- `/health` public **ONLINE** ×3 ; `/npcs` anon → 303 ; `dig.lodygames.com` / `lodylands.com` →
  200 (inchangés).

### VeryGames DEV — 1re passe (`e56930c`) : bug découvert

- `deploy-verygames.sh -y` (JAR SHA-256 `4492516691e9cd38ea3158ed81743113b6dc52bf7884d35a7cff3e45e62fcca5`,
  backup `…142316Z-predeploy.jar`) + `verygames-restart.sh` → ONLINE ; RPGQuest + Citizens verts ;
  heartbeat `14:25:51Z` ONLINE ; `npc.list` / `npc.citizens.list` **SUCCESS**.
- `npc.citizens.create` (4 refus injectés, aucun spawn) → **tous `FAILED` avec message
  « Échec : NullPointerException »**. Cause : `AgentActionExecutor` plaçait un `citizens_id`
  **`null`** dans les détails de l'outcome sur les chemins de refus, or `AgentActionOutcome`
  recopie ses détails via `Map.copyOf` (**valeurs `null` interdites**).

### Correctif + 2e passe (`b9f3beb`)

- `citizens_id` vaut désormais `-1` quand aucun PNJ Citizens n'est impliqué. Test de régression
  ajouté. `./gradlew build` → **BUILD SUCCESSFUL** (`:test` 1165/0/29 ignorés).
- `deploy-verygames.sh -y` (JAR SHA-256
  `6a7ff0d4b2147f8153c05bed954a93df35f0cb6cf60033152840b7f31f4d94a9`, 1 349 308 o,
  backup `…144029Z-predeploy.jar`) + `verygames-restart.sh` → **ONLINE**.
- `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` verts ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`.
- Heartbeat agent `2026-09-08T14:41:43Z` : `ONLINE`, plugin `0.1.0-SNAPSHOT` ; aucun `ERROR`
  dans `journalctl -u plugadmin`.
- `npc.list` → **SUCCESS** (8 PNJ) ; `npc.citizens.list` → **SUCCESS** (7 Citizens, 0 libre).
- `npc.citizens.create` — **aucun spawn réel** (consigne : pas de position sûre explicite sur
  DEV) :

  | `npc_id` | `world` | `x/y/z` | Résultat |
  |---|---|---|---|
  | `guard` (déjà lié à Citizens #6) | `world_hub` | `0/64/0` | FAILED `NPC_ALREADY_LINKED` |
  | `woodcutter_bob` | `the_nether` | `0/64/0` | FAILED `UNKNOWN_WORLD` (« hors de la liste blanche RPGQuest (claims, wild, world_hub) ») |
  | `woodcutter_bob` | `world_hub` | `0/99999/0` | FAILED `INVALID_POSITION` (« Y=99999 hors bornes de sécurité (-2048 à 2048) ») |
  | `does_not_exist_xyz` | `world_hub` | `0/64/0` | FAILED `UNKNOWN_NPC` ; `details.citizens_id = -1` (plus de NPE) |
  | `woodcutter_bob` | `world_hub` | `NaN/64/0` | REJECTED (`AgentActionExecutor`, avant la couche métier) |

- **Chemin nominal `CREATED` non exercé en direct** : aucune position sûre fournie, consigne de
  ne pas en choisir une en prod DEV. Couvert par `CitizensSpawnPlannerTest`,
  `CitizensSpawnCoordinatorTest`, `AgentActionExecutorTest`, `NpcCitizensPayloadTest`,
  `NpcsCatalogTest`.
- Aucun PNJ Citizens créé ni supprimé ; aucune progression joueur touchée ; aucune migration.

## Documentation mise à jour

- `docs/control-panel/AGENT.md` : ligne de tableau `npc.citizens.create` + section « Spawn d'un
  PNJ Citizens depuis une définition (`npc.citizens.create` — issue #81, phase 2) » (préconditions
  `CitizensSpawnPlanner`, orchestration `CitizensSpawnCoordinator`, rollback ciblé, threading,
  hors périmètre).
- `docs/RPGQUEST_BIBLE.md` §5 : mention de `npc.citizens.create` et de son périmètre.
- `docs/current_state.md` : puce « Spawn d'un PNJ Citizens depuis une définition (issue #81
  phase 2) ».
- `docs/control-panel/ROADMAP.md` : puce Étape 3 « Spawn d'un PNJ Citizens depuis une
  définition (#81, phase 2) » + « Reste ».
- `NPC_FORMAT.md` : « trois façons de poser un binding » (tag en jeu / lier existant / créer +
  lier).
- `docs/deployment/SERVER_CHANGELOG.md` : entrée « 2026-09-08 — Spawn d'un PNJ Citizens depuis
  une définition — issue #81 (phase 2) » + « ### Exécution réelle » (à renseigner au déploiement).
- `docs/claude-reports/README.md` : ligne d'index de ce rapport.

## Limitations / travail restant

- **Premier spawn réel non validé en jeu** (aucune position sûre fournie, consigne de ne pas en
  choisir une en prod DEV). À faire par l'owner (§ « Tests manuels »).
- **Pas d'ACL géographique** : la seule contrainte de zone est « monde de la liste blanche
  RPGQuest + monde chargé + Y dans les limites du monde ». Un raffinement (zone hub, distance au
  spawn…) serait un chantier séparé.
- **Suite possible (documentée, non codée)** :
  - `npc.citizens.delete` — vraie action admin de suppression d'un PNJ Citizens (aujourd'hui
    seul le rollback interne immédiat supprime). Nécessiterait : sélection par `npc_id` ou
    `citizens_id`, `confirm`, permission dédiée, nettoyage `npc_citizens_bindings`, validation en
    jeu.
  - **Rebind / déplacement** d'un binding ou d'un PNJ existant (téléport Citizens + mise à jour
    éventuelle) — explicitement hors périmètre phases 1 et 2.
  - **Choix de position depuis une carte / l'admin en jeu** : pousser la position du joueur admin
    (`/rpgadmin npc here <id>` ou un bouton panel qui lit le heartbeat enrichi) plutôt qu'une
    saisie manuelle.
  - **Téléportation admin vers le PNJ** après création (confort de vérification).
  - **Enrichissement live** du roster Citizens (position, monde, PNJ non tagués) — nécessite une
    lecture du monde sur le thread principal, volontairement absente jusqu'ici.

## Prochaine étape suggérée

1. Owner : exécuter le premier spawn réel avec une position explicite dans `world_hub` (ex.
   `woodcutter_bob`), vérifier `LINKED` + interaction en jeu + absence de PNJ orphelin sur un
   cas d'échec.
2. Spécifier `npc.citizens.delete` (#81 suite) à partir de la section « Limitations ».
3. Optionnel : bouton « utiliser ma position » dans le formulaire de spawn (nécessite un
   heartbeat qui transporte la position de l'admin, ou une action de lecture dédiée).
