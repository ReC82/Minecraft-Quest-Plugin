# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-16
* Heure : 02:31
* Sujet : Issue #421 — acheminer l'instantané du serveur Minecraft vers le portail AWS, et statut honnête du serveur (en ligne / hors ligne annoncé / non actualisé / inconnu)
* Statut : DONE (livré et déployé, inactif ; activation et validation navigateur/jeu = propriétaire)
* Branche Git : `feature/421-canal-snapshot-portail` (PR #449, fusionnée dans `chore/257-ci-github-actions` → `10c7785`) ; rapport sur `docs/421-rapport-canal-instantane`
* Commit actuel si disponible : `10c7785` (code déployé)
* Début de la tâche : 2026-10-16 02:02:57
* Fin de la tâche : 2026-10-16 02:33:00
* Durée totale : 00:30:03

## Demande

Ticket #421 (enfant de #52) : le plugin écrit `snapshot.json` chez VeryGames, le portail le lit sur
son propre disque AWS ; aucun canal ne les relie. Attendu : canal **sortant** HTTPS du serveur
Minecraft vers AWS, jeton dédié, route d'ingestion fermée par nginx sauf authentification, validation
taille/format, écriture atomique, aucun UUID/IP/position, échec journalisé sans secret et sans
bloquer le thread principal, aucun accès SQL croisé. Exigence ajoutée par le propriétaire le 15/10 :
ne plus afficher un bandeau rouge « hors ligne » sans preuve — distinguer ONLINE, OFFLINE confirmé,
STALE (date du dernier relevé) et UNKNOWN, sur l'accueil, `/statut` et tout widget, avec tests.

## Analyse

- Le portail (`web-api`) résolvait l'état par `ServerState.resolve` : absent → `no_data`, périmé →
  `stale`, les deux rendus `online=false` et le même bandeau rouge « Le serveur de jeu ne répond pas »
  (`PageLayout`), « ● Hors ligne ou injoignable » sur l'accueil.
- Le précédent `/lien` (#53) fournissait le modèle exact : fichier `.properties` hors Git surchargé
  par l'environnement, client HTTP JDK, route à jeton dédié avec exception nginx `location =`.
- Seul le serveur peut prouver un arrêt : d'où l'instantané d'arrêt publié par le plugin dans
  `WebSnapshotWriter.stop()` (le seul cas qui donne OFFLINE). Un crash ou une panne du canal donne
  STALE, jamais OFFLINE.

## Travail effectué

Portail :
- `ServerStatus` (record + `Kind` ONLINE/STALE/OFFLINE/UNKNOWN, date du dernier relevé, bandeau) ;
  `ServerState.resolve` écrit `server.status` et `server.lastUpdate` ; `server.online=false` reçu →
  OFFLINE même ancien.
- `PageLayout.render` accepte un `ServerStatus` : bandeau gris neutre (`.unknown`) pour UNKNOWN/STALE,
  rouge seulement pour OFFLINE. Accueil (carte « Le serveur »), `/status`, Wiki, Classements,
  Joueurs, Confidentialité adaptés ; FAQ `/infos` explique « état inconnu » vs « hors ligne ».
- `/api/status` : `status`, `lastUpdate` ; `/health` : `server`.
- `ingest.SnapshotIngestHandler` sur `POST /api/snapshot` (chemin exact) : jeton
  `RPGQUEST_PORTAL_SNAPSHOT_TOKEN` (401, y compris avec le jeton général), 405 hors POST, 413 au-delà
  de `snapshot-ingest-max-bytes` (défaut 1 Mio), 400 si JSON invalide / `schemaVersion` hors 1–2 /
  `generatedAt` absent ou > 5 min dans le futur / `server.online` absent / clé contenant `uuid` —
  fichier précédent intact ; sinon écriture atomique (temporaire même dossier + rename), 200.
- nginx : `location = /api/snapshot` (POST, `client_max_body_size 1m`).

Plugin :
- `web.LocalSettingsFile` extrait de `PortalLinkConfigLoader` (refactor, comportement de `/lien`
  inchangé).
- `web.push.SnapshotPushConfig/Loader/Client` : `plugins/RPGQuest/portal-snapshot.properties` (hors
  Git, HTTPS obligatoire hors local, env `RPGQUEST_PORTAL_SNAPSHOT_*`), envoi bloquant depuis le fil
  `RPGQuest-WebExport` ; un seul avertissement par panne (statut HTTP ou « injoignable », jamais le
  jeton ni le contenu), un message au rétablissement.
- `WebSnapshotWriter` : `publish` = fichier local puis portail ; à l'arrêt, instantané minimal
  `server.online=false` (aucune donnée joueur) publié si un export a eu lieu ; drapeau `stopping`
  contre les écritures tardives.
- Bootstrap : chargement de la config ; avertissement si l'envoi est configuré alors que
  `web-export.enabled=false`.

Déploiement : portail AWS, vhost nginx, JAR DEV + redémarrage (détail ci-dessous).

## Fichiers créés

- `web-api/src/main/java/com/lodygames/rpgquest/webapi/ServerStatus.java`
- `web-api/src/main/java/com/lodygames/rpgquest/webapi/ingest/SnapshotIngestHandler.java`
- `web-api/src/main/java/com/lodygames/rpgquest/webapi/ingest/SnapshotIngestSettings.java`
- `web-api/src/test/java/com/lodygames/rpgquest/webapi/ServerStatusTest.java`
- `web-api/src/test/java/com/lodygames/rpgquest/webapi/ingest/SnapshotIngestTest.java`
- `src/main/java/com/lodygames/rpgquest/web/LocalSettingsFile.java`
- `src/main/java/com/lodygames/rpgquest/web/push/SnapshotPushClient.java`, `SnapshotPushConfig.java`, `SnapshotPushConfigLoader.java`
- `src/test/java/com/lodygames/rpgquest/web/push/SnapshotPushClientTest.java`, `SnapshotPushConfigLoaderTest.java`
- `scripts/portal-snapshot.properties.example`

## Fichiers modifiés

- web-api : `ServerState`, `WebApiConfig`, `WebApiConfigLoader`, `WebApiMain`, `HttpServerBootstrap`,
  `api/StatusHandler`, `api/HealthHandler`, `site/PageLayout`, `site/HomePageHandler`,
  `site/StatusPageHandler`, `site/WikiPageHandler`, `site/LeaderboardsPageHandler`,
  `site/PlayersPageHandler`, `site/PrivacyPageHandler`, `site/InfoPageHandler` ; tests
  `HttpServerBootstrapTest`, `PublicPortalV1Test` (attendent désormais « état inconnu »).
- plugin : `web/WebSnapshotWriter`, `web/link/PortalLinkConfigLoader`, `bootstrap/RPGQuestBootstrap` ;
  test `WebSnapshotWriterTest`.
- scripts : `web-portal/nginx-lodyquests-portal.conf`, `web-portal/portal.env.example`,
  `web-portal/web-api.properties.example`.
- docs : `WEB_API.md`, `WEB_PORTAL.md`, `deployment/WEB_PORTAL_AWS.md`, `RPGQUEST_BIBLE.md`,
  `current_state.md`, `MANUAL_TEST_PLAN.md` (TC-327), `deployment/SERVER_CHANGELOG.md`.

## Base de données / migrations

Aucune. Aucun accès SQL ajouté, ni du portail vers `data.db` ni l'inverse.

## Configuration / données

- Nouveau fichier plugin hors Git `portal-snapshot.properties` (**non créé** sur DEV).
- Nouvelle variable portail `RPGQUEST_PORTAL_SNAPSHOT_TOKEN` (**non posée**) ; nouvelle clé
  facultative `snapshot-ingest-max-bytes`.
- `web-export.enabled` reste `false` sur DEV, `player-data-public` reste `false` : rien n'est publié.

## Tests automatiques

- Local : `:web-api:test` complet vert ; plugin ciblé `WebSnapshotWriterTest` (8),
  `web.push.*` (6), `web.link.*` vert. Un premier passage a révélé une course (l'annonce d'arrêt
  dépendait de `lastWriteMillis`, posé après l'envoi) : corrigée par un drapeau `published`.
- CI GitHub PR #449 (deux exécutions) : plugin, control-panel, web-api, scripts, artefacts — tout
  vert ; CI verte aussi sur le commit de fusion `10c7785` (vérifiée par `deploy-verygames.sh`).

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` TC-327 : partie A (état livré) dans un
navigateur, partie B après activation par le propriétaire (FRESH/ONLINE en moins d'une minute,
OFFLINE à l'arrêt propre, STALE quand le canal est coupé). Aucun test en jeu n'a été fait.

## Résultat attendu

Aujourd'hui : le portail affiche « ● État inconnu » / bandeau gris « État du serveur de jeu inconnu —
aucune donnée reçue pour le moment », plus jamais le rouge « hors ligne » sans preuve. Après
activation : statut réel mis à jour toutes les 30 s.

## Reset / retour à l'état initial

`enabled=false` dans `portal-snapshot.properties` (ou fichier absent) ; jeton retiré de
`portal.env`. Le dernier `snapshot.json` reçu passe en « non actualisé ».

## Déploiement VeryGames

### À transférer
JAR `10c7785` — **fait** (02:29, 3 119 418 o, SHA-256 `be4986c0…62c8b9`).

### Ne PAS transférer/altérer
`data.db`, `config.yml` (ne pas activer `web-export` sans décision), aucun
`portal-snapshot.properties` tant que le propriétaire ne l'a pas décidé.

### Redémarrage requis
Oui — fait par RCON (02:29), serveur revenu en ligne, RPGQuest vert dans `/plugins`.

### Migration automatique
Aucune.

## Rollback

- JAR : `scripts/rollback-verygames.sh --latest` (sauvegarde `rpgquest-20261016T002912Z-predeploy.jar`).
- Portail : `scripts/web-portal/rollback.sh app` (release `20261016-022204`).
- nginx : restaurer `/opt/lodyquests-portal/releases/nginx-lodyquests-portal.pre-421-20261016-022215.bak`,
  `sudo nginx -t`, `sudo systemctl reload nginx`.

## Logs / diagnostic

- `curl -s http://127.0.0.1:8092/health` → `{"status":"UP","snapshot":"MISSING","server":"UNKNOWN",…}`.
- Public : POST `/api/snapshot` 401, GET 403, `/api/status` 404, POST `/api/link/confirm` 401.
- Plugin (au démarrage) : « Envoi d'instantané au portail désactivé (enabled != true dans
  portal-snapshot.properties) » attendu dans `latest.log` (non relu : pas d'accès console ici).

## Documentation mise à jour

`WEB_API.md` (route, quatre états, `/health`), `WEB_PORTAL.md` (roadmap étape 3, exploitation, tableau
V1), `deployment/WEB_PORTAL_AWS.md` (procédure d'activation, vérifications), `RPGQUEST_BIBLE.md`
(section « Portail — envoi de l'instantané web »), `current_state.md`, `MANUAL_TEST_PLAN.md` TC-327,
`SERVER_CHANGELOG.md`. Pas de page `docs-site/` existante pour le portail.

## Limitations / travail restant

- Activation = décision propriétaire (#55) : jeton des deux côtés, `web-export.enabled: true`,
  fichier sur DEV, redémarrage (procédure dans `WEB_PORTAL_AWS.md`).
- Un arrêt brutal (crash, kill) n'envoie pas d'annonce : le portail affichera « non actualisé »,
  ce qui est voulu.
- Hors ligne / non actualisé, les classements restent masqués (mode dégradé inchangé).

## Prochaine étape suggérée

Validation navigateur TC-327 partie A par le propriétaire, puis décision d'activer le canal sur DEV.
