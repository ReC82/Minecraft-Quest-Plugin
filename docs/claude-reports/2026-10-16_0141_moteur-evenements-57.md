# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-16
* Heure : 01:41
* Sujet : Issue #57 — moteur générique d'événements et de classements temporaires
* Statut : DONE (validation en jeu et navigateur : PENDING MANUAL VALIDATION, ticket laissé ouvert)
* Branche Git : `feature/57-moteur-evenements` (PR #446, fusionnée → `d748df1`) ; correctif CI `fix/57-statistiques-arret-base` (PR #447, fusionnée → `379efee`) ; rapport sur `docs/57-rapport-moteur-evenements`
* Commit actuel si disponible : `379efee`
* Début de la tâche : 2026-10-15 23:24:13
* Fin de la tâche : 2026-10-16 01:41:44
* Durée totale : 02:17:31

## Demande
Session autonome lqorch, ticket #57 : un moteur d'événements générique au-dessus du modèle #54
(métrique, filtre de cible, règle de classement, éligibilité, cycle de vie explicite et audité,
événements simultanés, résultats figés, récompenses déclaratives sans injection en jeu), sans
listener par concours.

## Analyse
- #54 fournissait déjà les périodes (saisons/événements) lues par fenêtre dans le journal
  `xp_grants` et figées à leur fin. Il manquait : des métriques filtrables (« tuer des zombies »),
  des règles de classement, l'objectif commun, le brouillon/l'annulation et l'audit.
- Choix : **un journal d'activité générique** (`stat_activity_log`), même principe que
  `xp_grants` (journal idempotent lu par fenêtre, aucun compteur), alimenté par **un seul**
  listener. Les règles restent dans `statistics.yml` (extension du format #54, rétrocompatible).
- Admin/debug exclus **à l'écriture**, côté plugin : créatif/spectateur, bypass admin #35, mobs de
  test, de spawner, issus de division, victimes joueurs.
- Back-office PlugAdmin, inscription préalable et compte lié : hors de cette tranche (documentés).

## Travail effectué
- `stats.ActivityListener` + `database.ActivityLogRepository` : `ENTITY_KILL` (cible = type
  d'entité, variante = mob spécial RPGQuest) et `FISH_CAUGHT` (cible = matériau), `event_key`
  unique, `MONITOR`, écriture asynchrone.
- `StatMetric` : métriques d'activité avec filtres autorisés (`entity`, `special-mob`, `item`) ;
  descripteur exporté `filters`.
- `EventRules`, `EventRanking` (`HIGHEST_TOTAL`, `BEST_SINGLE`, `COMMUNITY_GOAL`, `LOWEST_TOTAL`
  réservé aux futurs chronos), `EventReward` (rang / seuil / commun, clé `entitlement` jamais
  publiée), `EventLifecycle` (`DRAFT`, `SCHEDULED`, `ACTIVE`, `ENDED`, `ARCHIVED`, `CANCELLED`).
- `StatPeriod` : `state` (`planned`/`draft`/`cancelled`) et `rules` ; `StatisticsDefinitionLoader`
  valide tout (filtre applicable à toutes les métriques, `goal` positif, une métrique pour un objectif
  commun, récompenses complètes, clés réservées aux événements) — fichier refusé en entier sinon.
- `StatisticsRepository` : fenêtres sur l'un ou l'autre journal, filtres en paramètres (colonnes
  venues du catalogue), agrégation et sens selon la règle, total communautaire live et figé,
  `recordTransition` / `auditTrail`, audit `ARCHIVED` dans la transaction d'archivage.
- `StatisticsService` : brouillon et annulé jamais archivés, transitions notées une fois ; tick
  ignoré pendant l'arrêt de la base (PR #447, cause de l'instabilité CI).
- `WebSnapshotWriter` : brouillons non exportés, annulés sans classement, `ranking`, `filter`,
  `community`, `rewards` (libellés).
- Portail `web-api` : liste blanche étendue, `/classements?periode=` affiche statut « Annulé »,
  compte à rebours (calculé serveur, CSP sans JS), règles, cibles, récompenses, barre d'objectif commun.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/stats/{ActivityListener,EventLifecycle,EventRanking,EventReward,EventRules}.java`
- `src/main/java/com/lodygames/rpgquest/database/ActivityLogRepository.java`
- Tests : `stats/{ActivityListenerTest,EventDefinitionLoaderTest,EventLifecycleTest}`,
  `database/EventEngineRepositoryTest`, `web-api/.../site/EventPageTest`

## Fichiers modifiés
- `SchemaMigrator` (V40), `StatisticsRepository`, `StatMetric`, `StatPeriod`, `StatisticsDefinition`,
  `StatisticsDefinitionLoader`, `StatisticsService`, `WebSnapshotWriter`, `CombatXpListener`
  (clé spawner partagée), `RPGQuestBootstrap`
- `web-api` : `PublicPlayerData`, `LeaderboardsPageHandler`, `StatLabels`
- Tests : `StatisticsRepositoryTest`, `StatisticsServiceTest`, `PublicPortalExportTest`,
  `MySqlDialectTranslationTest` (colonnes V40 classées)
- Docs : `docs/WEB_PORTAL.md` (§6 réécrit, §4 et feuille de route), `docs/RPGQUEST_BIBLE.md`,
  `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations
**V40** (automatique) : `stat_activity_log` (`event_key` unique, index `(action, created_at)`),
`stat_period_audit` (clé `period_id` + `action`). Aucune donnée existante modifiée.

## Configuration / données
Aucune clé `config.yml`. `statistics.yml` toujours **absent** sur DEV (aucun événement programmé
sans décision du propriétaire). Le journal d'activité se remplit dès le démarrage (mobs tués, pêche).

## Tests automatiques
- Local ciblé : paquets `stats`, `database`, `web`, `progression`, `ConfigFileCompleterTest` —
  604 tests, 0 échec ; `:web-api:test` vert ; `HubComfortServiceTest`/`ModCompatServiceTest` verts
  après #447.
- CI : PR #446 verte (le run « push » avait 2 échecs instables, verts au rerun) ; merge `d748df1`
  rouge sur ces 2 tests (déjà rouge sur `b04a8ea`) → cause trouvée et corrigée (#447) ; PR #447
  et merge `379efee` verts.
- Script de déploiement : suite plugin complète + build relancés en local, verts.
- Couverture du ticket : activation/fin aux bornes exactes, résultat figé après fin (rejeu tardif
  sans effet), événements simultanés, filtre d'entité et de mob spécial, retry sans double comptage,
  créatif/spectateur/bypass/spawner/victime joueur exclus, highest et meilleure action, égalités
  déterministes, brouillon et annulation, audit, agrégation communautaire (profils privés comptés,
  jamais nommés), `LOWEST_TOTAL` refusé sur un compteur.

## Tests manuels à effectuer
PENDING MANUAL VALIDATION (aucun test en jeu ni navigateur n'a été fait) :
1. Créer `plugins/RPGQuest/statistics.yml` sur DEV :
   ```yaml
   events:
     - id: test_zombies
       name: "Test zombies"
       start: "<dans 2 min, en Z>"
       end: "<dans 15 min, en Z>"
       metrics: [ENTITY_KILL]
       filter: {entity: [ZOMBIE]}
       rewards: [{rank: 1, entitlement: test_title, label: "Titre de test"}]
     - id: test_commun
       name: "Pêche commune"
       start: "<dans 2 min>"
       end: "<dans 15 min>"
       metrics: [FISH_CAUGHT]
       ranking: COMMUNITY_GOAL
       goal: 5
   ```
2. Console dans la minute : `Statistiques : 2 période(s) chargée(s)…`, puis `event « test_zombies » → SCHEDULED` et `→ ACTIVE`.
3. `/profile web public`, en survie : tuer des zombies et un squelette, pêcher ; puis en créatif et
   avec le mode bypass admin actif, tuer d'autres zombies (ne doivent pas compter).
4. Avec `web-export.enabled: true` temporairement : `snapshot.json` → `test_zombies` ne compte que
   les zombies tués en survie ; `test_commun.community.total` progresse ; après la fin,
   `… terminée, classement figé.` et statut `ARCHIVED`.
5. Passer un événement en `status: cancelled` : affiché « Annulé », sans classement.
6. Navigateur : https://lodyquests.lodylands.com/classements?periode=test_zombies (quand l'instantané
   sera acheminé, #421) — compte à rebours, règles, récompense, barre d'objectif.
7. Remettre `web-export.enabled: false`, supprimer `statistics.yml`.

## Résultat attendu
Sans `statistics.yml` : aucun changement visible ; seul le journal d'activité se remplit.

## Reset / retour à l'état initial
Supprimer `statistics.yml`. Les lignes de `stat_activity_log` / `stat_period_audit` restent, inertes.

## Déploiement VeryGames
- 01:00 : JAR `d748df1` transféré par `scripts/deploy-verygames.sh -y` (« JAR en ligne : 3109579
  octets (== local) », `DEPLOY_EXIT=0`) ; backup `rpgquest-20261015T230028Z-predeploy.jar`
  (3 085 334 o = JAR #54). `verygames-restart.sh` (0 joueur) : ONLINE, RPGQuest actif (vert).
  Migration V40 déduite du plugin actif (`latest.log` non lu).
- Le correctif #447 (`379efee`) ne change que l'arrêt d'un timer : **non redéployé sur DEV**
  (aucun effet en jeu) ; il partira avec le prochain JAR.
- Portail AWS : `scripts/web-portal/deploy.sh` sur `379efee` à 01:40 (release précédente
  `/opt/lodyquests-portal/releases/20261016-014044`), `/health` UP, `/classements` 200 en HTTPS,
  JAR servi vérifié (libellés #57 présents), ~31 Mo. nginx non touché.
### À transférer
JAR seul (fait).
### Ne PAS transférer/altérer
`data.db`, `config.yml` ; ne pas créer `statistics.yml` sans décision.
### Redémarrage requis
Oui (fait).
### Migration automatique
Oui, V40.

## Rollback
Plugin : `scripts/rollback-verygames.sh --latest` (JAR #54) ; tables V40 inertes. Retirer tout
`statistics.yml` utilisant des clés #57 (l'ancien JAR le refuserait en entier). Portail :
`scripts/web-portal/rollback.sh app`.

## Logs / diagnostic
`~/build-logs/deploy-57.log`, `~/build-logs/portal-57.log` (machine AWS). Console : préfixe
« Statistiques : », « Événements : action … non journalisée. » en cas d'échec d'écriture.

## Documentation mise à jour
`docs/WEB_PORTAL.md` (§6), `docs/RPGQUEST_BIBLE.md` (entrée `statistics.yml`, pas de page
docs-site), `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`.

## Limitations / travail restant
- Back-office PlugAdmin (brouillon, aperçu, planification, annulation, audit consultable).
- Inscription préalable, compte lié obligatoire (#53), `server_id` multi-serveurs.
- Position personnelle hors top N (identité du joueur connecté, #53).
- Blocs minés, boss, temps de jeu, métriques chrono ; purge du journal d'activité.
- Livraison des récompenses par entitlements (#422).

## Prochaine étape suggérée
#421 (acheminer l'instantané vers AWS) pour voir les événements sur le portail, puis un ticket
« back-office PlugAdmin des événements » et #422.
