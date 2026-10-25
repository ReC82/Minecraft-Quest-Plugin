# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-25
* Heure : 10:04
* Sujet : #734 — classements par type de créature tuée sur le portail bêta (priorité propriétaire du 25/10)
* Statut : DONE (validation en jeu en attente : TC-437)
* Branche Git : `feature/734-classements-par-mob` (PR #867, merge `ce59b076` dans `chore/257-ci-github-actions`) ; rapport : `docs/734-rapport-classements-par-mob`
* Commit actuel si disponible : `ce59b076309b9b4a38fd12db6ae5e88d6dd3efad`
* Début de la tâche : 2026-10-25 09:16:31
* Fin de la tâche : 2026-10-25 10:05:05
* Durée totale : 00:48:34

## Demande

Commentaire du propriétaire du 25/10 07:51Z sur #734 (session lancée par `lqorch`, file automatique #836) :
livrer un premier lot de **classements par type précis de créature tuée**, distincts du cumul « Monstres »,
sur https://lodyquests.lodylands.com ; catégories hostiles/passives/neutres si les données les
distinguent ; aucun chiffre inventé, « début de suivi » et « pas encore de données » ; boss et profils
spéciaux distincts ; réutiliser les tables et l'instantané existants (#421/#54), sans SQL gameplay depuis le
portail ; vérifier s'il faut compléter l'instrumentation ; UX en français, recherche/filtre, responsive,
sans centaines de catégories vides ; tests, CI, déploiement AWS/DEV non destructif ; preuves pour au moins
2 types réellement enregistrés ; ne pas clôturer #734.

## Analyse

- **Instrumentation** : `ActivityListener#onDeath` (#57) écrit déjà dans `stat_activity_log` le type exact
  (`target` = `EntityType`) et le mob spécial (`variant`) de chaque mort comptable (survie/aventure, hors
  bypass, spawner, division, mob de test, PvP). **Rien à compléter côté source** ; seule la présentation
  manquait (4 familles hebdomadaires seulement : zombies, squelettes, araignées, creepers).
- DEV n'a pas de `statistics.yml` (vérifié par téléchargement FTP : fichier absent) : le catalogue par
  défaut du JAR s'applique, donc de nouveaux classements par défaut y apparaissent sans configuration.
- Catégorie « neutre » : Paper n'a pas d'interface pour les mobs neutres (loup, abeille, piglin…) ; les
  catégories disponibles sont celles déjà générées (#734) : boss, hostile, non hostile. Pas d'invention.

## Travail effectué

- **Plugin** — champ déclaratif `split: entity` sur un classement `ENTITY_KILL` (validé au chargement, refusé
  ailleurs) : un classement par type réellement rencontré. `StatisticsRepository#liveTopByTarget` (une
  requête groupée par type et joueur, top N par type, ex aequo, profils visibles, exclusions) et
  `#firstSeenByTarget` (« suivi depuis » par type). Mob spécial = clé `special:<id>`, catégorie `special`,
  nom public de sa définition (MiniMessage retiré), `base` = créature de base ; jamais compté dans sa
  créature de base. Noms français : section `entity-labels` de `stats/families.yml` (tous les types
  comptables de Paper 1.21.11). Classements découpés lus en direct (pas d'archive `board_*`), exclus de la
  progression personnelle liée (#53). Éditeur PlugAdmin (#735) : `split` conservé ; affiché dans
  `leaderboard.catalog.status`. Trois classements par défaut : `kills_by_type_total`, `_month`, `_week`.
- **Portail** — liste blanche `PublicPlayerData` étendue (`split`, `targets[{key,label,category,base,
  trackedSince,leaderboard}]`, aussi dans `previous`) ; nouvelle page `/classements/creatures` : onglets
  Période et Catégorie (avec effectifs), recherche sans JavaScript insensible aux accents (trouve aussi un mob
  spécial par sa créature de base), une carte par type ayant des joueurs, les types sans joueur sur la
  période résumés en une ligne, période précédente repliable, encadré « Ce qui est compté ». Liens depuis
  `/classements` et `/classements/tous` (qui n'affiche plus ces classements en carte vide). Aperçu fictif
  étiqueté `/demo/classements/creatures` (joueurs Demo_*, mob spécial inventé « Roi squelette (démo) »).

## Fichiers créés

- `web-api/src/main/java/com/lodygames/rpgquest/webapi/site/CreatureBoardsPage.java`
- `web-api/src/test/java/com/lodygames/rpgquest/webapi/site/CreatureBoardsPageTest.java`
- ce rapport

## Fichiers modifiés

- Plugin : `stats/StatBoard.java`, `StatDimensions.java`, `StatisticsDefinitionLoader.java`,
  `StatisticsService.java`, `LeaderboardCatalogEditor.java`, `LeaderboardAdminView.java`,
  `database/StatisticsRepository.java`, `web/BoardCatalogExport.java`, `web/WebSnapshotWriter.java`,
  `web/link/LinkedProgressBuilder.java`, `bootstrap/RPGQuestBootstrap.java`,
  `resources/stats/families.yml`, `resources/stats/leaderboards.yml` ; tests `BoardCatalogExportTest`,
  `StatDimensionsTest`, `LinkedProgressBuilderTest`.
- Portail : `PublicPlayerData.java`, `BoardCatalogPage.java`, `DemoData.java`, `LeaderboardsPageHandler.java`,
  `StatLabels.java`.
- Docs : `docs/WEB_PORTAL.md` (§9), `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
  `docs/MANUAL_TEST_PLAN.md` (TC-437), `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 15f-bis + décision),
  `docs/deployment/SERVER_CHANGELOG.md`.

## Base de données / migrations

Aucune. Lecture seule de `stat_activity_log` et `player_profiles`.

## Configuration / données

Aucune. `web-export.enabled`, `player-data-public`, accès bêta nginx et données joueurs non modifiés.

## Tests automatiques

- Local ciblé plugin : `BoardCatalogExportTest` (+2 : découpage par type, `split` refusé hors
  `ENTITY_KILL`), `StatDimensionsTest` (+1 : nom français pour chaque créature comptable), plus
  `BoardCatalogLoaderTest`, `LeaderboardCatalogEditorTest`, `Leaderboard*`, `StatisticsService*`,
  `BoardPeriodTest`, `LinkedProgressBuilderTest`, `BoardSourcesRepositoryTest`, `WebSnapshotWriter*` : 0 échec.
- `:web-api:test` : 184 tests, 0 échec (`CreatureBoardsPageTest`, 6 nouveaux).
- CI GitHub PR #867 et CI du commit de fusion `ce59b076` : vertes (plugin, control-panel, web-api, scripts, artefacts).

## Tests manuels à effectuer

TC-437 (PENDING MANUAL VALIDATION) : page réelle ordinateur + téléphone, recherche « araignee », tuer 2 zombies
et 1 squelette en survie → +2/+1 dans « Cette semaine » au relevé suivant ; mob spécial séparé de sa base ;
nouvelle créature → nouvelle carte avec la date du jour ; démo. **Aucun test en jeu n'a été fait.**

## Résultat attendu

Preuves relevées sur l'instantané réel (10:02, sans pseudo reproduit ici) : 3 classements `split` actifs,
14 types suivis — Zombie (depuis le 16/10, 2 joueurs, 70 au total), Araignée (16/10, 46), Squelette (16/10,
29), Creeper (20/10, 11), Noyé, Vagabond, Villageois zombie, Vache, Cochon, Golem de fer, Mouton,
Chauve-souris, et 2 mobs spéciaux (Zombie Fissible, Zombie Runner) séparés des zombies. Semaine en cours :
Vagabond affiché « déjà vaincu, personne sur cette période ». Aucun boss vanilla encore tué (onglet absent).

## Reset / retour à l'état initial

Retirer les entrées `split` du catalogue (ou redéployer le JAR précédent) : aucune donnée créée.

## Déploiement VeryGames

- JAR : `scripts/deploy-verygames.sh -y` après CI de fusion verte ; `rpgquest-0.1.0-SNAPSHOT.jar` 4 428 693 o,
  SHA-256 `458380e7e3e494d5ae13498f6a6c806c758e362939e62a914bf1b56310e53e8b`, **retéléchargé identique** ;
  backup `rpgquest-20261025T090103Z-predeploy.jar` (= JAR #829, SHA-256 `f55dec92…`). DEV redémarré par
  `scripts/verygames-restart.sh` (seul le propriétaire était connecté avant ; 0 joueur au retour), ONLINE.
- Portail AWS : `scripts/web-portal/deploy.sh` → `ce59b07`, `/health` UP, snapshot FRESH, server ONLINE.
- PlugAdmin : non redéployé (aucun changement `control-panel/`).
- URLs vérifiées (200, accès bêta) : `/classements/creatures`, `?periode=semaine`, `?q=araignee` (carte
  Araignée seule), `?type=special` (2 mobs spéciaux), `/classements/tous` et `/classements` (lien présent) ;
  `/demo/classements/creatures` 200. Aucun UUID dans la page. Playwright 1280 et 390 px : 0 débordement.

## Rollback

`scripts/rollback-verygames.sh` vers `rpgquest-20261025T090103Z-predeploy.jar` + redémarrage ;
`scripts/web-portal/rollback.sh` pour le portail. Aucune donnée à nettoyer.

## Logs / diagnostic

Journaux de déploiement : `~/deploy-logs/734-ce59b076.log`, `~/deploy-logs/734-portal-ce59b076.log`.

## Documentation mise à jour

WEB_PORTAL §9 (format `split`, export, page), bible (`statistics.yml`), current_state, TC-437, registre,
SERVER_CHANGELOG.

## Limitations / travail restant

- Pas de catégorie « neutres » (non exposée par Paper) : décision propriétaire (famille `neutral` à la main ?).
- Pas de case `split` dans le formulaire PlugAdmin #735 (champ conservé si on édite le classement).
- Classements découpés non archivés : seule la fenêtre précédente est montrée (comme les autres).
- `/classements/creatures` absent du plan du site (portail en bêta fermée).
- Les autres mesures de #734 (temps de jeu, distance, crafts…) restent « pas encore suivies » ; #734 reste ouvert.

## Prochaine étape suggérée

Recette TC-437 par le propriétaire, puis décision « neutres » ; ensuite éventuel `split: block` pour les blocs.
