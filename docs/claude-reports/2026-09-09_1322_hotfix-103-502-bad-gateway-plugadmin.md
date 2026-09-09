# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 13:22
* Sujet : #103 — 502 Bad Gateway sur PlugAdmin après connexion, suite au déploiement #101
* Statut : DONE — panel restauré, cause racine identifiée, durcissement déployé
* Branche Git : `feat/control-panel-admin-tools`
* Commit au démarrage : `721deb7` — commits de la tâche : `aa6270b` (fix + régression), `c92ae3f` (smoke prod-copy), `<docs>` (rapport + changelog)
* Début de la tâche : 2026-09-09 13:00:30 (première capture `systemctl status`)
* Fin de la tâche : 2026-09-09 13:23:30
* Durée totale : 00:23:00

## Demande

502 Bad Gateway après connexion owner (`/login` OK, redirection `/home` → 502 nginx).
BLOQUANT. Exigences : cause racine AVANT correction (captures systemd + journal + nginx),
corréler avec la session authentifiée, auditer en priorité #101 et #38, reproduire avec des
**données réelles**, rollback PlugAdmin si le fix n'est pas immédiat et sûr, durcir pour
qu'un snapshot incomplet ne puisse jamais faire tomber le serveur web, régression exacte,
validation **authentifiée** (plus de « anon → 303 » comme preuve), rapport détaillé.

## CAUSE RACINE

`AgentStore.readAction` (ligne 335) faisait `Instant.parse(rs.getString("created_at"))`
**sans tolérance**. `Instant.parse` exige un décalage horaire (`…Z` ou `±hh:mm`).

Pendant la « validation live » de **#101**, j'ai inséré **directement en base** une ligne
`agent_action` (sonde `npc.citizens.list`, `created_by='claude-101-validation'`) avec :

```
created_at = strftime('%Y-%m-%dT%H:%M:%S','now')  ->  '2026-09-09T12:51:33'   (SANS « Z »)
```

alors que `AgentStore.createAction` écrit toujours `isoSeconds(Instant.now())` →
`'2026-09-09T12:51:33Z'`.

`recentActions()` trie `ORDER BY created_at DESC` → cette ligne (la plus récente) est
**toujours** dans le scan → `readAction` → `Instant.parse('2026-09-09T12:51:33')` →
`DateTimeParseException: … could not be parsed at index 19`.

Cette exception remonte par :

```
NotificationCenter.recent (l.41)  ->  AgentStore.recentActions (l.285)  ->  readAction (l.335)
NotificationCenter.bellHtml (l.71)  ->  PanelApp.notifBell (l.1223)
PanelApp.finishPage (l.1214)  ->  renderPage  ->  handleHome / handleBusinessPage
```

`finishPage` (donc `notifBell`, donc `recentActions`) est appelé pour **toute page
authentifiée**. La cloche de la topbar cassait → HTTP 500 → **502 nginx** (`upstream …
closed connection` / réponse tronquée). `/login` (anonyme, sans cloche) restait fonctionnel.

**Ce n'est PAS une régression du code #101.** `freeCitizens`, `CITIZENS_ONLY`,
`NpcDiagnosticProvider`, `HomePages.DiagSummary`, `DiagnosticsService` **n'apparaissent pas**
dans la stacktrace. Le panel était sain juste après le déploiement #101
(`/health` OK à 12:50:37) et n'a cassé qu'à **12:51:33**, à l'insertion de la ligne mal
formée. La faute est double : (a) mon écriture hors-outil d'un horodatage non conforme ;
(b) une fragilité réelle de `AgentStore` — une seule ligne illisible faisait tomber toutes
les pages authentifiées.

## STACKTRACE / LOG

`journalctl -u plugadmin` (répété à chaque `POST /login` → `GET /home`) :

```
WARNING: event=handler_error rid=4ffebaa3 path=/home
java.time.format.DateTimeParseException: Text '2026-09-09T12:51:33' could not be parsed at index 19
        at java.base/java.time.Instant.parse(Instant.java:398)
        at com.lodygames.rpgquest.panel.agent.AgentStore.readAction(AgentStore.java:335)
        at com.lodygames.rpgquest.panel.agent.AgentStore.recentActions(AgentStore.java:285)
        at com.lodygames.rpgquest.panel.web.NotificationCenter.recent(NotificationCenter.java:41)
        at com.lodygames.rpgquest.panel.web.NotificationCenter.bellHtml(NotificationCenter.java:71)
        at com.lodygames.rpgquest.panel.web.PanelApp.notifBell(PanelApp.java:1223)
        at com.lodygames.rpgquest.panel.web.PanelApp.finishPage(PanelApp.java:1214)
        at com.lodygames.rpgquest.panel.web.PanelApp.renderPage(PanelApp.java:1209)
        at com.lodygames.rpgquest.panel.web.PanelApp.handleHome(PanelApp.java:308)
        ...
INFO: event=request rid=4ffebaa3 method=GET path=/home status=500 ms=28
```

Même trace sur `path=/npcs` (via `handleBusinessPage` l.820).

## État service lors du 502

- `systemctl status plugadmin` : **`active (running)`** depuis 12:50:35, `NRestarts=0`,
  `ExecMainStatus=0`, `MainPID=467464`.
- `ss -ltnp | grep 8090` : **écoute bien** sur `127.0.0.1:8090`.
- **Le process n'était pas mort, ne redémarrait pas, ne saturait pas la RAM**
  (`Memory: 108.9M`). Aucun OOM. L'agent DEV continuait ses heartbeats (200).
- Le 502 était **par requête** : chaque `GET /home` authentifié levait l'exception, le
  handler renvoyait 500 avec réponse tronquée → nginx traduit en 502.

## Fix

### 1. Réparation de données (immédiat — panel re-servi à ~13:09 UTC, avant tout redéploiement)

```sql
UPDATE agent_action
   SET created_at = '2026-09-09T12:51:33Z'
 WHERE id = '0cd8dd38-d8f7-4e6a-aba7-2972d189b813'
   AND created_at = '2026-09-09T12:51:33';
```

UPDATE **ciblé**, aucune suppression. Valeur d'origine consignée ici. Ligne inspectée avant
action : `type=npc.citizens.list`, `status=EXPIRED`, `created_by='claude-101-validation'` —
la sonde jetable que j'avais créée pour #101. Vérifié ensuite : **0** ligne au `created_at`
sans « Z » dans `control-panel.db`. Aucun rollback nécessaire (le pas de #101 lui-même est
sain), donc pas de `rollback.sh`.

### 2. Durcissement `AgentStore` (déployé — protection permanente)

- **`parseTimestamp(raw, context)`** : analyse tolérante — ISO-8601 canonique
  (`…Z`/offset/fraction), **plus** formes héritées sans décalage (`2026-09-09T12:51:33`,
  `2026-09-09 12:51:33`, date seule) interprétées en **UTC** ; `null`/blanc → `null` ;
  illisible → `null` + `WARNING event=timestamp_unparseable`.
- **`created_at` / `received_at`** (colonnes `NOT NULL`, utilisées pour tri/comparaison) :
  `instantOrEpoch` → `Instant.EPOCH` en dernier recours, **jamais** d'exception (un `null`
  déplacerait seulement le crash vers le comparateur).
- **`instantOrNull`** (`delivered_at`/`completed_at`) : délègue à `parseTimestamp` (tolère
  désormais aussi les formes sans décalage).
- **Frontière de sécurité par ligne** dans `recentActions` : `readAction(rs)` par ligne dans
  un `try/catch` — une ligne illisible est **ignorée** avec un `WARNING
  event=agent_action_row_skipped id=…`, **jamais propagée**. Aucune ligne ne peut plus
  casser l'historique ni les pages qui l'affichent, même pour un mode de défaillance non
  prévu.

Règle appliquée : **donnée agent invalide/incomplète ⇒ diagnostic/WARNING, jamais crash du
serveur web.**

## Pourquoi les tests précédents ne l'ont pas détecté

- Les tests #101 utilisaient des **fixtures JSON** injectées via le vrai chemin agent
  (`/agent/v1/actions/<id>/result`), donc des `created_at` **toujours** écrits par
  `AgentStore` au format canonique. Aucun test ne fabriquait une ligne `agent_action` avec
  un `created_at` mal formé — ce cas n'existait pas avant mon insertion manuelle.
- Aucun test n'exerçait `NotificationCenter.recent` / `AgentStore.recentActions` avec une
  ligne au timestamp hérité. La cloche est rendue partout mais testée surtout pour son
  markup (`NotificationsBugfixTest`), sur des données saines.
- La cause n'est pas dans le diff #101 : relire le diff #101 ne pouvait pas la révéler.
  Elle est dans une **fragilité préexistante** de `AgentStore`, déclenchée par une donnée
  que **j'ai** introduite lors de la validation.

## Pourquoi le smoke test anonyme était insuffisant

`/login` ne rend **pas** la cloche de notifications (`finishPage`/`notifBell` n'est appelé
que pour les pages authentifiées). Un check « `/home` anonyme → **303** vers `/login` »
prouve seulement que la redirection d'auth fonctionne — il ne rend **jamais** le corps de
`/home`, donc n'exécute jamais `notifBell` → `recentActions` → `readAction`. Le chemin qui
plantait n'était atteint qu'**après** authentification. Désormais : validation
**authentifiée** obligatoire (voir ci-dessous).

## Test de régression ajouté

`control-panel/.../web/PanelHardeningMalformedAgentDataTest` :

1. `authenticatedPagesStay200WhenAnAgentActionRowHasAMalformedTimestamp` — insère la ligne
   **exacte** de #103 (`npc.citizens.list`, `EXPIRED`, Stan libre, `created_at`
   `'2026-09-09T12:51:33'` sans « Z »), connexion owner réelle, puis
   `GET /home /dashboard /npcs /diagnostics /docs /actions` = **200** ; cloche rendue ;
   `/agents/actions.json` = 200.
2. `variousLegacyTimestampShapesAreToleratedOnAuthenticatedPages` — espace au lieu de « T »,
   date seule, blanc total → pages authentifiées 200.
3. `legacyCitizensPayloadWithoutNewFieldsRendersFine` — `npc.citizens.list` SUCCESS au
   `result_json` **hérité** (Stan sans `uuid`/`availableForBinding`/`spawned`) → `/npcs` 200,
   `Citizens #7` visible, `/diagnostics` 200.
4. `authenticatedSmokeAgainstProductionDbCopyWhenProvided` (`-DpanelProdDbCopy=<copie.db>`,
   ignoré sinon) — démarre `PanelApp` sur une **copie de la vraie `control-panel.db`**
   (79 lignes réelles), connexion owner, `/home /dashboard /npcs /diagnostics /docs /actions
   /agents /players /quests /stories /dialogues` = **200**, Stan (#101) visible, ligne mal
   formée réinjectée → `/home` reste 200.

**Vérification négative** : `git stash` de `AgentStore.java` → le test (1) **échoue**
(`EOFException` = réponse tronquée = 502) ; `stash pop` → repasse au vert. Le test reproduit
donc bien la cause exacte.

`control-panel/build.gradle.kts` : passe la propriété `panelProdDbCopy` au worker de test
(Gradle ne la propage pas par défaut) — inerte en run normal.

## Release déployée

- `scripts/plugadmin/deploy.sh --no-build` — release `/opt/plugadmin/releases/20260909-131643`
  (PID 480917), le 2026-09-09 ~13:16 UTC.
- Jar déployé **byte-identique** au build : SHA-256
  `9a21828f51e71e8784223bd2f8e549dbd0d09bf22c48e5fbc13ad0f8d608983c` ; `parseTimestamp` +
  `instantOrEpoch` présents dans le bytecode.
- **Aucun impact VeryGames / Minecraft / Citizens / `data.db` / mondes.**

## Validation authentifiée

- `PanelHardeningMalformedAgentDataTest` (4/4, dont le smoke `-DpanelProdDbCopy` contre une
  **copie de la base de prod réelle**) : connexion owner + toutes les pages authentifiées =
  **200**, sur les données de production réelles (79 lignes `agent_action`, payloads
  `npc.citizens.list`/`npc.list` réels incluant Stan). Ligne mal formée réinjectée dans la
  copie → `/home` reste 200.
- Prod : `/health` ONLINE (local + `https://plugadmin.lodylands.com`) ; `plugadmin.service`
  `active` `NRestarts=0` `ExecMainStatus=0` ; **0 `ERROR`** au journal depuis le
  redéploiement ; `control-panel.db` = 0 ligne au `created_at` sans « Z ».
- Navigateur authentifié sur le service de prod : dernier point à confirmer par l'owner
  (mot de passe non détenu). Couverture équivalente assurée par le smoke authentifié sur
  copie de prod.
- `:control-panel:test` **241** (0 échec ; 1 ignoré = smoke prod-copy, lancé à la demande) ;
  `:test` plugin inchangé (0 échec) ; `./gradlew build` vert.

## État Stan après fix

`#101 toujours vivant.` Le smoke authentifié sur copie de prod (payload `npc.citizens.list`
réel) confirme : `/npcs` = 200 et **`Citizens #7` (Stan) reste visible** — sans fiche
RPGQuest / non lié. Le durcissement #103 ne touche pas le code #101.

## Rollback disponible

`scripts/plugadmin/rollback.sh app` → restaure `/opt/plugadmin/releases/20260909-125035`
(= #101 sans le durcissement). ⚠️ Sans le durcissement, toute future ligne `created_at` sans
« Z » ferait revenir le 502 — la vraie protection est le correctif de code, pas la
réparation ponctuelle. Aucune migration à défaire. **PlugAdmin uniquement.**

## Ce qui a été touché

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentStore.java` — durcissement.
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/PanelHardeningMalformedAgentDataTest.java` — nouveau.
* `control-panel/build.gradle.kts` — passe `-DpanelProdDbCopy` au worker de test.
* `docs/deployment/SERVER_CHANGELOG.md` — entrée hotfix #103.
* `docs/current_state.md`, `docs/control-panel/ROADMAP.md` — note #103.
* Base de production : `agent_action` id `0cd8dd38…`, `created_at` réparé (UPDATE ciblé).

## Base de données / migrations

Aucune migration. Une réparation ponctuelle d'une ligne `agent_action` en production
(`created_at` : ajout du « Z » manquant). `control-panel.db` non touché par le déploiement.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (navigateur, mot de passe owner) : se connecter sur
`https://plugadmin.lodylands.com`, confirmer `/home`, `/dashboard`, `/npcs` (Stan visible),
`/diagnostics`, `/docs` s'affichent (200, plus de 502).

## Reset / retour à l'état initial

`scripts/plugadmin/rollback.sh app` pour le code. La ligne `0cd8dd38…` peut être supprimée
sans effet (sonde `EXPIRED` jetable) ; sa valeur `created_at` d'origine
(`'2026-09-09T12:51:33'`) est consignée ci-dessus.

## Déploiement VeryGames

### À transférer
**Rien.** Control Panel AWS uniquement.

### Ne PAS transférer/altérer
JAR RPGQuest du serveur, `data.db`, `plugins/Citizens/*`, mondes, config VeryGames.

### Redémarrage requis
`systemctl restart plugadmin` uniquement (fait par `deploy.sh`). VeryGames : non.

### Migration automatique
Aucune.

## Logs / diagnostic

Journal `plugadmin` : la `DateTimeParseException` (répétée à chaque `/home` authentifié
entre 12:51 et 13:09) a disparu après réparation de la ligne ; **0 `handler_error`** depuis
le redéploiement `20260909-131643`.

## Documentation mise à jour

`docs/deployment/SERVER_CHANGELOG.md`, `docs/current_state.md`,
`docs/control-panel/ROADMAP.md`.

## Limitations / travail restant

* Confirmation navigateur authentifiée sur le service de prod par l'owner (mot de passe non
  détenu par Claude).
* Les autres lectures `Instant.parse` du panel sur des chaînes stockées
  (`SqliteAuditLog.ts`, `BridgeHealth.generatedAt`) restent en `Instant.parse` strict —
  elles ne sont pas dans un chemin de rendu de page authentifiée et sont toujours écrites
  par le code ; à harmoniser éventuellement plus tard (hors périmètre de ce hotfix).
* Leçon : ne plus écrire directement en base pour « valider » — passer par le vrai chemin
  applicatif, ou n'utiliser que des horodatages ISO canoniques.

## Prochaine étape suggérée

Confirmation navigateur de l'owner, puis fermeture de #103 par l'owner. #101 reste ouverte.
EOF
echo "report written"