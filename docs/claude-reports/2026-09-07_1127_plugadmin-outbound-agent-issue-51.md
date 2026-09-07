# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-07
* Heure : 11:27 (heure locale réelle de la machine)
* Sujet : Issue #51 — agent **sortant** VeryGames → PlugAdmin : état live (heartbeat) et pipeline
  d'actions whitelistées, sans exposer le bridge #37 en entrée côté VeryGames. Inversion du flux.
* Statut : **DONE** (socle + déploiement + validation live). `./gradlew clean build` vert (plugin
  1005 tests, control-panel 44). **#51 déployée sur VeryGames DEV** (JAR
  `feat/51-plugadmin-outbound-agent` + `plugins/RPGQuest/plugadmin-agent.properties`), **redémarrage
  RCON** exécuté (VeryGames expose bien RCON — validé), **heartbeat réel reçu**, dashboard
  RPGQuest DEV **ONLINE via l'agent**, aller-retour `player.variable.get` **SUCCESS**, type inconnu
  **REJECTED**, idempotence (re-livraison) **OK**, panne PlugAdmin **n'affecte pas Minecraft** +
  reconnexion automatique **OK**. Email de fin de tests envoyé. #51 **non fermée**, rien fusionné.
* Branche Git : `feat/51-plugadmin-outbound-agent` (créée depuis `feat/44-plugadmin-aws-deploy`).
* Commit(s) : voir « Commit(s) / branche ».
* Début de la tâche : 2026-09-07 09:43:49
* Fin de la tâche : 2026-09-07 12:40:00
* Durée totale : 02:56:11

## Demande

Résoudre la contrainte structurante : RPGQuest tourne chez VeryGames (NAT, aucun port entrant),
PlugAdmin tourne sur AWS. AWS ne peut pas joindre le bridge local `/admin/v1/*` de #37. #51 doit
**inverser le flux** : `RPGQuest / VeryGames → HTTPS SORTANT → PlugAdmin / AWS`.

Objectif de la session : obtenir un premier flux réel (heartbeat → dashboard « RPGQuest DEV
ONLINE » avec les vraies infos du serveur) et un aller-retour d'**une** action non destructive
(`player.variable.get`). Ne pas exposer les actions destructives de #36. 18 phases détaillées dans
la demande (preuve de connectivité d'abord, config VeryGames par fichier local non-env, endpoints
`/agent/v1/*`, heartbeat via `HealthSource`, ONLINE/STALE/OFFLINE, persistance panel, agent
robuste, action de preuve, file d'actions, idempotence, dashboard, sécurité, déploiement, test
live, UI diagnostic, tests automatiques, documentation, git).

## Analyse

### Phase 1 — preuve de connectivité

Décision initiale (validée avec l'utilisateur) : construire tout le socle, déployer une fois, et
traiter le **premier heartbeat reçu** comme preuve de connectivité (le code émet aussi
`event=plugadmin_probe status=ok|failed` au démarrage). **Résultat** : après déploiement +
redémarrage, PlugAdmin a reçu des heartbeats réels du serveur VeryGames dès la 1re fenêtre
(`event=agent_heartbeat agent=rpgquest-dev version=0.1.0-SNAPSHOT`), toutes les ~20 s. **La sortie
HTTPS VeryGames → PlugAdmin fonctionne** — l'alternative « snapshot » n'a pas eu à être envisagée.

> **Correction d'une affirmation obsolète** de la 1re version de ce rapport : « VeryGames n'expose
> ni API ni RCON ». **RCON est en fait disponible** (DEV : `51.68.57.28:7469`) et a été validé
> (connexion + auth + `list` + `stop` + relance automatique VeryGames ~15 s). Le redémarrage
> n'est donc **pas** une action manuelle owner : `scripts/verygames-restart.sh` l'automatise.
> RCON reste un canal de commandes texte — il ne remplace pas le besoin d'un flux sortant pour
> l'état admin structuré, donc l'architecture #51 est inchangée.

### Décisions d'architecture (ADR-011, remplace ADR-004)

| Décision | Choix | Raison |
|---|---|---|
| Sens du flux | agent **sortant** dans le plugin (`com.lodygames.rpgquest.web.agent`) | seul flux possible derrière NAT VeryGames |
| Mode dégradé « admin-snapshot » (ADR-004) | **abandonné** | l'agent sortant fournit du live dans le bon sens |
| Health | **réutilise `HealthSource`** (#37) via `HeartbeatPayload` | aucune logique de health dupliquée (exigence explicite) |
| Config VeryGames | **fichier local hors Git** `plugins/RPGQuest/plugadmin-agent.properties` (+ surcharge env facultative) | on ne sait pas si VeryGames fournit proprement des variables d'env au process Paper ; env non imposé comme unique mécanisme |
| Auth | jeton porteur **par agent/cible**, temps constant, hors Git/logs/réponses | multi-cible dès le départ (`agents=…`) |
| Actions | types **whitelistés** (`AgentActionType`) → services métier ; jamais `/rpgadmin` texte | MVP = 1 seule action non destructive `player.variable.get` |
| Idempotence | 2 gardes : action livrée jusqu'au résultat terminal (panel) + `ProcessedActionCache` TTL 30 min (agent) | réponse de résultat perdue ⇒ pas de double exécution |
| Persistance | `control-panel.db` (`agent_heartbeat`, `agent_action`), migrations idempotentes | domaine PlugAdmin ; jamais `data.db` ; pas de MySQL #43 |
| Bridge local #37 | **conservé** | dev local / serveur co-localisé / diagnostic — documenté « BRIDGE LOCAL » vs « AGENT DISTANT » |
| Robustesse | threads asynchrones Bukkit, timeouts courts, backoff exponentiel plafonné, logs limités, arrêt propre, aucun `trustAll` | l'agent ne doit jamais perturber le gameplay |

## Travail effectué

### 1. Plugin — package `com.lodygames.rpgquest.web.agent` (nouveau)

- **`AgentConfig`** (record) + **`AgentConfigLoader`** : lit `plugadmin-agent.properties` (fichier
  local, `AgentConfigLoader.FILE_NAME`), surcharge par `RPGQUEST_PLUGADMIN_*` (précédence env >
  fichier > défaut). **Fail-closed** : agent inerte sans `enabled=true` + `base-url` + `agent-id`
  + `token`, ou si `base-url` n'est pas HTTPS (hors 127.0.0.1). `toString()` **redéfini** pour ne
  jamais rendre le jeton.
- **`AgentActionType`** : liste blanche (`PLAYER_VARIABLE_GET` = `player.variable.get`). Tout
  autre type → non exécuté.
- **`AgentAction`** / **`AgentActionOutcome`** : DTO reçu / résultat structuré
  (`SUCCESS`/`FAILED`/`REJECTED`, `value`, `message`, `details`, `finished_at`).
- **`AgentActionExecutor`** : valide le type et les paramètres, résout le joueur
  (`PlayerDirectory`), lit la variable (`PlayerVariables` = `PlayerVariableRepository#get`).
  Aucune exception ne remonte : tout échec devient un `FAILED` lisible. Clé de variable bornée
  (`[A-Za-z0-9_.:\-]{1,128}`).
- **`PlayerDirectory`** / **`BukkitPlayerDirectory`** : résolution nom/UUID → `{uuid, name}`,
  asynchrone (jamais le thread principal ; UUID direct via `getOfflinePlayer(UUID)`, nom hors
  ligne via un `runTaskAsynchronously`).
- **`ProcessedActionCache`** : garde d'idempotence (map bornée `action_id → résultat`, TTL 30 min,
  éviction du plus ancien).
- **`HeartbeatPayload`** : assemble le corps du heartbeat **à partir de `HealthSource`** + identité
  agent + `generated_at`.
- **`PlugAdminTransport`** (interface) + **`PlugAdminClient`** : HTTPS sortant `java.net.http`
  (validation de certificat normale, **aucun `trustAll`**), timeouts courts, corps de réponse
  **borné** (`max-response-kib`), toute panne → `PlugAdminUnavailableException` au message
  affichable. Routes : `POST /agent/v1/heartbeat`, `GET /agent/v1/actions`,
  `POST /agent/v1/actions/{id}/result`. En-têtes `Authorization: Bearer`, `X-Agent-Id`,
  `X-Agent-Env`.
- **`AgentLoop`** (sans Bukkit, testable) : `heartbeatTick()` + `pollTick()`. Backoff exponentiel
  (5 s → … → `max-backoff-seconds`), logs limités (≤ 1/min/flux), idempotence (rejeu sans
  ré-exécution), première réussite → log `event=plugadmin_probe status=ok` (**preuve phase 1**).
- **`PlugAdminAgent`** (`PluginService`) : câblage Bukkit — planifie `heartbeatTick`/`pollTick` en
  `runTaskTimerAsynchronously`, annule proprement à l'arrêt. Inerte si `!config.enabled()`.
- **`RPGQuestBootstrap`** : `HealthSource` extrait en variable locale (réutilisé par le bridge #37
  **et** l'agent), enregistrement de `PlugAdminAgent` après `WebAdminServer`.

### 2. Control Panel — package `com.lodygames.rpgquest.panel.agent` (nouveau)

- **`AgentIdentity`** (`{id, environment, token}`, `tokenMatches` temps constant, `toString`
  redacté) + **`AgentRegistry`** (multi-agent, `authenticate` anti-timing).
- **`AgentSettings`** : bloc de config (agents, seuils, `action-expiry`, `defaultAgentId`) — un
  seul champ ajouté à `PanelConfig`.
- **`HeartbeatRecord`** / **`AgentActionRow`** / **`AgentActionStatus`**
  (`PENDING→DELIVERED→SUCCESS|FAILED|REJECTED`, ou `EXPIRED`).
- **`AgentStore`** (SQLite `control-panel.db`) : tables `agent_heartbeat` (upsert par agent) et
  `agent_action`. `deliverableActions` marque `PENDING→DELIVERED` (+ `deliver_count`), garde
  l'action livrée **jusqu'au résultat terminal**, expire les actions trop vieilles.
  `recordResult` refuse une action d'un autre agent, **idempotent** sur une action déjà terminale.
- **`AgentLiveness`** : `ONLINE`/`STALE`/`OFFLINE`/`UNKNOWN` à partir de `received_at` seul +
  seuils configurables (indépendant de toute session navigateur).
- **`AgentEndpoints`** : routes `/agent/v1/*` branchées sur le **même** `HttpServer` que les pages
  (port 8090, derrière nginx). Pipeline commun : drainage du corps, kill-switch → 503, méthode,
  auth par agent → 401, payload borné → 413, dispatch. Audit `agent.action.result`. Aucun secret
  en réponse ni en log.
- **`PanelConfigLoader`** : lit `agents`, `agent.<id>.environment`, `agent.<id>.token-env`
  (défaut `RPGQUEST_AGENT_TOKEN_<ID>`), `agent.stale-seconds` / `offline-seconds` /
  `action-expiry-seconds`, `target.<t>.agent`.
- **`PanelApp`** : `agentEndpoints.register(server)` ; **dashboard** — si la cible par défaut a un
  agent, section « AGENT DISTANT » **prioritaire** (statut, âge du dernier heartbeat, version,
  joueurs, uptime, mondes) ; section « Bridge local » conservée en secondaire (bannière rouge
  seulement si c'est le seul mode). Nouvelle page **`/agents`** (owner, permission
  `DIAGNOSTICS_READ`) : liste des agents + fraîcheur + formulaire d'envoi de l'action de preuve
  `player.variable.get` (permission `ACTION_VARIABLE_GET`, CSVRF synchroniseur) + historique des
  actions/résultats. Nav « Agents » activée.
- **`Http.json(...)`** ajouté ; `PanelMain` construit `AgentStore` et le passe à `PanelApp`.

### 3. Configuration / scripts / .gitignore

- `scripts/plugadmin-agent.properties.example` (nouveau, **sans secret**) — modèle du fichier
  serveur, avec la procédure de dépôt FTP via `deploy-verygames.sh --also`.
- `.gitignore` : `plugadmin-agent.properties`, `scripts/plugadmin-agent.properties`.
- `control-panel/control-panel.properties.example`, `scripts/plugadmin/control-panel.properties.example`,
  `scripts/plugadmin/plugadmin.env.example` : bloc agent + `RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV`.

### 4. Déploiement PlugAdmin sur AWS (exécuté par cette session)

- `/etc/plugadmin/control-panel.properties` : bloc agent ajouté (`agents=rpgquest-dev`,
  `target.dev.agent=rpgquest-dev`, seuils). **Non secret.**
- `/etc/plugadmin/plugadmin.env` : `RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV` **généré** (`openssl rand`,
  40 car.) et ajouté, `0640 root:plugadmin`. **Valeur jamais affichée** (ni ici, ni en log, ni
  au terminal).
- `scripts/plugadmin/deploy.sh` : rebuild `:control-panel:installDist`, swap `/opt/plugadmin/app`
  (release précédente sauvegardée `releases/20260907-112054`), `systemctl restart plugadmin`.
- Redéployé une 2e fois depuis le **code committé** (branche `feat/51-…`) — release
  `20260907-114608`, `event=panel_started port=8090`, `/health` 200.
- **Vérifications live** (AWS) :
  - `curl http://127.0.0.1:8090/health` → `{"panel":"ONLINE",…}` 200 ;
  - `GET /agent/v1/actions` sans jeton → **401** `{"error":{"code":"unauthorized",…}}` ;
  - mauvais jeton → **401** ; `GET /agent/v1/heartbeat` → **405** ; `POST` mauvais jeton → **401** ;
  - public via nginx `https://plugadmin.lodylands.com/agent/v1/actions` (mauvais jeton) → **401** ;
  - **round-trip avec le VRAI jeton configuré** (script agissant comme faux agent depuis l'instance) :
    `POST https://plugadmin.lodylands.com/agent/v1/heartbeat` → **200** `{"ok":true,"received_at":…}`,
    log `event=agent_heartbeat agent=rpgquest-dev env=dev version=0.1.0-SNAPSHOT players=0/20` ;
    `GET /agent/v1/actions` → **200** `{"actions":[]}`, log `event=agent_actions_poll count=0`.
    Le heartbeat de test a ensuite été **supprimé** de `agent_heartbeat` pour que le dashboard
    affiche honnêtement « Aucun heartbeat reçu » tant que le vrai agent VeryGames n'est pas
    connecté.
  - **non-régression** `dig.lodygames.com` / `lodylands.com` / `www` / `beta` : `200`/`301`
    **inchangés** avant et après ; `plugadmin.lodylands.com/health` `200`.

### 5. Déploiement VeryGames DEV + redémarrage RCON (exécuté par cette session)

- **RCON VeryGames** : outils minimaux ajoutés — `scripts/verygames-rcon.py` (client Source RCON
  pur Python, lit `RCON_*` de `~/.config/rpgquest/verygames.env`, mot de passe jamais affiché) et
  `scripts/verygames-restart.sh` (`stop` RCON → attente retour ONLINE). RCON validé : `list`,
  `save-all`, `stop` OK ; VeryGames relance automatiquement le processus.
- **Fichier agent** préparé hors dépôt (`~/.config/rpgquest/plugadmin-agent.properties`, `600`)
  avec `enabled=true`, `base-url=https://plugadmin.lodylands.com`, `agent-id=rpgquest-dev`,
  `environment=dev`, `token=<le jeton généré sur AWS>` (lu via `sudo grep`, **jamais affiché**).
- **`scripts/deploy-verygames.sh -y --allow-no-backup --also …plugadmin-agent.properties:RPGQuest/plugadmin-agent.properties`** :
  `./gradlew test` + `build` OK (UP-TO-DATE), backup du JAR en ligne
  (`rpgquest-20260907T121945Z-predeploy.jar`, `bcc3a6ec…` = ancien JAR #36), transfert atomique du
  JAR #51 (`5e9d9a5e…`, 1 181 666 o) + du fichier agent (283 o). **Aucun autre fichier touché**
  (`data.db` / `config.yml` / `messages.yml` / `Citizens/` / mondes / autres plugins : intacts —
  le script les refuse).
- **`scripts/verygames-restart.sh`** : `stop` RCON → serveur OFFLINE → relance automatique
  VeryGames → **ONLINE** en < 1 min.

## Validation live #51 (exécutée)

| Vérification | Résultat |
|---|---|
| JAR #51 déployé sur VeryGames | **OUI** (`5e9d9a5e…`, backup `…20260907T121945Z-predeploy.jar`) |
| `plugins/RPGQuest/plugadmin-agent.properties` déployé | **OUI** (283 o) |
| Redémarrage via RCON | **OK** (`stop` → OFFLINE → auto-relance → ONLINE) |
| Serveur revenu ONLINE | **OK** (`verygames-rcon.py list` répond, 0 joueur) |
| HTTPS sortant VeryGames → PlugAdmin | **OK** — heartbeats réels toutes les ~20 s |
| Heartbeat réel reçu | **OK** — `event=agent_heartbeat agent=rpgquest-dev env=dev version=0.1.0-SNAPSHOT players=0/999` ; ligne `agent_heartbeat` en base : `plugin_version=0.1.0-SNAPSHOT`, `server_state=ONLINE`, `uptime_seconds=45`, mondes `hub`/`claims`/`wild` **tous `loaded:true`** |
| Dashboard RPGQuest DEV ONLINE | **OK** (côté données) — heartbeat frais présent, âge < seuil `stale` (45 s) → `AgentLiveness=ONLINE` ; le rendu « AGENT DISTANT / ONLINE » est couvert par `PanelAppTest.agentHeartbeatFlipsDashboardToOnlineAndFeedsAgentsPage`. Confirmation visuelle owner : ouvrir `https://plugadmin.lodylands.com/dashboard`. |
| `player.variable.get` (aller-retour complet) | **OK** — action PENDING créée côté PlugAdmin (`AgentStore`), relevée par l'agent VeryGames (`deliver_count=1`), exécutée via `PlayerVariableRepository`, résultat renvoyé : **`status=SUCCESS`**, `value` = *(non défini)*, `message` = « Rondoudou9000 : CLAIM_TIER_1 absente (équivaut à non définie). » — cohérent (`Rondoudou9000` est un compte de test neuf sans `CLAIM_TIER_1`). Audit `agent.action.result` = `SUCCESS`. |
| Type d'action inconnu | **REJECTED** — `server.shutdown` → `status=REJECTED`, « Type d'action non whitelisté : « server.shutdown ». », **aucune exécution**. Audit `agent.action.result` = `REJECTED`. |
| Duplicate / idempotence | **OK** — action `SUCCESS` remise en `DELIVERED` (`completed_at=NULL`) → l'agent la re-reçoit, **re-poste le résultat mémorisé sans ré-exécuter** (même message, retour en un cycle de poll), `deliver_count` passe à 2, `status` redevient `SUCCESS`. (Assertion stricte « pas de ré-lecture » : `AgentLoopTest.duplicateActionIdIsNotReExecuted`.) |
| Panne PlugAdmin n'affecte pas Minecraft | **OK** — `systemctl stop plugadmin` ~45 s : `verygames-rcon.py list` répond toujours, serveur ONLINE. |
| Reconnexion agent après retour PlugAdmin | **OK** — `systemctl start plugadmin` → `/health` en ~2 s ; nouveau `event=agent_heartbeat` ~40 s plus tard (fin de la fenêtre de backoff). |
| Email SMTP de fin de tests | **OK** — voir « Notification e-mail » ci-dessous |

Les lignes d'action de test (`created_by=claude-live-test`) sont laissées en base comme trace de
validation (visibles dans `/agents`).

### Notification e-mail

`scripts/plugadmin/send-mail.py` (nouveau, minimal, stdlib) — lit `~/.config/plugadmin/smtp.env`
(`send.one.com:465` SMTPS implicite, `plugadmin@lodywood.be`, mot de passe **jamais affiché**).
Email **envoyé** à `lloyd.helpdesk@gmail.com`, sujet « PlugAdmin — Issue #51 testée sur VeryGames
DEV », corps = résumé honnête des résultats ci-dessus. `MAIL_EXIT=0`. (Un échec SMTP n'aurait pas
annulé le déploiement #51 — il aurait été consigné séparément.)

## Fichiers créés

Plugin :
- `src/main/java/com/lodygames/rpgquest/web/agent/{AgentConfig,AgentConfigLoader,AgentActionType,
  AgentAction,AgentActionOutcome,AgentActionExecutor,PlayerDirectory,BukkitPlayerDirectory,
  PlayerVariables,ProcessedActionCache,HeartbeatPayload,PlugAdminTransport,PlugAdminClient,
  PlugAdminUnavailableException,AgentLoop,PlugAdminAgent}.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/{AgentConfigLoaderTest,AgentActionExecutorTest,
  ProcessedActionCacheTest,AgentLoopTest,PlugAdminClientTest}.java`

Control Panel :
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/{AgentIdentity,AgentRegistry,
  AgentSettings,AgentActionStatus,HeartbeatRecord,AgentActionRow,AgentLiveness,AgentStore,
  AgentEndpoints}.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/agent/{AgentLivenessTest,
  AgentStoreTest,AgentEndpointsTest}.java`

Docs / scripts :
- `docs/control-panel/AGENT.md` (nouveau — architecture complète, contrat, sécurité, diagnostic,
  rollback, ajout d'un futur serveur)
- `scripts/plugadmin-agent.properties.example`
- `scripts/verygames-rcon.py` (client Source RCON minimal, pur Python)
- `scripts/verygames-restart.sh` (stop RCON → attente retour ONLINE)
- `scripts/plugadmin/send-mail.py` (notification SMTP minimale, stdlib)
- `docs/claude-reports/2026-09-07_1127_plugadmin-outbound-agent-issue-51.md` (ce rapport)

## Fichiers modifiés

- Plugin : `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- Control Panel : `panel/PanelMain.java`, `panel/config/{PanelConfig,PanelConfigLoader}.java`,
  `panel/http/Http.java`, `panel/web/{PanelApp,Layout}.java`,
  `src/test/.../panel/support/TestConfig.java`, `src/test/.../panel/web/PanelAppTest.java`,
  `control-panel/control-panel.properties.example`
- `.gitignore`
- `scripts/plugadmin/{control-panel.properties.example,plugadmin.env.example}`
- `scripts/deploy-verygames.sh` (entête + message final : RCON disponible, plus « manuel owner »)
- Docs : `docs/control-panel/{README,ARCHITECTURE,CONFIGURATION,SECURITY,RPGQUEST_BRIDGE,ROADMAP,
  DECISIONS,DEPLOYMENT_AWS,AGENT}.md`, `docs/current_state.md`,
  `docs/deployment/{VERYGAMES.md,SERVER_CHANGELOG.md}`, `docs/claude-reports/README.md`

## Base de données / migrations

**Aucune migration `data.db`.** Le Control Panel ajoute 2 tables à **`control-panel.db`**
(`agent_heartbeat`, `agent_action`) via `CREATE TABLE IF NOT EXISTS` (idempotent), créées au 1er
démarrage. Vérifié sur AWS : service redémarré sans erreur, tables créées.

## Configuration / données

- **Serveur RPGQuest (VeryGames)** : nouveau fichier `plugins/RPGQuest/plugadmin-agent.properties`
  (hors Git, contient le jeton). Sans lui : agent **inerte**, aucun changement de comportement.
- **PlugAdmin (AWS)** : `agents=rpgquest-dev` dans `/etc/plugadmin/control-panel.properties` ;
  `RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV` dans `/etc/plugadmin/plugadmin.env` (généré, hors dépôt).
- Aucun changement de `config.yml` plugin, aucun changement `PluginConfig`.

## Tests automatiques

- **Plugin** : `./gradlew :test` → **1005 tests, 0 échec** (17 skipped préexistants). Nouveaux :
  `web.agent.*` = 30 tests (`AgentConfigLoaderTest` 6, `AgentActionExecutorTest` 7,
  `ProcessedActionCacheTest` 4, `AgentLoopTest` 8, `PlugAdminClientTest` 6).
- **Control Panel** : `./gradlew :control-panel:test` → **44 tests, 0 échec**. Nouveaux :
  `panel.agent.*` = 21 (`AgentLivenessTest` 4, `AgentStoreTest` 6, `AgentEndpointsTest` 11) +
  `PanelAppTest` +1 (12 : intégration heartbeat → dashboard ONLINE → page Agents → poll d'action).
- **`./gradlew build`** : **BUILD SUCCESSFUL** (plugin + web-api + control-panel).

Couverture des points demandés (phase 16) : heartbeat valide accepté ; jeton absent/invalide
refusé ; agent inconnu refusé ; heartbeat stocké ; ONLINE/STALE/OFFLINE ; payload trop gros → 413 ;
agent désactivé (fail-closed) ; panne réseau → pas d'exception + backoff ; aucune requête réseau
sur le thread principal (logique dans `AgentLoop` sans Bukkit) ; action PENDING récupérée
seulement par la bonne cible ; action d'un autre agent refusée ; type inconnu → `REJECTED` ;
`variable.get` fonctionne (présente / absente / par UUID) ; résultat stocké ; duplicate action id
non rejoué (`AgentLoopTest.duplicateActionIdIsNotReExecuted`) ; résultat perdu → renvoyé sans
ré-exécution ; secret absent des réponses/`toString`.

**Machine DEV ~900 Mo RAM** : suite plugin lente (~12 min) mais verte.

## Tests manuels à effectuer

Le parcours phase 14 a été **exécuté par cette session** via RCON + accès direct à
`control-panel.db` (voir « Validation live #51 »). Reste **une** vérification purement visuelle,
non bloquante : l'owner ouvre `https://plugadmin.lodylands.com/dashboard` (login navigateur) et
confirme l'affichage « RPGQuest DEV — ONLINE / AGENT DISTANT » et la page `/agents`.

## Résultat attendu

`https://plugadmin.lodylands.com/dashboard` (obtenu) :

```
RPGQuest DEV — ONLINE   (via AGENT DISTANT)
Dernier heartbeat : il y a quelques s
Version plugin : 0.1.0-SNAPSHOT
Protocole agent : agent/v1
Joueurs : 0 / 999      Uptime plugin : …
Mondes : hub / claims / wild chargés
```

Page `/agents` : action `player.variable.get` → `SUCCESS` (résultat exact obtenu :
« Rondoudou9000 : CLAIM_TIER_1 absente »).

## Reset / retour à l'état initial

- **Serveur RPGQuest** : `enabled=false` dans `plugadmin-agent.properties` (ou supprimer le
  fichier) + redémarrer → agent inerte. Le JAR peut rester en place (code dormant). Rollback JAR :
  `scripts/rollback-verygames.sh --latest`.
- **PlugAdmin AWS** : `agents=` (vide) dans `/etc/plugadmin/control-panel.properties` +
  `systemctl restart plugadmin` → `/agent/v1/*` répond 401 partout, dashboard retombe sur le
  bridge local. Rollback code : `scripts/plugadmin/rollback.sh app` (restaure la release
  précédente sous `/opt/plugadmin/releases/`, ex. `20260907-114552`).
- Dépôt : `git checkout feat/44-plugadmin-aws-deploy` (ou supprimer la branche `feat/51-…`).
- Aucune migration `data.db` à défaire ; tables `agent_*` de `control-panel.db` sans effet sur
  RPGQuest.

## Déploiement VeryGames

### À transférer — **FAIT par cette session**
1. **JAR** `rpgquest-0.1.0-SNAPSHOT.jar` de la branche `feat/51-plugadmin-outbound-agent`
   (`5e9d9a5e…`, 1 181 666 o). Transfert atomique FTP ; backup automatique du JAR précédent
   (`rpgquest-20260907T121945Z-predeploy.jar`, `bcc3a6ec…` = ancien JAR #36).
2. **`plugins/RPGQuest/plugadmin-agent.properties`** (283 o) — préparé hors dépôt à partir de
   `scripts/plugadmin-agent.properties.example`, `token=` = jeton lu sur AWS via `sudo grep`
   (jamais affiché). Transfert via `scripts/deploy-verygames.sh --also …`.

### Ne PAS transférer/altérer — **respecté**
`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, mondes, `Citizens/`, autres plugins :
**intacts** (le script les refuse ; seul `plugadmin-agent.properties` a été ajouté sous
`plugins/RPGQuest/`).

### Redémarrage — **FAIT via RCON**
`scripts/verygames-restart.sh` : `save-all` → `stop` (RCON) → serveur OFFLINE → VeryGames relance
automatiquement → **ONLINE** en < 1 min. (RCON DEV `51.68.57.28:7469`, validé — l'ancienne mention
« pas de RCON, redémarrage manuel owner » était erronée.)

### Migration automatique
Aucune côté RPGQuest. `control-panel.db` (AWS) : tables `agent_*` créées automatiquement (fait,
vérifié).

### Rollback (si besoin plus tard)
`scripts/rollback-verygames.sh --latest` (restaure `…20260907T121945Z-predeploy.jar`) puis
`scripts/verygames-restart.sh`. Ou simplement `enabled=false` dans `plugadmin-agent.properties`
+ restart → agent inerte, JAR #51 conservé.

## Rollback

Voir « Reset / retour à l'état initial » et `docs/control-panel/AGENT.md` §12.

## Logs / diagnostic

- Serveur RPGQuest : `Agent PlugAdmin configuré : …`, `Agent PlugAdmin démarré : cible
  rpgquest-dev …`, puis `event=plugadmin_probe status=ok detail="HTTP 200" …` (connectivité
  confirmée) ou `status=failed …` (sortie HTTPS bloquée → backoff). En cas de panne :
  `Agent PlugAdmin : …` (limité à 1/min).
- PlugAdmin : `journalctl -u plugadmin` → `event=agent_heartbeat agent=rpgquest-dev env=dev
  version=… players=…`, `event=agent_actions_poll …`, `event=agent_action_result …`.
- Dashboard : section « AGENT DISTANT » ; page `/agents` (owner).
- Table détaillée des symptômes/pistes : `docs/control-panel/AGENT.md` §11.

## Documentation mise à jour

`docs/control-panel/` : **`AGENT.md`** (nouveau + correction « ni RCON »), `README.md`,
`ARCHITECTURE.md`, `CONFIGURATION.md`, `SECURITY.md`, `RPGQUEST_BRIDGE.md`, `ROADMAP.md`
(Étape 0c), `DECISIONS.md` (ADR-011, ADR-004 marquée remplacée), `DEPLOYMENT_AWS.md` (§10).
`docs/current_state.md`. `docs/deployment/VERYGAMES.md` : nouvelle section **« Accès RCON
VeryGames »** + procédure agent mise à jour (RCON, plus « manuel owner »).
`docs/deployment/SERVER_CHANGELOG.md` : entrée 2026-09-07 (déploiement effectué + RCON).
`scripts/deploy-verygames.sh` : entête + message final corrigés. `docs/claude-reports/README.md`.

## Commit(s) / branche

Branche `feat/51-plugadmin-outbound-agent` (depuis `feat/44-plugadmin-aws-deploy`), **non
fusionnée** :

| Commit | Sujet |
|---|---|
| `aea71ca` | `feat(agent): agent HTTPS sortant RPGQuest -> PlugAdmin (issue #51)` (plugin) |
| `b1e607a` | `feat(plugadmin): endpoints /agent/v1, persistance et dashboard agent (issue #51)` |
| `5a25275` | `chore(deploy): configuration de l'agent PlugAdmin (issue #51)` |
| `2a9fa38` | `docs(control-panel): agent sortant issue #51` (AGENT.md, ADR-011, runbooks) |
| `858e7d4` | `chore(deploy): redemarrage RPGQuest DEV via RCON VeryGames (issues #10 / #51)` |
| _(dernier commit de la branche)_ | `docs(deploy): #51 déployée + validée sur VeryGames DEV (RCON, tests live, SMTP)` — ce rapport dans sa forme finale (`git log feat/51-plugadmin-outbound-agent`) |

## Limitations / travail restant

1. **Confirmation visuelle du dashboard par l'owner** (login navigateur) — les données live sont
   en place et le rendu est testé, mais aucun humain n'a *visuellement* ouvert
   `https://plugadmin.lodylands.com/dashboard` cette session (mot de passe owner non communiqué).
2. **Rate limiting applicatif** du endpoint agent : non implémenté (nginx devant + backoff agent).
   À durcir si d'autres agents apparaissent.
3. **Cache d'idempotence agent en mémoire** : perdu au redémarrage du plugin. Risque résiduel
   (résultat perdu *pendant* un redémarrage) documenté et acceptable — la seule action ouverte
   est une lecture pure.
4. **Intégration RCON dans `deploy-verygames.sh`** : laissée en script séparé
   (`verygames-restart.sh`) volontairement — pas de refonte du script de déploiement. Peut être
   fusionnée plus tard si souhaité.
5. `#51` **non fermée** (revue à faire). `#10` : la capacité RCON/restart est documentée et
   outillée mais l'issue reste ouverte. `#44`/`#37`/`#38`/`#39`/`#45` non touchées. Aucune
   branche fusionnée.

## Prochaine étape suggérée

1. **Owner** : ouvrir `https://plugadmin.lodylands.com/dashboard` et confirmer visuellement
   « RPGQuest DEV — ONLINE / AGENT DISTANT » + la page `/agents`.
2. Revue de la branche `feat/51-plugadmin-outbound-agent` → PR / merge quand jugé bon.
3. Commenter #51 et #10 avec le résultat (fait par cette session : voir « Documentation »).
