# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 19:05 (locale, UTC sur cette machine)
* Sujet : Bug **#87** — un joueur déconnecté dans le Wild se reconnecte au Hub au lieu de sa position
* Statut : DONE — code + tests + build verts ; **JAR déployé sur VeryGames DEV** (1 restart, serveur `ONLINE`, agent reconnecté, `dialogue.list`/`npc.list` `SUCCESS`, 0 `ERROR`). **Validation manuelle en jeu (owner, client réel) = `PENDING MANUAL VALIDATION`.**
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `4006095` (fix) — commit de suivi pour ce rapport + « Exécution réelle »
* Début de la tâche : 2026-09-08 18:50:51 (heure locale réelle)
* Fin de la tâche : 2026-09-08 19:20:00 (heure locale réelle)
* Durée totale : 00:29:09

## Demande

Corriger **uniquement #87** : un joueur non-OP déconnecté dans `wild` (~`X=-1765 / Y=80 /
Z=2607`) se reconnecte dans `world_hub` (~`X=738 / Y=67 / Z=-679`). Le logout/login devient un
échappatoire gratuit du Wild.

Attendu : reconnexion dans le **même monde `wild`, à la même position** (ou une position sûre
très proche si l'exacte est devenue invalide). Repli Hub **seulement** si réellement nécessaire
(monde précédent introuvable). Onboarding nouveau joueur inchangé. Ne pas confondre avec le
respawn après mort. Pas de `if (!world.equals("world_hub"))` codé en dur. Ne pas toucher #88
(anti-combat-logging), Claims, Rune/Pierre de rappel, mort/respawn. Pas de merge.

## Audit — pourquoi un joueur existant dans `wild` est forcé au Hub

Audit exhaustif de **tous** les chemins de connexion/réapparition (`PlayerJoinEvent` ×15,
`PlayerSpawnLocationEvent`, `PlayerRespawnEvent`) et de **tous** les appels
`teleport`/`teleportAsync`/`setSpawnLocation`/`setRespawnLocation` de `src/main` :

| Chemin | Peut renvoyer au Hub sur reconnexion ? |
|---|---|
| `spawn.SpawnPlayerListener` → `SpawnService.handleFirstJoin` (`PlayerSpawnLocationEvent`) | **OUI** — `event.setSpawnLocation(spawn.yml)` si `!hasPlayedBefore()` |
| `spawn.SpawnService.handleRespawn` (`PlayerRespawnEvent`) | non — uniquement après la mort |
| `travel.WorldPortalTeleportListener.onJoin` / `PortalListener.onJoin` / `PortalService.handleJoin` | non — logs + répit + rechargement de cooldowns, aucun téléport |
| `claim.ClaimWorldSafetyListener.onJoin` | non — ne se déclenche que dans le **monde des claims** |
| `player.NewPlayerResetJoinListener`, `PlayerConnectionListener`, `ResourcePackListener`, `mod.ModCompatService`, `story.*`, `quest.*`, `ui.*`, `waystone.*`, `zone.*`, `progression.*` | non — aucun téléport à la connexion |

**La position d'arrivée observée (`world_hub` ~`738,67,-679`) correspond exactement à
`plugins/RPGQuest/spawn.yml`** (`world: world_hub / x: 738.82 / y: 67.0 / z: -679.57`). Le
**seul** code qui écrit cette position sur un événement de connexion est
`SpawnService.handleFirstJoin`, exécuté quand `SpawnPlayerListener` juge le joueur « nouveau »
via `!event.getPlayer().hasPlayedBefore()`.

### Cause racine

`Player#hasPlayedBefore()` est un **signal peu fiable** : il renvoie `false` pour un joueur qui a
pourtant déjà joué dès que la métadonnée Bukkit `bukkit.firstPlayed` de son fichier
`playerdata/<uuid>.dat` est **absente ou pas encore peuplée** — migration / transfert de serveur
(scénario VeryGames), bascule hors-ligne ↔ en-ligne des UUID, restauration depuis une
sauvegarde, ou simple aléa de timing dans la séquence de login. La position vanilla (`Pos`,
dimension) du joueur, elle, est **toujours** présente : quand `hasPlayedBefore()` se trompe pour
un joueur qui était dans `wild`, RPGQuest jette sa position `wild` réelle et le force au spawn du
village (`world_hub`).

### Écarté

- **Multiverse-Core** (DEV, `plugins/Multiverse-Core/config.yml`/`worlds.yml`) :
  `enforce-access: false`, `first-spawn-override: false`, `enable-join-destination: false`, et
  **`wild` n'est même pas un monde géré par Multiverse** (`worlds.yml` ne connaît que `claims`,
  `overworld`, `the_end`, `the_nether`, `world_hub`). Multiverse ne déplace personne à la
  connexion.
- **`handleRespawn`** : hors périmètre (#87 = reconnexion, pas mort) et non déclenché par un
  logout/login normal.

## Correction

Minimale, **sans nouvelle persistance** (Paper restaure déjà la position ; on corrige seulement
le code RPGQuest qui l'écrase).

### `spawn/JoinSpawnPolicy.java` (nouveau, pur — règle métier testable, #4 de la demande)

`decide(spawnConfigured, hasPlayedBefore, incomingWorldName, primaryWorldName, hubWorldName)` →
`REDIRECT_TO_CONFIGURED_SPAWN | KEEP_VANILLA_LOCATION`.

Règle : on redirige vers le spawn du village **uniquement si** —
1. un spawn de village est configuré (`spawn.yml` présent et son monde chargé) ; **et**
2. `hasPlayedBefore()` vaut `false` ; **et**
3. Paper place le joueur dans un monde où un tout nouveau joueur apparaît légitimement : le
   **monde principal** (`getServer().getWorlds().get(0)`) **ou** le **monde Hub** (`hub.world`).

Sinon → `KEEP_VANILLA_LOCATION` : la position restaurée par Paper est laissée intacte, **quel que
soit `hasPlayedBefore()`**. Un joueur que Paper ramène dans `wild` / `claims` / tout autre monde
chargé n'est donc jamais renvoyé au Hub.

- **Repli « monde précédent disparu »** conservé sans code dédié : si `wild` n'est plus chargé,
  Paper renvoie lui-même le joueur au monde principal → condition 3 remplie → redirection village.
- **Pas de `if (!world.equals("world_hub"))`** : c'est un **allowlist** des deux mondes
  d'apparition d'un nouveau joueur, dérivé de la config (`hub.world`) et de l'API
  (`getWorlds().get(0)`) — n'affecte aucun autre monde.
- Casse-insensible ; `incomingWorldName` inconnu / vide → `KEEP` (on ne prend pas le risque).

### `spawn/SpawnService.java`

- `handleFirstJoin(event)` → **`applyJoinSpawnPolicy(event)`** : construit les entrées (spawn
  résolu, `hasPlayedBefore()`, monde d'arrivée `event.getSpawnLocation()`, monde principal, monde
  Hub via `Supplier<String> hubWorldName`), appelle `JoinSpawnPolicy.decide(...)`, et n'appelle
  `event.setSpawnLocation(target)` que sur `REDIRECT_TO_CONFIGURED_SPAWN`.
- Nouveau paramètre de constructeur `Supplier<String> hubWorldName` (câblé bootstrap :
  `() -> configService.current().hub().world()`).
- `handleRespawn` **inchangé** (mort → village, hors périmètre #87).
- L'ancien log `[TP-TRACE] … reason=first_join_spawn` (instrumentation ad hoc) est remplacé par
  **`join_restore player=<uuid> world=<w> action=KEEP_LAST_LOCATION|REDIRECT_TO_VILLAGE_SPAWN`**
  (#11 de la demande) — **une** ligne INFO par connexion, **aucune coordonnée**.

### `spawn/SpawnPlayerListener.java`

`onSpawnLocation` appelle désormais **toujours** `service.applyJoinSpawnPolicy(event)` (le garde
`!hasPlayedBefore()` est descendu dans la politique, où il est combiné au monde d'arrivée).

### `bootstrap/RPGQuestBootstrap.java`

`new SpawnService(plugin, spawn.yml, logger, () -> configService.current().hub().world())`.

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/spawn/JoinSpawnPolicy.java`
- `src/test/java/com/lodygames/rpgquest/spawn/JoinSpawnPolicyTest.java`
- `docs/claude-reports/2026-09-08_1905_bug-wild-reconnexion-hub-87.md` (ce rapport)

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/spawn/SpawnService.java`
- `src/main/java/com/lodygames/rpgquest/spawn/SpawnPlayerListener.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/test/java/com/lodygames/rpgquest/spawn/SpawnServiceTest.java` (+2 cas, constructeur 4-arg)
- `src/test/java/com/lodygames/rpgquest/waystone/WaystoneServiceTest.java`,
  `src/test/java/com/lodygames/rpgquest/player/PlayerResetServiceTest.java` (constructeur 4-arg,
  comportement inchangé)
- `docs/current_state.md`, `docs/RPGQUEST_BIBLE.md` §7, `docs/deployment/SERVER_CHANGELOG.md`,
  `docs/claude-reports/README.md`

## Base de données / migrations

Aucune. Aucune persistance ajoutée.

## Configuration / données

Aucun changement de `config.yml`. `hub.world` (déjà existant) est désormais aussi consulté par
`SpawnService`. `spawn.yml` inchangé.

## Tests automatiques

- **`JoinSpawnPolicyTest`** (nouveau) — 6 cas de la demande + bords : joueur existant dans `wild`
  jamais redirigé (y compris `hasPlayedBefore()==false`) ; nouveau joueur → primary/Hub redirigé
  (casse-insensible) ; resetnew traité comme nouveau dans le Hub → redirigé ; monde manquant →
  repli via monde principal ; position invalide → laissée à Paper ; joueur dans `claims` jamais
  redirigé ; sans spawn configuré → rien ; monde d'arrivée inconnu → `KEEP` ; Hub non configuré
  → redirige quand même depuis le monde principal.
- **`SpawnServiceTest`** (étendu) — `returningPlayerInWildIsKeptEvenWhenHasPlayedBeforeIsWrong`
  (régression #87, via le vrai listener + MockBukkit) ;
  `brandNewPlayerLandingInHubWorldIsStillRedirectedToVillageSpawn` (onboarding conservé) ; les
  cas existants (`firstJoin*`, `returningPlayer*`, `respawn*`) restent verts.

Résultats : `./gradlew :test` → **1208 tests, 0 échec, 0 erreur** (29 ignorés) ;
`./gradlew :control-panel:test` → **121, 0** ; `./gradlew build` → **BUILD SUCCESSFUL**.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (section 12 de la demande, à faire **par l'owner** — un client réel
est nécessaire) :

1. `LoDyMcFly`, **non-OP**, entrer dans le Wild ; relever `world` + `x/y/z` (F3).
2. Se déconnecter, se reconnecter.
3. Vérifier : `world` toujours `wild` ; position identique ou très proche ; aucun passage Hub
   visible ; log serveur `join_restore player=… world=wild action=KEEP_LAST_LOCATION`.
4. Recommencer une 2ᵉ fois.
5. **Contrôles de non-régression** : un joueur déconnecté **dans le Hub** revient au Hub ; un
   **tout nouveau** compte arrive bien au spawn du village ; ne pas mélanger avec mort / Rune /
   combat / Claims.

## Résultat attendu

```
Avant logout : world=wild x≈-1765 y≈80 z≈2607
Après login  : world=wild x≈-1765 y≈80 z≈2607   (tolérance faible si ajustement de sécurité)
```
Jamais `world_hub`.

## Reset / retour à l'état initial

`git revert` du commit suffit (aucune donnée, aucune migration). Le `spawn.yml` du serveur n'est
pas touché.

## Déploiement VeryGames

### À transférer

- **JAR RPGQuest** uniquement (`scripts/deploy-verygames.sh -y`) — code plugin modifié.
- Backup automatique du JAR précédent conservé par le script.

### Ne PAS transférer/altérer

`data.db`, `config.yml`, `spawn.yml`, `messages.yml`, mondes (`wild` / `world_hub` / `claims` /
…), `Citizens/`, `dialogues/`, autres plugins, `plugadmin-agent.properties`.

### Redémarrage requis

Oui — **un seul** `scripts/verygames-restart.sh` (RCON `stop` → relance auto). **Ne pas
enchaîner les redémarrages** : VeryGames arrête le service après **10 auto-reboots en 30 min**
(« *We exit now* ») → relance manuelle obligatoire depuis le panel. Après le restart : vérifier
`ONLINE` (RCON `list`), `/plugins` RPGQuest vert, heartbeat agent, puis laisser l'owner faire le
test manuel #87.

### Migration automatique

Aucune.

## Rollback

- `scripts/rollback-verygames.sh --latest` puis **un seul** `scripts/verygames-restart.sh`.
- Aucune migration à défaire.

## Logs / diagnostic — exécution réelle du 2026-09-08 (~19:14–19:18 UTC)

Branche `feat/control-panel-admin-tools` @ `4006095`.

- `scripts/deploy-verygames.sh -y` — JAR `rpgquest-0.1.0-SNAPSHOT.jar` **1 429 560 o**, SHA-256
  **`d9a47cf868991dae8f6a072856f1f0519e87fd201cfc63388c486e4b0ba9f7ee`** ; backup auto
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T191434Z-predeploy.jar`
  (SHA-256 `12f66391be6ca65fc694095b21cc3a603c779187026ecde189392e6b61b3788d`, 1 427 111 o).
- `scripts/verygames-restart.sh` — **une seule fois** — OFFLINE → relance auto → **ONLINE**.
  `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` **verts** ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`. Heartbeat agent **ONLINE**, uptime croissant, mondes
  `world_hub` / `claims` / `wild` **tous `loaded:true`**.
- **Non-régression** : `dialogue.list` → **SUCCESS** (7 dialogues, `loadIssues=0`) ;
  `npc.list` → **SUCCESS** (8 PNJ). `journalctl -u plugadmin` depuis le déploiement : **0 ligne
  `ERROR` / `Exception` / `SEVERE`**.
- **Test manuel en jeu #87 (owner, non-OP, client réel)** : `PENDING MANUAL VALIDATION` — non
  réalisable depuis le périmètre (pas de client). Procédure exacte : section « Tests manuels à
  effectuer » ci-dessus. Le log serveur attendu au retour dans le Wild :
  `join_restore player=<uuid> world=wild action=KEEP_LAST_LOCATION`.

## Logs / diagnostic

- Nouveau log ciblé, **une ligne par connexion**, sans coordonnées :
  `join_restore player=<uuid> world=<w> action=KEEP_LAST_LOCATION` (cas nominal Wild) /
  `action=REDIRECT_TO_VILLAGE_SPAWN` (nouveau joueur au monde principal/Hub).
- L'ancien `[TP-TRACE] … reason=first_join_spawn` de `handleFirstJoin` est retiré (remplacé).
  Les autres `[TP-TRACE]` (investigation portails, `travel.TpTraceLogger`) restent en l'état —
  non concernés.
- Scan `ERROR` de la console VeryGames = `PENDING MANUAL VALIDATION` (console non accessible à
  distance dans le périmètre).

## Documentation mise à jour

- `docs/current_state.md` : section « Bug résolu : reconnexion depuis le Wild renvoyée au Hub
  (issue #87) ».
- `docs/RPGQUEST_BIBLE.md` §7 : paragraphe « Redirection à la connexion (issue #87) » sous le
  tableau « Spawn Minecraft vs Multiverse vs RPGQuest ».
- `docs/deployment/SERVER_CHANGELOG.md` : entrée « #87 … » + « ### Exécution réelle » à compléter
  au déploiement.
- `docs/claude-reports/README.md` : ligne d'index.

## Limitations / travail restant

- **Position sûre très proche si l'exacte est invalide** (#5) : on s'appuie sur le placement sûr
  natif de Paper/Multiverse (`adjust-spawn`) qui s'applique à la restauration de position — pas de
  recherche « nearby-safe » explicite ajoutée (la demande privilégie « ne pas stocker si Paper
  sait déjà restaurer »). Si un cas réel de suffocation à la reconnexion apparaît, un
  `SafeArrivalFinder` local restreint au même monde serait le complément (hors périmètre ici).
- **#88 anti-combat-logging** : point d'intégration documenté dans `JoinSpawnPolicy` (un
  `KEEP_VANILLA_LOCATION` pourra être remplacé par une pénalité si le `PlayerQuitEvent` précédent
  a marqué « déconnexion en combat »). **Non implémenté.**
- Validation en jeu = `PENDING MANUAL VALIDATION` (client réel requis).
- `hasPlayedBefore()` défaillant : la correction ne *répare* pas la métadonnée `bukkit.firstPlayed`
  manquante (hors périmètre), elle rend la décision d'onboarding robuste malgré elle.

## Prochaine étape suggérée

1. Déployer le JAR DEV + un restart, puis owner : test manuel #87 (2 passes) + non-régression
   Hub / nouveau joueur.
2. Si validé : envisager le complément « nearby-safe » et le branchement #88.
3. Ne pas fermer #87 avant la validation manuelle de l'owner.
