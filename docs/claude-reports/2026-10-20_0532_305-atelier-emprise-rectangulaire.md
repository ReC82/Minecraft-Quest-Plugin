# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-20
* Heure : 05:32
* Sujet : #305 — Atelier bâtiments : largeur X, longueur Z et hauteur Y indépendantes (emprises rectangulaires)
* Statut : DONE (livré et déployé DEV ; validation en jeu PENDING MANUAL VALIDATION, TC-371)
* Branche Git : `feature/305-emprise-rectangulaire` → PR #617, fusionnée dans `chore/257-ci-github-actions` (merge `e5b1cc0e`)
* Commit actuel si disponible : `e5b1cc0e` (déployé) ; rapport sur `docs/305-rapport`
* Début de la tâche : 2026-10-20 04:04:52
* Fin de la tâche : 2026-10-20 05:32:33
* Durée totale : 01:27:41

## Demande
Signalement propriétaire du 13/10 : la fiche d'un projet d'Atelier n'avait qu'un champ « Largeur et
profondeur maximales », impossible de faire une estrade 13 × 9 × 7. Remplacer par trois limites
distinctes (X, Z, Y) dans création, édition, stockage, reprise, conception, prompts IA,
correction/critique, validations moteur, aperçu ; migration non destructive N → X = N, Z = N ;
diagnostic qui nomme l'axe dépassé ; aucun appel IA payant en test ; aucun plan ni bâtiment posé modifié.

## Analyse
- `BuildingProject`, `BuildingPlanDraft`, `BuildingPromptBuilder.Demand` ne portaient qu'un `maxWidth`,
  appliqué à X **et** Z (prompt : « %d blocs en x, %d blocs en z » avec deux fois `maxWidth`).
- Plugin : `PlanRepair.Limits(maxWidth, maxHeight)` faisait grandir Z jusqu'à `maxWidth`.
- **Aucun contrôle** ne refusait un plan plus grand que les limites du projet : seul l'examen d'import
  (#308) le signalait, sans bloquer. Le critère « plan dépassant Z=9 refusé » exigeait donc un refus réel.
- Placement / rotation : déjà correct pour un bâtiment rectangulaire (`BuildingFootprint`, tests 7×5 à
  90°/270° existants) — non modifié.

## Travail effectué
- **Plugin** : `PlanRepair.Limits(maxWidth, maxDepth, maxHeight)` (+ constructeur carré historique) et
  `exceeded()` par axe ; `BuildingGenerator.examine` refuse un plan dont la taille déclarée dépasse une
  limite transmise (code `PROJECT_LIMITS_EXCEEDED`, motif « size.z = 11 pour une longueur maximale (Z)
  de 9 », aperçu conservé, pas de réparation proposée) ; la réparation borne Z par `maxDepth`.
  Agent : paramètre optionnel `max_depth` sur `building.plan.audit` / `preview` ; absent → `max_width`
  (panel antérieur = carré, comportement inchangé).
- **PlugAdmin** : champ « Longueur / profondeur maximale — Z » + explication des axes (rotation à la pose
  ≠ dimensions du projet) ; `maxDepth` dans projet, brouillon, demande IA ; prompts plan / conception /
  correction / critique avec X et Z distincts ; `max_depth` envoyé aux vérifications, aperçus et
  vérifications automatiques ; validation par axe (entier 5–64, 4–64 pour Y) avec refus nommant le champ
  au lieu du recadrage silencieux précédent (`clamped()` gardé pour l'import automatique d'emplacement) ;
  examen d'import par axe ; carte « Enregistrer » : « Produit : … — limites du projet : X ≤ …, Z ≤ …,
  Y ≤ … » ; diagnostic copiable X×Z×Y ; texte clair pour `PROJECT_LIMITS_EXCEEDED`.

## Fichiers créés
- `docs/claude-reports/2026-10-20_0532_305-atelier-emprise-rectangulaire.md`

## Fichiers modifiés
- Plugin : `building/plan/PlanRepair.java`, `building/plan/BuildingGenerator.java`,
  `web/agent/AgentActions.java`, `BukkitAgentActions.java`, `AgentActionExecutor.java` ; test
  `BuildingGeneratorTest`.
- Panel : `BuildingProject`, `BuildingProjectStore`, `BuildingPlanDraft`, `BuildingPlanDraftStore`,
  `PlanImportPreview`, `PlanVerdictText`, `WorkshopAutopilot`, `ai/BuildingPromptBuilder`,
  `ai/BuildingDesignPipeline`, `agent/AgentActionCatalog`, `web/PanelApp`, `web/WorkshopPages` ; tests
  `BuildingProjectStoreTest`, `PlanImportPreviewTest`, `WorkshopFlowTest`, `BuildingDesignPipelineTest`.
- Docs (PR #617) : `RPGQUEST_BIBLE.md`, `control-panel/BATIMENTS_PARCOURS.md`, `MANUAL_TEST_PLAN.md`
  (TC-371), `deployment/SERVER_CHANGELOG.md`, `current_state.md`. Ce rapport : `SUIVI_VALIDATIONS_DEV.md`
  (ligne 7k), `claude-reports/README.md`.

## Base de données / migrations
- `control-panel.db` (AWS) uniquement : `ALTER TABLE building_project ADD COLUMN max_depth INTEGER` et
  idem `building_plan_draft`, si absentes, au démarrage. Lignes existantes laissées à NULL et relues
  X = Z = ancienne limite ; aucune ligne réécrite. Vérifié en lecture seule sur la base réelle après
  déploiement : 5 projets (dont « Estrade d’Elias » 13 / NULL / 7, version 1) et 54 brouillons, tous à NULL.
- `data.db` (plugin) : aucune migration.

## Configuration / données
Aucune configuration ni contenu modifiés. Aucun plan, bâtiment de bibliothèque ou bâtiment posé touché.

## Tests automatiques
- Locaux ciblés (`RPGQUEST_TEST_MAX_HEAP=768m`) : `BuildingGeneratorTest` 24/24 (dont nouveau refus Z
  même si X tient), `AgentActionExecutorTest` 137/137, `PlanRepair282Test`, `Stability288Test`,
  `Facades288Test` verts ; panel : `BuildingProjectStoreTest` (+3 : X/Z/Y distincts après édition et
  rechargement, base antérieure relue en carré sans réécriture, validation par axe), `PlanImportPreviewTest`
  (+1), `WorkshopFlowTest` 37/37 (+2 : formulaire → prompt IA « 13 blocs en x (largeur), 9 blocs en z
  (longueur) » → brouillon → paramètres d'audit/aperçu `max_depth=9` → redémarrage ; Z illisible refusé),
  `BuildingDesignPipelineTest`, `BuildingCorrectionLoopTest`, `BuildingPlanDraftStoreTest`… — 143 tests,
  un échec corrigé (ordre d'affichage des dimensions X × Z × Y attendu par un ancien test).
- Aucun appel IA réel : faux fournisseur local dans les tests.
- CI GitHub PR #617 : toutes vertes (plugin, control-panel, web-api, scripts, artefacts) ; CI du merge verte
  (référencée par `DEPLOYED` du panel).

## Tests manuels à effectuer
TC-371 — PENDING MANUAL VALIDATION (aucun test Minecraft ni PlugAdmin authentifié fait par Claude).

## Résultat attendu
Projet 13 × 9 × 7 conservé tel quel ; IA et vérifications reçoivent les trois limites ; plan à
`size.z` 11 refusé avec l'axe Z nommé ; anciens projets carrés inchangés.

## Reset / retour à l'état initial
Archiver le projet d'essai créé pendant TC-371. Rien d'autre à remettre.

## Déploiement VeryGames
Effectué le 20/10 vers 05:29–05:31 (heure locale).
### À transférer
JAR `rpgquest-0.1.0-SNAPSHOT.jar` de `e5b1cc0e` (3 751 703 o, SHA-256 `d1552170f9df6cbe…`), via
`scripts/deploy-verygames.sh -y` ; SHA-256 du JAR distant retéléchargé identique. PlugAdmin `e5b1cc0e` via
`scripts/plugadmin/deploy.sh` (CI verte, /health OK, classes `max_depth` présentes dans le JAR servi,
`/buildings/workshop/new` → 303 /login).
### Ne PAS transférer/altérer
`data.db`, `config.yml`, contenus, bâtiments, schematics.
### Redémarrage requis
Oui (JAR) — fait par `scripts/verygames-restart.sh` avec 0 joueur connecté ; serveur de nouveau ONLINE.
### Migration automatique
Oui, côté panel uniquement (colonnes additives, voir plus haut).

## Rollback
JAR : remettre `~/.local/share/rpgquest/verygames-backups/rpgquest-20261020T032913Z-predeploy.jar`
(3 750 395 o, SHA-256 `1ab76d30…`, version `409b3e8`) via `scripts/rollback-verygames.sh`, redémarrer.
PlugAdmin : release précédente sous `/opt/plugadmin/releases/`. Les colonnes `max_depth` sont ignorées
par l'ancien panel.

## Logs / diagnostic
`~/rpgquest-logs/deploy-305.log`, `~/rpgquest-logs/plugadmin-305.log`.

## Documentation mise à jour
Bible (Atelier : limites à trois axes ; table PlanRepair), parcours bâtiments, TC-371, changelog serveur,
current_state, registre de validation DEV (ligne 7k), index des rapports.

## Limitations / travail restant
- Pas de plafond de volume total distinct : chaque axe est borné à 64, ce qui borne le volume (64³).
- `building.generate` (publication) ne reçoit pas les limites : la publication suit la vérification de la
  version courante, qui, elle, les applique.
- Validation en jeu TC-371 à faire par le propriétaire ; ticket laissé ouvert.

## Prochaine étape suggérée
Propriétaire : TC-371 étapes 1, 2 et 4 (sans IA), puis 6 (pose rectangulaire à 90°/270°).
