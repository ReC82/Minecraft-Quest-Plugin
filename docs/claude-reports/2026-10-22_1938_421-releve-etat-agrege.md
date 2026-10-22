# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-22
* Heure : 19:38
* Sujet : #421 — relevé d'état agrégé Minecraft VeryGames → portail AWS, sans donnée de joueur (lot 1 WebPortal)
* Statut : DONE (livré, déployé, vérifié de bout en bout ; validation navigateur propriétaire en attente)
* Branche Git : `feature/421-statut-agrege` (PR #743, fusionnée dans `chore/257-ci-github-actions`) ; rapport : `docs/421-rapport-statut-agrege`
* Commit actuel si disponible : `55814445` (fusion), `1a331489` (web-api), `55f9fadb` (plugin + docs)
* Début de la tâche : 2026-10-22 19:00:18
* Fin de la tâche : 2026-10-22 19:38:44
* Durée totale : 00:38:26

## Demande
Session lqorch sur #421 (mission WebPortal du 22/10, lot 1) : reprendre le travail interrompu (commit
local non poussé `507afcc2`, wip non testé), rendre réelle la liaison VeryGames → AWS avec un transport
authentifié, des états ONLINE / OFFLINE / UNKNOWN / STALE corrects, une synchronisation idempotente, sans
double comptage, un diagnostic exploitable, puis déployer et vérifier les URLs réellement servies.
Interdits : publier ou ingérer pseudos / UUID / profils, toucher `web-export.enabled` ou
`player-data-public` sur DEV, contourner TLS ou l'authentification, secrets dans Git / logs / rapports.

## Analyse
Diagnostic de l'existant avant intervention :
- portail AWS `lodyquests-portal` en service depuis le 16/10 (`10c7785`), nginx avec le bloc
  `location = /api/snapshot`, TLS valide ; `/health` → `snapshot: MISSING`, `server: UNKNOWN` ;
- **ce qui empêchait la connexion** : (1) aucun jeton `RPGQUEST_PORTAL_SNAPSHOT_TOKEN` dans
  `/etc/lodyquests-portal/portal.env` (fichier entièrement commenté) ; (2) aucun
  `portal-snapshot.properties` sur DEV ; (3) surtout, **le seul contenu envoyable était l'instantané
  complet**, qui n'est produit que si `web-export.enabled: true` — interdit sans décision #55. Le canal ne
  pouvait donc pas fonctionner sans publier de données nominatives.
- Le commit `507afcc2` contenait la moitié portail d'une solution (relevé d'état agrégé `kind: status`),
  sans tests ni émetteur côté plugin.

Décision : canal à deux contenus sur la même route et le même jeton — le relevé d'état agrégé (toujours,
périmètre fermé) et l'instantané complet (seulement si l'export est activé, inchangé). Le relevé suffit à
établir les quatre états sans aucune donnée de joueur ; le nombre de joueurs connectés est un agrégat
explicitement permis par la mission.

## Travail effectué
- **Portail (web-api)** — repris du wip, relu et corrigé :
  - `StatusReportIngest` : corps `kind: status` validé en **périmètre fermé** (racine `schemaVersion`,
    `kind`, `generatedAt`, `bootId`, `sequence`, `server` ; `server` = `online`, `playerCount`,
    `maxPlayers` ; toute autre clé → 400 sans écriture), bornes, date future refusée ;
  - **idempotence** : même `bootId` + même `sequence` → `DUPLICATE`, plus petite → `OUT_OF_ORDER`, autre
    `bootId` plus ancien → `OUT_OF_ORDER` ; rien n'est écrit dans ces cas ; compteurs remplacés, jamais
    additionnés ; écriture atomique de `server-status.json` avec `receivedAt` (horloge portail) ;
  - `ServerState` : le relevé fait foi pour l'état dès qu'il est au moins aussi récent que l'instantané ;
    classements / profils ne viennent que d'un instantané complet frais (sinon vides, jamais inventés) ;
    correction par rapport au wip : les champs serveur de l'instantané sont fusionnés puis écrasés par
    ceux du relevé (au lieu d'être perdus) ;
  - `/health` : `heartbeat` (MISSING / FRESH / STALE) et `lastUpdate` en plus de `server` ;
  - journal portail : « nouveau démarrage reçu » et « arrêt annoncé », jamais de jeton.
- **Plugin** — nouveau `ServerStatusReporter` (`web.push`) : toutes les `status-interval-seconds`
  (30 s, 10–300), compteurs lus sur le thread principal, envoi depuis le fil `RPGQuest-PortalStatus`,
  envoi sauté si le précédent est en cours (pas de file pendant une panne), `bootId` aléatoire par
  démarrage, `sequence` croissante, relevé `online=false` à l'arrêt. Démarré seulement si
  `portal-snapshot.properties` est actif, **indépendamment de `web-export.enabled`**. `SnapshotPushClient`
  reçoit un libellé pour distinguer ses journaux (« de l'instantané » / « du relevé d'état »).
- Le commit wip `507afcc2` n'a jamais été poussé ; il est remplacé par deux commits propres sur une
  nouvelle branche (aucun historique réécrit).
- **Déploiement** (voir plus bas) puis vérification publique des quatre états.

## Fichiers créés
- `web-api/src/main/java/com/lodygames/rpgquest/webapi/ingest/StatusReportIngest.java`
- `web-api/src/test/java/com/lodygames/rpgquest/webapi/ingest/StatusReportIngestTest.java`
- `src/main/java/com/lodygames/rpgquest/web/push/ServerStatusReporter.java`
- `src/test/java/com/lodygames/rpgquest/web/push/ServerStatusReporterTest.java`
- ce rapport

## Fichiers modifiés
- web-api : `ServerState`, `SnapshotStore`, `api/HealthHandler`, `ingest/SnapshotIngestHandler`
- plugin : `bootstrap/RPGQuestBootstrap`, `web/push/SnapshotPushClient`, `SnapshotPushConfig`,
  `SnapshotPushConfigLoader`
- `scripts/portal-snapshot.properties.example`
- docs : `WEB_API.md`, `deployment/WEB_PORTAL_AWS.md`, `RPGQUEST_BIBLE.md`, `current_state.md`,
  `MANUAL_TEST_PLAN.md` (TC-327 partie C), `deployment/SERVER_CHANGELOG.md`, `SUIVI_VALIDATIONS_DEV.md`,
  `claude-reports/README.md`

## Base de données / migrations
Aucune. Aucun accès SQL ajouté, ni portail → `data.db`, ni l'inverse.

## Configuration / données
- Nouvelle clé optionnelle `status-interval-seconds` dans `portal-snapshot.properties` (défaut 30).
- AWS : `RPGQUEST_PORTAL_SNAPSHOT_TOKEN` ajouté à `/etc/lodyquests-portal/portal.env` (copie préalable
  `portal.env.bak-pre421-statut-20261022-191723`).
- DEV : `plugins/RPGQuest/portal-snapshot.properties` **créé** (`enabled=true`, base-url HTTPS du
  portail, jeton dédié). Copie locale hors dépôt : `~/.config/rpgquest/portal-snapshot.properties` (600).
- **Inchangés** : `config.yml` DEV (`web-export.enabled: false`), `player-data-public=false`, nginx, TLS.

## Tests automatiques
- `./gradlew :web-api:test` complet : vert (dont `StatusReportIngestTest` 6 : ONLINE sans instantané,
  doublon / retard sans réécriture, compteur remplacé, nouveau démarrage, OFFLINE annoncé puis STALE
  sans jamais OFFLINE, champs hors périmètre refusés sans écriture, jeton dédié obligatoire).
- `./gradlew :test --tests 'com.lodygames.rpgquest.web.push.*'` : 11 verts (dont
  `ServerStatusReporterTest` 5 : contrat de clés identique au portail, `bootId` conforme, séquence
  croissante + jeton, envoi lent → suivant sauté, portail injoignable sans exception).
- CI GitHub PR #743 : verte (plugin, control-panel, web-api, scripts, artefacts) ; CI du commit de
  fusion `55814445` : verte.
- Le test d'intégration inter-composants est le contrôle réel ci-dessous (plugin réel sur VeryGames →
  portail réel sur AWS via nginx/TLS) ; le contrat de clés est verrouillé des deux côtés par les tests.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` TC-327 partie C : navigateur ordinateur et
téléphone, « ● En ligne » + nombre de joueurs, passage à 1 en se connectant, arrêt VeryGames → « ● Hors
ligne », redémarrage → « ● En ligne ». Aucun test en jeu n'a été fait par Claude.

## Résultat attendu
Le portail public annonce l'état réel du serveur de jeu, mis à jour toutes les 30 s, sans qu'aucune
donnée de joueur ne transite.

## Reset / retour à l'état initial
Retirer la ligne `RPGQUEST_PORTAL_SNAPSHOT_TOKEN` de `portal.env` et redémarrer le portail (envois → 401,
état « non actualisé »), ou `enabled=false` dans `RPGQuest/portal-snapshot.properties` + redémarrage DEV.

## Déploiement VeryGames
### À transférer
Fait : JAR `55814445` (4231948 octets, SHA-256
`2b096e6dbeedd4322a0d67f679e880326f62bc4778e347dcf0a5d8ad1220b1ae`, retéléchargé et identique) et
`RPGQuest/portal-snapshot.properties` (créé, retéléchargé et identique). Portail AWS `5581444` via
`scripts/web-portal/deploy.sh`.
### Ne PAS transférer/altérer
`config.yml` (laisser `web-export.enabled: false`), `data.db`, mondes, contenus.
### Redémarrage requis
Oui (fichier lu au démarrage) — fait par RCON à 19:30 puis 19:31 (0 joueur), le second pour vérifier
l'arrêt annoncé.
### Migration automatique
Aucune.

## Rollback
- JAR : `scripts/rollback-verygames.sh` avec `rpgquest-20261022T172828Z-predeploy.jar` (SHA-256
  `11014ac2…`, JAR précédent) puis redémarrage.
- Portail : `scripts/web-portal/rollback.sh` (ancienne app sous `releases/20261022-192746`).
- Canal seul : voir « Reset ».

## Logs / diagnostic
URLs réellement vérifiées (`https://lodyquests.lodylands.com`, passage nginx + TLS) :
- avant : `/health` → `snapshot MISSING`, `server UNKNOWN` ;
- POST `/api/snapshot` faux jeton → 401 ; bon jeton + champ `players` → 400 « champ non autorisé », rien écrit ;
- 19:30:48 premier relevé du serveur réel : `"server":"ONLINE","heartbeat":"FRESH"`, `lastUpdate` qui
  avance de 30 s ; `/` → « ● En ligne » ; `/status` → « Serveur en ligne — 0 / 999 joueurs connectés » ;
- 19:31:49 arrêt RCON : `"server":"OFFLINE"` (journal portail « arrêt annoncé par le serveur ») ;
  19:32:23 : `ONLINE` (« nouveau démarrage reçu ») ;
- 19:34–19:36 jeton retiré côté portail : `"server":"STALE","heartbeat":"STALE"`, accueil « non
  actualisées depuis le 22 octobre 2026 à 19h33 », jamais OFFLINE ; jeton remis : `ONLINE` à 19:36:26 ;
- `server-status.json` sur AWS ne contient que `bootId`, `sequence`, dates, `online`, `playerCount`,
  `maxPlayers` ; aucun `snapshot.json` créé. Tableau de diagnostic : `docs/deployment/WEB_PORTAL_AWS.md`.
- Non vérifiable d'ici : le `latest.log` du serveur VeryGames (hors FTP) — l'avertissement unique
  pendant le test STALE puis « rétabli » n'a pas été lu.

## Documentation mise à jour
`WEB_API.md` (relevé d'état, `/health`, tableau des états), `deployment/WEB_PORTAL_AWS.md` (mise en
service, diagnostic), `RPGQUEST_BIBLE.md`, `current_state.md`, modèle `portal-snapshot.properties`,
TC-327 C, `SERVER_CHANGELOG.md`, registre `SUIVI_VALIDATIONS_DEV.md` (ligne #421 « À tester » + décision
visible), index des rapports.

## Limitations / travail restant
- Vérification « depuis une autre machine » : faite via l'URL publique (nginx/TLS) depuis la machine AWS
  et par des envois réels venus de VeryGames ; une vérification navigateur par le propriétaire reste à faire.
- **Décision propriétaire restante (#55)** : activer `web-export.enabled` pour envoyer l'instantané
  complet (classements, pseudos `/profile web public`). Proposition : garder l'export désactivé jusqu'à
  la validation #55, la suite (#734, #735, #56) peut avancer sur fixtures étiquetées.
- Le `bootId` d'un redémarrage dont l'horloge serait en retard de plus que le dernier relevé serait
  ignoré (`OUT_OF_ORDER`) jusqu'au rattrapage : risque négligeable (même horloge serveur).

## Prochaine étape suggérée
Propriétaire : TC-327 C dans un navigateur. Sessions : lot suivant de la mission WebPortal (#428 refonte
visuelle ou #734 catalogue de classements sur fixtures), #421 laissé ouvert.
