# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-19
* Heure : 11:10
* Sujet : #560 — [#331-W1] Mondes : page /worlds en lecture seule (adaptateur Multiverse, éléments liés, mondes essentiels)
* Statut : WAITING_OWNER (livré, déployé et vérifié techniquement sur DEV ; TC-362 PENDING MANUAL VALIDATION)
* Branche Git : `feature/560-mondes-lecture` → fusionnée dans `chore/257-ci-github-actions` (PR #577, merge `294514e`)
* Commit actuel si disponible : `294514e`
* Début de la tâche : 2026-10-19 10:27:58
* Fin de la tâche : 2026-10-19 11:10:25
* Durée totale : 00:42:27

## Demande
Session autonome `lqorch` sur #560, autorisé par le propriétaire le 19/10 (registre, `e52e0eb` sur `main` :
Q1–Q7 de `docs/design/331-mondes-portails/ETUDE.md` approuvées). Page `/worlds` **en lecture seule** :
mondes, chargé, dimension, génération, gestionnaire (Multiverse / RPGQuest), rôle Hub/Wild/Claims, badge
Essentiel, éléments liés (portails, waypoints, bâtiments…), erreurs ; desktop + mobile. Strictement aucune
création, suppression, modification, import, chargement ou déchargement ; aucun bouton mutatif ; ne pas
créer `lody_test_flat` ; ne toucher à aucun monde, portail, bâtiment ni donnée joueur. Ne pas enchaîner #561+.

## Analyse
- État réel au départ : aucune branche/PR #560 ; intégration `7514ced` ; JAR DEV `e4f7432` (#542).
- Existant réutilisé : `WorldService` (`worlds.yml`, `wild`), `WorldPortalRegistry` (`hub_to_wild`,
  `hub_to_claims`), `BuildingSiteService`, `WaypointService` + politique `travel.waypoint` (#137/#149),
  `SpecialMobRegistry` (`allowedWorlds`), `NpcSpotService`, `CityRegistry`, config `hub.world` /
  `travel.wild-world` / `claims.world`. Modèle d'isolation de `WorldEditSchematicGateway`.
- API Multiverse-Core 5.7.3 vérifiée (`javap` du JAR officiel `repo.onarandombox.com`) :
  `MultiverseCoreApi.get().getWorldManager().getWorlds()` + getters `MultiverseWorld`.

## Fichiers modifiés
- Plugin : `world/WorldOverview.java` (nouveau, assemblage pur), `world/multiverse/MultiverseWorldSource.java`
  et `MultiverseApiReader.java` (nouveaux), `world/WorldService.java` (`managedNames()`),
  `web/agent/AgentActionType.java` (`WORLD_LIST`), `AgentActions.java`, `AgentActionExecutor.java`,
  `WorldListDetails.java` (nouveau), `BukkitAgentActions.java` (`setWorldSources`, `worldList`),
  `bootstrap/RPGQuestBootstrap.java`, `build.gradle.kts` (dépôt + `compileOnly` Multiverse), `plugin.yml`
  (`softdepend: Multiverse-Core`).
- PlugAdmin : `web/WorldPages.java` (nouveau), `AgentPages.java` (`worlds`, `listControls`/`filterBtn`
  rendus package-private), `PanelApp.java` (route), `Layout.java` (groupe « Monde »), `authz/Permission.java`
  (`WORLD_READ`), `authz/Role.java`, `agent/AgentActionCatalog.java` (`world.list`), `DiagnosticHelp.java`
  (7 codes `WORLD_*`), `assets/plugadmin.css`, `docs/mondes.md` + `_index.txt`.
- Tests : `WorldOverviewTest`, `WorldListActionTest`, `WorldsPageTest` (nouveaux).

## Implémentation
- `world.list` : collecte sur le **thread principal** (mondes Bukkit, Multiverse), sources RPGQuest lues
  dans leurs caches mémoire (aucun SQL ni disque). Gestionnaire : monde principal et ses `_nether`/`_the_end`
  = serveur ; listé dans `plugins/RPGQuest/worlds.yml` = RPGQuest ; connu de Multiverse = Multiverse.
  Essentiel = monde principal ou monde à rôle. Aucun rôle par défaut (Q7). Problèmes : monde à rôle non
  chargé, monde RPGQuest non rechargé, monde déclaré deux fois, portail actif depuis/vers un monde non chargé,
  monde Multiverse auto-load non chargé, Multiverse non lu. Graine transmise en texte (précision JSON).
- Multiverse : seule `MultiverseApiReader` importe l'API (getters uniquement) ; `MultiverseWorldSource` vérifie
  présence/activation et transforme `LinkageError`/erreur en indisponibilité motivée → repli Bukkit.
- Page : cartes filtrables (Tous, Chargés, Avec rôle, Essentiels, Avec problème) + fiche (Fixé à la création,
  Réglages actuels, Éléments liés avec liens vers Emplacements/Voyages/Mobs/PNJ/Villes, Rôle, État + aides
  `DiagnosticHelp`). Seul formulaire : « Rafraîchir les mondes ». Un monde absent du relevé (ex.
  `lody_test_flat`) affiche « n'apparaît pas dans le dernier relevé ».

## Tests automatiques
- Local ciblé (`RPGQUEST_TEST_MAX_HEAP=768m`) : plugin `world.*` + `WorldListActionTest` +
  `AgentActionExecutorTest` (17 tests, 0 échec) ; panel `WorldsPageTest` (5), `DiagnosticHelpTest` (7),
  `RolePermissionMatrixTest` (16), `TravelCatalogTest` (17), Docs, Merchants, Quests… : 0 échec.
- `WorldsPageTest` vérifie qu'aucune action `world.*` autre que `world.list` n'apparaît dans la page ni la fiche
  et qu'un `world.create` posté est refusé sans mise en file.
- CI GitHub PR #577 (deux runs) : Scripts, Tests plugin, Tests control-panel, Tests web-api, Artefacts — verte ;
  CI du commit fusionné attendue par `deploy-verygames.sh --wait-ci`.
- Rendu local réel (panel 8097 + Playwright Chromium) 1280 et 390 px : liste, fiches `world_hub`, `wild`, monde
  non chargé fictif, monde absent — aucun débordement horizontal. Captures : `assets/560-mondes/`.

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — **TC-362** (`docs/MANUAL_TEST_PLAN.md`). Aucun test en jeu n'a été effectué par Claude.

## Résultat attendu
PlugAdmin → Monde → Mondes : 6 cartes ; `wild` « géré par RPGQuest (worlds.yml) », `world_hub` Hub/Multiverse
(départ des 2 portails), `claims` Claims/Multiverse, `world` monde principal Essentiel, Nether/End sans rôle.

## Reset / retour à l'état initial
Rien à réinitialiser : aucune donnée créée ou modifiée (une ligne d'action `world.list` en lecture seule,
`created_by = claude-560`, dans la base du panel).

## Déploiement VeryGames
**Effectué le 19/10/2026 à 11:08** depuis le worktree propre détaché sur `294514e` :
`RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --wait-ci` → « JAR en ligne : 3709840 octets (==
local) », « Déploiement terminé » ; sauvegarde `rpgquest-20261019T090807Z-predeploy.jar` (3 675 485 o, version
`e4f7432`). 0 joueur connecté → un `scripts/verygames-restart.sh --timeout 300` → ONLINE. JAR distant
re-téléchargé : SHA-256 `66ff7d9d23dbcedf32745bc493117c7a70aecaad682d706b16152c2b12f1a487` = local.
PlugAdmin : `scripts/plugadmin/deploy.sh` (sans sudo) — `/health` KO à 2 s (course connue), puis ONLINE ;
JAR servi contient `WorldPages` et `docs/mondes.md` ; `GET /worlds` → 303 vers `/login` (route enregistrée).
Vérification réelle : action `world.list` sur DEV → SUCCESS « 6 monde(s), 6 chargé(s) — Multiverse-Core
5.7.3 » ; `wild` RPGQUEST/WILD (arrivée `hub_to_wild`, 168 waypoints, 3 profils de mob) ; `world_hub`
MULTIVERSE/HUB (départ des 2 portails, 8 emplacements, 27 waypoints, 3 emplacements PNJ, 3 villes) ;
`claims` MULTIVERSE/CLAIMS ; `world`, `world_nether`, `world_the_end` SERVER ; aucun problème.

### À transférer
JAR RPGQuest (`294514e`) + PlugAdmin (même commit).

### Ne PAS transférer/altérer
`data.db`, `config.yml`, `worlds.yml` (RPGQuest et Multiverse), `world-portals/`, mondes, bâtiments, autres plugins.

### Redémarrage requis
Oui pour le JAR (fait). PlugAdmin redémarré par son script.

### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh` (JAR `e4f7432` sauvegardé ci-dessus) + redémarrage ; PlugAdmin :
`scripts/plugadmin/rollback.sh app`. Aucune donnée à reprendre.

## Logs / diagnostic
- Journaux locaux : `/srv/rpgquest/560-deploy.log`, `/srv/rpgquest/560-panel-deploy.log`.
- Multiverse absent/incompatible : bandeau « Multiverse-Core non lu (motif) » dans la page.

## Documentation mise à jour
`docs/RPGQUEST_BIBLE.md` §7, `docs-site/worlds.html`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md`
(TC-362), `docs/deployment/SERVER_CHANGELOG.md`, fiche panel `docs/mondes.md`, registre
`docs/SUIVI_VALIDATIONS_DEV.md` (ligne 6d-bis ajoutée, ligne 6d et décision 11 mises à jour ; rien de `main`
à reporter : la section « Autorisations propriétaire — 19/10/2026 » y était déjà).

## Limitations / travail restant
- Portails de zone (`/rpgadmin portal`, aucun sur DEV) non listés dans les éléments liés : relève de #564.
- Sans Multiverse, les mondes non chargés ne sont pas listés (pas de scan des dossiers : prévu avec #563).
- Les PNJ Citizens hors emplacements PNJ ne sont pas comptés (seuls les emplacements #381).
- `Monde déclaré deux fois` est seulement signalé (aucun cas sur DEV).
- #561–#563, #565–#570 non commencés (accord requis) ; #564 est le suivant autorisé.
