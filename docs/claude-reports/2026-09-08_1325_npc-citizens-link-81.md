# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 13:25 (locale, UTC sur cette machine)
* Sujet : Lier une définition PNJ logique à un PNJ Citizens existant — issue #81, **phase 1** uniquement
* Statut : DONE (chemin nominal `LINKED` couvert par les tests, non exerçable en direct faute de PNJ Citizens libre sur DEV)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `3d1f50c` (code : `3843238` ; ce rapport + `SERVER_CHANGELOG` « Exécution réelle » : commit de suivi)
* Début de la tâche : non mesurable (session reprise après compaction du contexte ; l'implémentation code+tests+docs s'est faite avant la reprise)
* Fin de la tâche : 2026-09-08 13:30:00
* Durée totale : non calculable (début non mesurable). Segment observable « déploiement + validation live + rapport » : 2026-09-08 13:19:00 → 13:30:00 (~00:11:00).

## Demande

Ticket principal **#81 — « lier une définition PNJ à un Citizens existant puis préparer le
spawn depuis `/npcs` »**. La tâche ne traite **que la phase 1** (lier une définition à un PNJ
Citizens **déjà créé**). Pas de validation Minecraft manuelle possible côté demandeur.

Exigences :

- Auditer l'intégration Citizens (`CitizensNpcBridge`, `NpcBindingRepository`,
  `NpcIdentityService`, schéma `npc_citizens_bindings`, `/rpgadmin npc tag`). Préférer l'**API
  Registry Citizens** à un scan d'entités/chunks.
- Action agent **lecture** `npc.citizens.list` : `citizens[]` = `{numericId, uuid, name,
  linkedNpcId?, availableForBinding, spawned}` ; pas de position/monde si cela force un
  chargement de chunk.
- Action agent **mutation** `npc.citizens.link` : params `npc_id`, `citizens_id` (numérique
  suffisant). Le navigateur n'envoie jamais de SQL, d'UUID arbitraire, de commande console ou
  de chemin de fichier.
- Validation métier **avant** binding : `npc_id` doit exister dans le registre des définitions et
  être `enabled` ; `citizens_id` doit vraiment exister dans le registre Citizens ; **collision
  Citizens** (déjà lié à un autre `npc_id`) → refus lisible, pas de réaffectation silencieuse ;
  **collision RPGQuest** (`npc_id` déjà lié à un autre Citizens) → refus aussi (pas de rebind en
  phase 1) ; **idempotence** (binding exactement identique) → succès/no-op explicite.
- Persistance via `NpcBindingRepository` (pas de SQL direct dans `AgentActionExecutor`), méthode
  métier `bind(...)` avec validation interne, mutation atomique.
- Threading : interaction Citizens potentiellement thread principal (`onMain`) ; persistance DB
  asynchrone ; ne pas bloquer le thread principal avec SQLite.
- Control Panel `/npcs` : sur une carte « définition OUI / binding NON » → formulaire « Lier un
  PNJ Citizens existant » avec un `select` des Citizens (afficher `#ID`, nom, liaison actuelle) ;
  seuls les Citizens libres sélectionnables ; confirmation obligatoire. Sur un PNJ `LINKED` →
  afficher `Binding Citizens #N — Nom`, **pas** de bouton de modification, mention qu'une
  procédure de rebind viendra plus tard.
- Orphelins Citizens (`guide`, `help`, `jeff`, `jo`, `junior`, `libraire` : Citizens sans
  définition) → **ne pas les lier automatiquement** ; une fois la définition créée, un binding
  déjà présent doit être reconnu automatiquement — n'ajouter aucune mutation inutile si c'est
  déjà le comportement naturel.
- Cas `woodcutter_bob` (définition OUI, binding NON) = cas de validation principal. N'utiliser un
  Citizens DEV existant **que si** son identité est claire ; sinon ne pas créer de Citizens, ne
  pas détourner un autre PNJ.
- Préparer (documenter, **ne pas coder**) une future action `npc.citizens.create`.
- Permissions : `NPC_BIND_WRITE` (ou équivalent) pour la mutation ; lecture = `NPC_READ`.
- Audit/sécurité : action whitelistée, `confirm` obligatoire, trace d'audit, résultat structuré,
  message clair, jamais de commande console, jamais de donnée libre dangereuse.
- Tests plugin + payloads JSON agent + Control Panel. `./gradlew :test`,
  `./gradlew :control-panel:test`, `./gradlew build` verts.
- Déployer : AWS `scripts/plugadmin/deploy.sh` ; VeryGames DEV `scripts/deploy-verygames.sh -y`
  puis `scripts/verygames-restart.sh`. Ne créer ni supprimer de PNJ Citizens. Ne pas toucher à
  la progression joueur.
- GitHub : ne fermer aucun ticket ; documenter #81 (et #66 si du code réutilisable est touché) ;
  ne pas créer de ticket sauf problème critique distinct.
- Hors périmètre strict : spawn Citizens ; suppression Citizens ; rebind/remplacement d'un
  binding existant ; suppression de `NpcDefinition` ; éditeur de dialogues ; MariaDB ; Claims ;
  onboarding ; progression joueur ; portail public ; nginx/TLS ; merge vers `main`.

## Analyse

### État avant intervention

- **`CitizensNpcBridge`** (package-private, `com.lodygames.rpgquest.npc`) isole **tous** les
  imports `net.citizensnpcs.*`. Instancié uniquement si Citizens est présent (test via
  `Bukkit.getPluginManager()`). Fournissait déjà `currentId(entity)` / résolution d'identité,
  mais **aucune énumération du registre**.
- **`NpcBindingRepository`** (`com.lodygames.rpgquest.database`) : `loadAll()` →
  `List<Binding>` (`Binding{citizens_uuid, citizens_numeric_id, npc_id, created_at}`), plus un
  `UPSERT` `ON CONFLICT(citizens_uuid) DO UPDATE` — c.-à-d. un **rebind silencieux**, inadapté à
  la phase 1.
- **`NpcIdentityService`** : détient un `@Nullable CitizensNpcBridge`, un cache
  `Map<UUID,String> citizensCache` (peuplé au démarrage depuis `loadAll()`, lu par `currentId()`
  sur le thread principal pour l'identification en jeu). Pas de méthode d'écriture de binding.
- **Schéma `npc_citizens_bindings`** (migration V12) :
  `citizens_uuid TEXT PRIMARY KEY, citizens_numeric_id INTEGER NOT NULL, npc_id TEXT NOT NULL,
  created_at TEXT NOT NULL`. `npc_id` **non unique** (doublons possibles → drapeau
  `DUPLICATE_BINDING` côté catalogue). Clé stable = `NPC#getUniqueId()`.
- **`/rpgadmin npc tag <id>`** : pose un binding en visant l'entité Citizens regardée par
  l'admin. C'est l'unique voie d'écriture existante ; `npc.citizens.link` en devient une seconde,
  pilotable sans être en jeu.
- **`NpcCatalog.build(...)`** (Tâche #66/#75, PNJ V2) croise déjà définitions logiques
  (`npcs/*.yml`) ↔ bindings ↔ `giver:` ↔ `TALK_TO_NPC` par **id**, et produit `state ∈
  {LINKED, NOT_LINKED, DISABLED, CITIZENS_ORPHAN, UNDEFINED_REFERENCE, BROKEN}`.

### Décisions

1. **Logique de collision extraite dans une classe pure** `CitizensBindPlanner` (aucune
   dépendance Bukkit/SQL) : entrée `(npcId, citizensUuid, citizensNumericId, List<Binding>)`,
   sortie `Plan{action ∈ INSERT|NOOP|REJECT, code, message}`. Rend toutes les branches
   (idempotence, `CITIZENS_TAKEN`, `NPC_ID_TAKEN`) testables sans serveur.
2. **Écriture atomique non destructive** : nouvelle méthode
   `NpcBindingRepository.insertIfAbsent(uuid, numericId, npcId)` = `INSERT OR IGNORE` (réécrit
   par `SqlDialect` en `INSERT IGNORE` pour MySQL), renvoie `true` si une ligne a été insérée.
   L'`UPSERT` existant (rebind) reste **inutilisé** par cette action.
3. **Séparation stricte des threads** :
   - `npc.citizens.list` : `NpcBindingRepository.loadAll()` (asynchrone) → `onMain` pour
     parcourir `CitizensAPI.getNPCRegistry()` → fusion des deux hors thread principal.
   - `npc.citizens.link` : vérif définition (synchrone, en mémoire) → `onMain` pour résoudre
     `citizens_id` → `NpcIdentityService.bindCitizens(...)` **asynchrone** (`loadAll` + planner +
     `insertIfAbsent` + `citizensCache.put`). Aucun accès SQLite sur le thread principal.
4. **`CitizensNpc` promu en record public top-level** (`com.lodygames.rpgquest.npc.CitizensNpc`,
   `{numericId, uuid, name, spawned}`) : le package `web.agent` doit pouvoir le nommer. L'ancien
   record imbriqué package-private de `CitizensNpcBridge` a été supprimé.
5. **Permission dédiée** `NPC_BIND_WRITE` (distincte de `NPC_WRITE`) : la liaison à une entité
   physique est un cran de risque au-dessus de l'édition d'un YAML logique. `OWNER` l'obtient via
   `EnumSet.allOf(...)`.
6. **Orphelins : aucune mutation ajoutée.** `NpcCatalog.build` croise déjà binding + définition
   par id ; créer `npcs/guide.yml` fait passer `guide` en `LINKED` au prochain `npc.list` sans
   action supplémentaire. Confirmé, documenté (`AGENT.md`).
7. **Sémantique de résultat alignée sur l'existant** : une précondition métier non remplie
   (`UNKNOWN_NPC`, `DISABLED`, `CITIZENS_UNAVAILABLE`, `UNKNOWN_CITIZENS`, `CITIZENS_TAKEN`,
   `NPC_ID_TAKEN`) donne `AgentActionOutcome.FAILED` + `code` + `message` lisible +
   `details.code` — exactement le patron de `toOutcome(...)` déjà utilisé par `quest.start`,
   `player.variable.set`, etc. L'idempotence (`NOOP`) est un **succès** explicite.

## Travail effectué

- Audit complet de la chaîne Citizens (ci-dessus).
- `CitizensNpc` : record public top-level ; `CitizensNpcBridge` : `roster()`, `byNumericId(int)`,
  `toSummary(NPC)` (les seuls points qui touchent `CitizensAPI.getNPCRegistry()`).
- `NpcBindingRepository.insertIfAbsent(...)` : `INSERT OR IGNORE`, renvoie `boolean`.
- `NpcIdentityService` : `citizensRoster()`, `citizensByNumericId(int)`,
  `bindCitizens(npcId, CitizensNpc) : CompletableFuture<BindResult>` (via `CitizensBindPlanner`,
  met à jour `citizensCache` en cas d'`INSERT`), record `BindResult{ok, code, message,
  citizensNumericId}`.
- `CitizensBindPlanner` (pur) : `plan(...) → Plan{INSERT|NOOP|REJECT, code, message}`.
- `AgentActions` (façade) : records `CitizensNpcSummary`, `CitizensRosterView` ;
  `citizensRoster()` ; `citizensLink(npcId, int)`.
- `AgentActionType` : `NPC_CITIZENS_LIST("npc.citizens.list")`,
  `NPC_CITIZENS_LINK("npc.citizens.link")`.
- `BukkitAgentActions` : implémentation des deux (threading décrit plus haut). `citizensLink`
  refuse tôt si la définition est absente / désactivée / Citizens inactif / `citizens_id`
  introuvable, puis délègue à `bindCitizens`.
- `AgentActionExecutor` : `npcCitizensList(action)` (sérialise le roster en `LinkedHashMap`),
  `npcCitizensLink(action)` (valide `npc_id` contre le motif `[a-z0-9._-]{1,64}`, `citizens_id`
  entier positif borné ≤ 10 000 000), `parsePositiveInt(...)`.
- **Control Panel** :
  - `Permission.NPC_BIND_WRITE`.
  - `AgentActionCatalog` : `npc.citizens.list` (lecture, `NPC_READ`, non-mutation),
    `npc.citizens.link` (mutation, `NPC_BIND_WRITE`, `confirm` obligatoire) + `validate()` (motif
    `npc_id`, entier `citizens_id` 1..10 000 000).
  - `AgentPages.npcs()` : bouton « Rafraîchir les PNJ Citizens » (`npc.citizens.list`), ligne de
    résumé (`total` / `available` / `linked`), passe le roster à `renderNpcCard`.
  - `renderNpcCard` : bloc « Binding Citizens » revu — `LINKED` → `#N — Nom` (nom résolu depuis
    le roster) + `<p class="faint">Pour changer ce binding, une procédure de rebind sera ajoutée
    ultérieurement.</p>` ; `définition OUI / binding NON` → « aucun — définition prête, PNJ
    Citizens à lier » + formulaire `citizensLinkForm(...)` sous `<details>` (uniquement si
    `canLink && hasDefinition && !boundCitizens && enabled`).
  - `citizensLinkForm(...)` : `select` des Citizens ; option `disabled` pour les Citizens
    occupés (avec « déjà lié à `<npcId>` ») ; `confirmBox` obligatoire ; affiche la dernière
    liaison tentée.
- Documentation (voir § « Documentation mise à jour »).
- **Build & tests locaux** : `./gradlew build` → **BUILD SUCCESSFUL** (`:test` 1142/0/0 ;
  `:control-panel:test` 109/0/0).
- **Déploiement** AWS + VeryGames DEV + validation live (voir § « Déploiement » et « Logs /
  diagnostic »).

## Fichiers créés

Plugin :

- `src/main/java/com/lodygames/rpgquest/npc/CitizensNpc.java`
- `src/main/java/com/lodygames/rpgquest/npc/CitizensBindPlanner.java`
- `src/test/java/com/lodygames/rpgquest/npc/CitizensBindPlannerTest.java` (5 tests)
- `src/test/java/com/lodygames/rpgquest/web/agent/NpcCitizensPayloadTest.java` (3 tests)

Documentation :

- `docs/claude-reports/2026-09-08_1325_npc-citizens-link-81.md` (ce fichier)

## Fichiers modifiés

Plugin :

- `src/main/java/com/lodygames/rpgquest/database/NpcBindingRepository.java` — `insertIfAbsent(...)`
- `src/main/java/com/lodygames/rpgquest/npc/CitizensNpcBridge.java` — `roster()`, `byNumericId(int)`,
  `toSummary(NPC)` ; suppression du record imbriqué `CitizensNpc`
- `src/main/java/com/lodygames/rpgquest/npc/NpcIdentityService.java` — `citizensRoster()`,
  `citizensByNumericId(int)`, `bindCitizens(...)`, record `BindResult`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java` — `CitizensNpcSummary`,
  `CitizensRosterView`, `citizensRoster()`, `citizensLink(...)`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java` — `NPC_CITIZENS_LIST`,
  `NPC_CITIZENS_LINK`
- `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java` — implémentations
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java` — `npcCitizensList`,
  `npcCitizensLink`, `parsePositiveInt`
- `src/test/java/com/lodygames/rpgquest/web/agent/StubAgentActions.java` — stubs des 2 méthodes
- `src/test/java/com/lodygames/rpgquest/web/agent/AgentActionExecutorTest.java` — `FakeAgentActions`
  (roster #6 Garde↔guard lié, #14 libre ; capture `lastLinkNpcId`/`lastLinkCitizensId`) + 2 tests
- `src/test/java/com/lodygames/rpgquest/database/NpcBindingRepositoryTest.java` — 1 test
  (`insertIfAbsent` insère une fois, refuse le rebind du même Citizens)
- `src/test/java/com/lodygames/rpgquest/npc/NpcIdentityServiceTest.java` — 4 tests `bindCitizens*`

Control Panel :

- `control-panel/src/main/java/com/lodygames/rpgquest/panel/authz/Permission.java` — `NPC_BIND_WRITE`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalog.java` —
  2 entrées + `validate()`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java` — bouton refresh,
  résumé, `renderNpcCard` (+`citizensRoster`,+`canLink`), `citizensLinkForm(...)`, `citizensNameFor(...)`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/NpcsCatalogTest.java` —
  `woodcutter_bob` devient `NOT_LINKED` ; constante `CITIZENS_DETAILS` ; surcharge
  `runListWithSuccess(type, details)` ; 2 tests de flux de liaison

Documentation :

- `docs/control-panel/AGENT.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
  `docs/control-panel/ROADMAP.md`, `NPC_FORMAT.md`,
  `docs/deployment/SERVER_CHANGELOG.md` (entrée #81 + « Exécution réelle » en commit de suivi),
  `docs/claude-reports/README.md` (index)

## Base de données / migrations

**Aucune migration.** La table `npc_citizens_bindings` (migration V12) est réutilisée telle
quelle. `npc.citizens.link` y fait au plus un `INSERT OR IGNORE` (non destructif). Aucune
colonne, aucun index ajouté.

## Configuration / données

Aucun nouveau fichier de configuration. Aucun format YAML modifié. Aucune commande en jeu
ajoutée ou changée. `NPC_FORMAT.md` précise seulement que le binding peut désormais être posé
depuis le Control Panel en plus de `/rpgadmin npc tag`.

## Tests automatiques

- `./gradlew build` (AWS, `GRADLE_OPTS` plafonné) → **BUILD SUCCESSFUL**.
  - Plugin `:test` : **1142 / 0 échec / 0 ignoré**.
  - `:control-panel:test` : **109 / 0 / 0**.
- Couverture ajoutée :
  - `CitizensBindPlannerTest` : INSERT (libre/libre), NOOP (binding identique), REJECT
    `CITIZENS_TAKEN`, REJECT `NPC_ID_TAKEN`, INSERT (aucun binding existant).
  - `NpcIdentityServiceTest` : `bindCitizens` crée + met à jour le cache ; idempotent (`NOOP`) ;
    refuse le rebind d'un Citizens déjà lié (`CITIZENS_TAKEN`) ; refuse quand `npc_id` est déjà
    lié ailleurs (`NPC_ID_TAKEN`).
  - `NpcBindingRepositoryTest` : `insertIfAbsent` insère une seule fois, renvoie `false` au
    second appel pour le même `citizens_uuid`.
  - `NpcCitizensPayloadTest` : sérialisation du roster (`availableForBinding` cohérent) ;
    `npc.citizens.link` whitelistée + validée ; collision et no-op deviennent des `outcome`
    lisibles.
  - `AgentActionExecutorTest` : `npc.citizens.list` renvoie le roster ; `npc.citizens.link`
    valide les params et délègue (capture `npc_id` / `citizens_id`).
  - `NpcsCatalogTest` : formulaire de liaison montré **uniquement** si définition présente +
    binding absent + au moins un Citizens libre ; Citizens occupé non sélectionnable ; action
    validée et mise en file.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — nécessitent un client Minecraft, à faire par l'owner :

1. `/npcs` : sur la carte `woodcutter_bob` (définition OUI, binding NON), le bloc « Lier un PNJ
   Citizens existant » n'apparaît **que** si `npc.citizens.list` a été rafraîchi et qu'au moins
   un Citizens est libre. Le `select` grise les Citizens occupés.
2. Créer en jeu un PNJ Citizens neuf (hors de cette tâche), le rafraîchir dans `/npcs`, le lier à
   `woodcutter_bob` depuis le formulaire (avec `confirm`) → `SUCCESS LINKED` ; la carte passe
   `LINKED` et affiche `#N — <nom>` sans bouton de modification ; l'identification en jeu du PNJ
   fonctionne **sans redémarrage** (cache rafraîchi).
3. Re-soumettre exactement la même liaison → `SUCCESS NOOP` (aucune erreur).
4. Tenter de lier ce même Citizens à un autre `npc_id` → refus `CITIZENS_TAKEN`.
5. Responsive : le `<details>` + `select` + `confirmBox` restent lisibles en largeur mobile.

## Résultat attendu

- Un admin peut, **sans être en jeu**, associer une définition logique RPGQuest à un PNJ
  Citizens **déjà présent** dans le monde, via `/npcs`.
- Toute collision (Citizens déjà pris, `npc_id` déjà pris) est **refusée avec un message clair**,
  jamais silencieusement réaffectée.
- Une double soumission est un **no-op** explicite, pas une erreur.
- Aucun spawn, aucun rebind, aucune suppression n'est possible par cette action.

## Reset / retour à l'état initial

- Défaire une liaison créée : `DELETE FROM npc_citizens_bindings WHERE citizens_uuid = ?` sur le
  `data.db` du serveur (ou `/rpgadmin npc untag` en visant l'entité). Non exposé par cette phase.
- Revenir au code d'avant : `git revert 3843238` (code) — la doc et le `SERVER_CHANGELOG`
  suivent. Aucune donnée à nettoyer si aucune liaison n'a été créée.

## Déploiement VeryGames

### À transférer

- **Le seul JAR RPGQuest** : `build/libs/rpgquest-0.1.0-SNAPSHOT.jar`
  (SHA-256 `342bf808cf1b4d1bdba0981dc2c0044190ca577f38176730b699d1b08c39b165`, 1 331 243 o),
  via `scripts/deploy-verygames.sh -y` (backup automatique de l'ancien JAR).
- Côté AWS : `scripts/plugadmin/deploy.sh` (le Control Panel embarque le nouveau catalogue
  d'actions).

### Ne PAS transférer/altérer

- `plugins/RPGQuest/data.db` (sauvegardé par précaution, mais **non remplacé**).
- `plugins/RPGQuest/npcs/*.yml`, la config Citizens, les mondes, la progression joueur.
- Aucun fichier agent (`plugadmin-agent.properties`) à changer.

### Redémarrage requis

Oui — `scripts/verygames-restart.sh` (`stop` RCON → relance auto VeryGames) pour que l'agent
charge les nouveaux types d'actions.

### Migration automatique

Aucune. Pas de nouvelle migration de schéma.

## Rollback

- **VeryGames** : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`
  (restaure `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T131925Z-predeploy.jar`).
- **AWS** : `scripts/plugadmin/rollback.sh app` (release précédente `20260908-131859` conservée).
- Aucune migration à défaire. Une liaison éventuellement créée se retire par `DELETE` ciblé.

## Logs / diagnostic

Déploiement + validation live du 2026-09-08 (~13:19–13:25 UTC), branche
`feat/control-panel-admin-tools` @ `3d1f50c` :

- **AWS** : `deploy.sh` exit 0 ; release `/opt/plugadmin/releases/20260908-131859` ;
  `control-panel-0.1.0-SNAPSHOT.jar` SHA-256
  `a52ad4d03e7752483be13a33e811ca094d115d8d2cc3ac303988313e16258f11` (== build local) ;
  `/health` public **ONLINE** ; `dig.lodygames.com` / `lodylands.com` → 200 (inchangés).
- **VeryGames** : `deploy-verygames.sh -y` OK (JAR SHA-256 ci-dessus) ;
  `verygames-restart.sh` → OFFLINE puis **ONLINE**.
- RCON `plugins` → `Citizens, Multiverse-Core, RPGQuest, WorldEdit` **tous verts** ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`.
- Heartbeat agent `2026-09-08T13:24:04Z` : `server_state=ONLINE`, `plugin_version=0.1.0-SNAPSHOT`.
- `journalctl -u plugadmin` (fenêtre du déploiement) : **aucun `ERROR` / `WARN`**.
- `npc.citizens.list` → **SUCCESS**, `value=7` :
  - `citizensAvailable:true`, `total:7`, `available:0`, `linked:7`.
  - Citizens `#0..#6` : `Guide↔guide`, `Libraire↔libraire`, `help↔help`, `jeff↔jeff`,
    `junior↔junior`, `Jo↔jo`, `Garde↔guard`. Chaque entrée porte
    `{numericId, uuid, name, linkedNpcId, availableForBinding:false, spawned:false}`.
    **Aucune position ni monde** dans le payload.
  - Les 7 Citizens DEV sont **déjà liés** (via `/rpgadmin npc tag` antérieur). Seul `guard` a
    aussi une définition logique ; les 6 autres restent des `CITIZENS_ORPHAN` — **non
    détournés**.
- `npc.list` → **SUCCESS** (8 PNJ RPGQuest, 7 avec avertissement) — inchangé.
- `npc.citizens.link` — 4 cas exercés en direct, **aucune liaison créée** :

  | `npc_id` | `citizens_id` | Résultat | Code | Message |
  |---|---|---|---|---|
  | `woodcutter_bob` | 6 (pris par `guard`) | FAILED | `CITIZENS_TAKEN` | « Citizens #6 est déjà lié à npc_id=guard. Aucune réaffectation dans cette phase. » |
  | `guard` | 6 (liaison identique) | SUCCESS | `NOOP` | « Citizens #6 est déjà lié à « guard » — rien à faire. » |
  | `does_not_exist_xyz` | 6 | FAILED | `UNKNOWN_NPC` | « Aucune définition logique … — créer d'abord la définition. » |
  | `woodcutter_bob` | 9999 | FAILED | `UNKNOWN_CITIZENS` | « Aucun PNJ Citizens #9999 dans le registre. » |

- **Chemin nominal `INSERT` → `LINKED` non exerçable en direct** : aucun PNJ Citizens libre sur
  DEV, et consigne de ne pas en créer ni détourner un PNJ existant. Couvert par
  `CitizensBindPlannerTest`, `NpcIdentityServiceTest#bindCitizens*`,
  `NpcBindingRepositoryTest#insertIfAbsent*`, `NpcCitizensPayloadTest`, `AgentActionExecutorTest`,
  `NpcsCatalogTest`.

## Documentation mise à jour

- `docs/control-panel/AGENT.md` : lignes de tableau `npc.citizens.list` / `npc.citizens.link` +
  section « Liaison définition ↔ Citizens existant (`npc.citizens.link` — issue #81, phase 1) »
  (dont le comportement automatique des orphelins).
- `docs/RPGQUEST_BIBLE.md` §5 : mention des deux actions.
- `docs/current_state.md` : puce « Liaison définition ↔ PNJ Citizens existant (issue #81 phase 1) ».
- `docs/control-panel/ROADMAP.md` : puce Étape 3 « Lier une définition à un PNJ Citizens
  existant (#81, phase 1) » + « Reste » (spawn #81 phase 2, rebind, éditeur de dialogues,
  câblage `/rpgadmin npc tag` #66, enrichissement live).
- `NPC_FORMAT.md` : paragraphe « Définition logique vs binding Citizens » (2ᵉ voie d'écriture).
- `docs/deployment/SERVER_CHANGELOG.md` : entrée « 2026-09-08 — Lier une définition PNJ à un PNJ
  Citizens existant — issue #81 (phase 1) » + « ### Exécution réelle » renseigné.
- `docs/claude-reports/README.md` : ligne d'index de ce rapport.

## Limitations / travail restant

- **Chemin `LINKED` non validé en jeu** (pas de Citizens libre sur DEV). À faire par l'owner
  (§ « Tests manuels »).
- **Phase 2 #81 — spawn Citizens depuis le web** (`npc.citizens.create`) : non codé.
  Architecture cible documentée ici :
  - Params : `npc_id` (définition existante), `world`, `x`, `y`, `z`, `yaw?`, `pitch?`.
  - Nom du PNJ : `NpcDefinition.displayName` (pas d'entrée libre).
  - Implémentation : `onMain` → `CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name)`
    → `npc.spawn(location)` → réutiliser `NpcIdentityService.bindCitizens(npcId, ref)` pour la
    persistance.
  - Verrous : monde chargé requis, position valide, collision de spawn, `EntityType` par défaut
    à décider, permission encore distincte (`NPC_SPAWN_WRITE` ?). **Nécessite une validation en
    jeu** → phase 2.
- **Rebind / remplacement d'un binding existant** : hors périmètre, action future dédiée
  (message « une procédure de rebind sera ajoutée ultérieurement » déjà affiché sur les cartes
  `LINKED`).
- **Enrichissement live du roster** (position, monde, PNJ Citizens non tagués distinctement) :
  volontairement absent pour ne pas forcer de chargement de chunk.
- **#66** (câblage `/rpgadmin npc tag` ↔ Control Panel) : `NpcBindingRepository` a reçu
  `insertIfAbsent` (réutilisable par un futur relais de `npc tag`), mais aucune commande en jeu
  n'a été modifiée dans cette tâche.

## Prochaine étape suggérée

1. Owner : exécuter les tests manuels (créer un Citizens neuf sur DEV, le lier à
   `woodcutter_bob` via `/npcs`, vérifier `LINKED` + identification en jeu sans redémarrage).
2. Spécifier #81 **phase 2** (`npc.citizens.create`) à partir de la section « Limitations »
   ci-dessus.
3. Optionnel : donner une définition logique aux 6 `CITIZENS_ORPHAN` DEV
   (`guide/help/jeff/jo/junior/libraire`) via `npc.definition.create` — le binding existant sera
   reconnu automatiquement (aucune action de liaison à rejouer).
