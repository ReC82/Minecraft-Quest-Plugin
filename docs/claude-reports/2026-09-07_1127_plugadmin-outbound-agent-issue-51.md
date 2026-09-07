# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-07
* Heure : 11:27 (heure locale réelle de la machine)
* Sujet : Issue #51 — agent **sortant** VeryGames → PlugAdmin : état live (heartbeat) et pipeline
  d'actions whitelistées, sans port entrant côté VeryGames. Inversion du flux du bridge #37.
* Statut : **PARTIAL** — socle complet livré, testé (`./gradlew clean build` vert : plugin
  1005 tests, control-panel 44, web-api inchangé), **PlugAdmin AWS redéployé depuis le code
  committé** et le contrat `/agent/v1/*` vérifié **de bout en bout avec le vrai jeton** contre
  l'URL publique (`POST heartbeat` → 200, `GET actions` → 200, logs `event=agent_heartbeat` /
  `agent_actions_poll`). Reste la **validation live VeryGames** : déploiement du JAR + du fichier
  de config agent + redémarrage du serveur RPGQuest = **actions manuelles owner** (VeryGames
  n'expose ni API ni RCON). Tant que ces étapes ne sont pas faites, aucun heartbeat *réel* du
  serveur n'arrive → dashboard « Aucun heartbeat reçu » (attendu).
* Branche Git : `feat/51-plugadmin-outbound-agent` (créée depuis `feat/44-plugadmin-aws-deploy`).
* Commit(s) : voir « Commit(s) / branche ».
* Début de la tâche : 2026-09-07 09:43:49
* Fin de la tâche : 2026-09-07 11:47:30
* Durée totale : 02:03:41

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

### Phase 1 — preuve de connectivité (décision)

VeryGames ne permettant pas à Claude de redémarrer le serveur (aucune API/RCON — cf.
`scripts/deploy-verygames.sh`), une preuve « micro » séparée aurait imposé **deux** cycles de
redémarrage manuel owner. Décision (validée avec l'utilisateur) : **construire tout le socle**,
déployer une fois, et traiter le **premier heartbeat reçu** comme preuve de connectivité. Le code
émet de toute façon une ligne de log explicite au démarrage :
`event=plugadmin_probe status=ok|failed …`. Si la sortie HTTPS est bloquée chez VeryGames, la
preuve (log `status=failed`) et l'alternative (cf. « Limitations ») sont documentées sans
sur-construire davantage — mais rien n'indique que VeryGames bloque (bStats / update-checkers
sortants fonctionnent en général).

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
- `docs/claude-reports/2026-09-07_1127_plugadmin-outbound-agent-issue-51.md` (ce rapport)

## Fichiers modifiés

- Plugin : `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- Control Panel : `panel/PanelMain.java`, `panel/config/{PanelConfig,PanelConfigLoader}.java`,
  `panel/http/Http.java`, `panel/web/{PanelApp,Layout}.java`,
  `src/test/.../panel/support/TestConfig.java`, `src/test/.../panel/web/PanelAppTest.java`,
  `control-panel/control-panel.properties.example`
- `.gitignore`
- `scripts/plugadmin/{control-panel.properties.example,plugadmin.env.example}`
- Docs : `docs/control-panel/{README,ARCHITECTURE,CONFIGURATION,SECURITY,RPGQUEST_BRIDGE,ROADMAP,
  DECISIONS,DEPLOYMENT_AWS}.md`, `docs/current_state.md`,
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

`PENDING MANUAL VALIDATION` — nécessitent une action owner sur VeryGames (redémarrage serveur).
Voir « Test live » ci-dessous et le plan phase 14 de la demande.

## Résultat attendu

Après le déploiement VeryGames (JAR + fichier agent + redémarrage), ouvrir
`https://plugadmin.lodylands.com/dashboard` :

```
RPGQuest DEV — ONLINE   (via AGENT DISTANT)
Dernier heartbeat : il y a 8 s
Version plugin : 0.1.0-SNAPSHOT
Protocole agent : agent/v1
Joueurs : X / Y      Uptime plugin : …
Mondes : hub / claims / wild chargés
```

et sur `/agents`, envoyer `player.variable.get` (`CLAIM_TIER_1` pour un joueur) → résultat
`SUCCESS` avec la valeur.

## Reset / retour à l'état initial

- **Serveur RPGQuest** : `enabled=false` dans `plugadmin-agent.properties` (ou supprimer le
  fichier) + redémarrer → agent inerte. Le JAR peut rester en place (code dormant). Rollback JAR :
  `scripts/rollback-verygames.sh --latest`.
- **PlugAdmin AWS** : `agents=` (vide) dans `/etc/plugadmin/control-panel.properties` +
  `systemctl restart plugadmin` → `/agent/v1/*` répond 401 partout, dashboard retombe sur le
  bridge local. Rollback code : `scripts/plugadmin/rollback.sh app` (release
  `20260907-112054`).
- Dépôt : `git checkout feat/44-plugadmin-aws-deploy` (ou supprimer la branche `feat/51-…`).
- Aucune migration `data.db` à défaire ; tables `agent_*` de `control-panel.db` sans effet sur
  RPGQuest.

## Déploiement VeryGames

### À transférer
1. **Le nouveau JAR** `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` (branche
   `feat/51-plugadmin-outbound-agent`). À ce stade l'agent est **inerte** (pas de fichier de
   config) → aucune régression, aucune connexion sortante.
2. **Le fichier de config agent** `plugins/RPGQuest/plugadmin-agent.properties` — à préparer
   **localement hors dépôt** à partir de `scripts/plugadmin-agent.properties.example`, avec
   `enabled=true`, `base-url=https://plugadmin.lodylands.com`, `agent-id=rpgquest-dev`,
   `environment=dev`, et `token=` = **le jeton généré sur AWS** (le récupérer par
   `sudo grep RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV /etc/plugadmin/plugadmin.env` — ne pas le coller
   dans un canal non sécurisé). Transfert :
   `scripts/deploy-verygames.sh --also <fichier local>:RPGQuest/plugadmin-agent.properties`
   ou upload manuel FTP dans `plugins/RPGQuest/`.

### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml`, mondes, `Citizens/`, autres plugins. Le fichier agent
est le seul ajout sous `plugins/RPGQuest/`.

### Redémarrage requis
**Oui** — nouveau JAR + prise en compte du fichier agent. Le redémarrage du serveur VeryGames est
une **action manuelle owner** (panel VeryGames ; pas d'API/RCON).

### Migration automatique
Aucune côté RPGQuest. `control-panel.db` (AWS) : tables `agent_*` créées automatiquement (fait).

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

`docs/control-panel/` : **`AGENT.md`** (nouveau), `README.md`, `ARCHITECTURE.md`,
`CONFIGURATION.md`, `SECURITY.md`, `RPGQUEST_BRIDGE.md`, `ROADMAP.md` (Étape 0c),
`DECISIONS.md` (ADR-011, ADR-004 marquée remplacée), `DEPLOYMENT_AWS.md` (§10 activation agent).
`docs/current_state.md`, `docs/deployment/VERYGAMES.md` (§ agent sortant),
`docs/deployment/SERVER_CHANGELOG.md` (entrée 2026-09-07), `docs/claude-reports/README.md`.

## Commit(s) / branche

Branche `feat/51-plugadmin-outbound-agent` (depuis `feat/44-plugadmin-aws-deploy`), **non
fusionnée** :

| Commit | Sujet |
|---|---|
| `aea71ca` | `feat(agent): agent HTTPS sortant RPGQuest -> PlugAdmin (issue #51)` (plugin) |
| `b1e607a` | `feat(plugadmin): endpoints /agent/v1, persistance et dashboard agent (issue #51)` |
| `5a25275` | `chore(deploy): configuration de l'agent PlugAdmin (issue #51)` |
| _(ce commit)_ | `docs(control-panel): agent sortant issue #51` — AGENT.md, ADR-011, runbooks, ce rapport |

## Limitations / travail restant

1. **Validation live VeryGames** — non exécutable par cette session (redémarrage serveur =
   action owner). Étapes exactes : voir « Déploiement VeryGames » + phase 14. Tant que ce n'est
   pas fait, le dashboard affiche « Aucun heartbeat reçu de l'agent rpgquest-dev » (attendu).
2. **Preuve de connectivité HTTPS sortante VeryGames** — sera confirmée par le log
   `event=plugadmin_probe status=ok` au premier heartbeat. Si `status=failed` persiste : la
   sortie HTTPS est bloquée chez VeryGames — dans ce cas, ne PAS sur-construire ; alternatives
   possibles : (a) relais/tunnel sortant applicatif, (b) revenir à un export
   `admin-snapshot.json` poussé par le mécanisme d'export existant (ADR-004, abandonnée mais
   réactivable), (c) RPGQuest DEV headless co-localisé sur AWS (le bridge local #37 fonctionne
   alors sans changement).
3. **Rate limiting applicatif** du endpoint agent : non implémenté (nginx devant + backoff agent).
   À durcir si d'autres agents apparaissent.
4. **Cache d'idempotence agent en mémoire** : perdu au redémarrage du plugin. Risque résiduel
   (résultat perdu *pendant* un redémarrage) documenté et acceptable — la seule action ouverte
   est une lecture pure.
5. `#51` **non fermée**. `#44`, `#37`, `#38`/`#39`/`#45` non touchées. Aucune branche fusionnée.

## Prochaine étape suggérée

1. **Owner** : récupérer le jeton sur AWS (`sudo grep RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV
   /etc/plugadmin/plugadmin.env`), préparer `plugadmin-agent.properties`, déployer le JAR + le
   fichier, **redémarrer le serveur RPGQuest**.
2. Vérifier le log `event=plugadmin_probe` puis le dashboard PlugAdmin (« RPGQuest DEV — ONLINE »).
3. Sur `/agents`, envoyer `player.variable.get` sur un vrai joueur → vérifier le résultat.
4. Couper PlugAdmin quelques minutes (`systemctl stop plugadmin`) → vérifier que Minecraft n'est
   pas affecté, puis reconnexion automatique.
5. Commenter #51 avec le résultat de la validation live.
