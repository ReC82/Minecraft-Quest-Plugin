# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-26
* Heure : 21:28
* Sujet : #568 — suite de la précision propriétaire du 26/10 : écart mesuré, aperçu 3D du volume de passage d'une arche
* Statut : PARTIAL (aperçu livré et déployé ; emplacements de type portail en attente d'une décision propriétaire)
* Branche Git : `feature/568-apercu-volume-passage` (PR #888, fusionnée dans `chore/257-ci-github-actions` en `72227f73`) ; rapport : `docs/568-rapport-apercu-passage`
* Commit actuel si disponible : `72227f73`
* Début de la tâche : 2026-10-26 20:43:48
* Fin de la tâche : 2026-10-26 21:29:22
* Durée totale : 00:45:34

## Demande

Session autonome lqorch sur #568. Précision propriétaire du 26/10 08:35 : l'arche est une construction de l'Atelier
(type propre, visible à part dans l'Atelier et la Bibliothèque), emplacements de type portail de préférence, passage
intérieur connu et transformé à la pose, **aperçu 3D de la zone de téléportation**, volume repris automatiquement à la
pose, jamais d'activation par la construction. Développement et simulations seulement : aucune arche ni portail réel,
aucun appel IA payant.

## Analyse

Écart mesuré avec l'existant (code de `chore/257-ci-github-actions` à `bfdce7b6`) :

| Exigence du 26/10 | État avant cette session |
|---|---|
| Arche générée par l'Atelier, type « Arche de portail » séparé, filtre Bibliothèque | Livré (#711, 21/10) |
| Passage traversable relevé et transformé par ancre + rotation | Livré (#711 : `PortalPassageFinder`, `PortalPassage.inWorld`, refus `PORTAL_PASSAGE_MISSING`) |
| Volume repris automatiquement après la pose, sans coordonnées | Livré par #565 (`zone_mode=building`, portail né lié et inactif) |
| Jamais d'activation par la construction / la liaison | Livré et testé (#711, `WorldPortalBuildingLinkTest`) |
| Aperçu 3D montrant la zone de téléportation | **Manquant** : la vue 3D ne montrait que les blocs, le plugin n'envoyait pas le passage avec l'aperçu |
| Emplacements de type portail | **Manquant** : `BuildingSite` n'a pas de type ; il faut une colonne `building_sites` dans `data.db` |

Le type d'emplacement est un changement de schéma de données : selon `CLAUDE.md`, décision à demander avant
d'implémenter. Question A/B posée sur #568.

## Travail effectué

1. **Plugin** — `BuildingGenerator.Examination` porte le passage relevé (`null` hors arche ou si introuvable), conservé
   par la réparation et par le refus « limites du projet » ; `BuildingPlanPreviewView` a un champ `passage`
   (constructeur précédent conservé) ; `building.plan.preview` ajoute `passage` (`minX..maxZ`) au détail quand il
   existe. Aucun changement de format sur disque.
2. **PlugAdmin** — nouveau `PassageVolume` (lecture validée dans la grille, largeur/profondeur selon la façade,
   phrase de description, écart avec le passage demandé au projet). `IsometricView.svg(…, passage)` dessine le volume
   après les blocs : trois arêtes cachées en pointillé léger, trois faces visibles violet translucide (`#a64dff`,
   couleur d'un portail du Nether), contour pointillé ; juste dans les 4 orientations (inverse de la rotation de vue),
   tronqué par la coupe. `WorkshopPages.previewCard` : volume dans la figure, ligne « Zone de téléportation : … Après
   la pose, le portail reprend exactement ce volume… Construire l'arche n'active jamais le portail. », avertissement
   d'écart, alerte `PORTAL_PASSAGE_MISSING` si un projet arche n'a pas de passage. CSS `.ws-passage`.
3. **Docs** — `portails.md` (aide PlugAdmin), Bible, `docs-site/worlds.html`, `current_state.md`, TC-396 étape 2.
4. **Vérification visuelle** — SVG d'une arche 7 × 6 × 3 rendu en Chromium (Playwright) aux 4 orientations : le
   volume occupe exactement l'ouverture.
5. **Déploiement DEV** (autorisé : déploiement de code non destructif, ticket du 20/10) — JAR + PlugAdmin `72227f73`.
6. **Commentaire #568** — écart point par point et question A/B sur les emplacements de type portail.

## Fichiers créés

- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/PassageVolume.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/building/PassageVolumeTest.java`
- ce rapport

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/building/plan/BuildingGenerator.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`, `BukkitAgentActions.java`, `AgentActionExecutor.java`
- `src/test/java/com/lodygames/rpgquest/building/plan/BuildingGeneratorTest.java`, `web/agent/AgentActionExecutorTest.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/IsometricView.java`, `panel/web/WorkshopPages.java`,
  `control-panel/src/main/resources/assets/plugadmin.css`, `control-panel/src/main/resources/docs/portails.md`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/WorkshopFlowTest.java`
- `docs/RPGQUEST_BIBLE.md`, `docs-site/worlds.html`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md`
- (PR de rapport) `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations

Aucune (`data.db` et `control-panel.db` inchangés). La migration de `building_sites` pour les emplacements de type
portail **n'est pas faite** : décision propriétaire attendue.

## Configuration / données

Aucune. Aucune arche, aucun portail, aucun emplacement, aucun projet d'Atelier créé ou modifié ; aucun appel IA.

## Tests automatiques

Ciblés en local (`RPGQUEST_TEST_MAX_HEAP=768m`), tous verts : `BuildingGeneratorTest` 27, `AgentActionExecutorTest` 143,
`PassageVolumeTest` 3, `IsometricViewTest` 14, `WorkshopFlowTest` 55. CI GitHub de la PR #888 et du commit de fusion
`72227f73` vertes (suite complète plugin, control-panel, web-api, scripts, artefacts).

## Tests manuels à effectuer

PENDING MANUAL VALIDATION — TC-396 :
- étape 1 en lecture seule (inchangée) ;
- étape 2 (feu vert requis, appel IA payant) : dans l'aperçu du bâtiment d'une arche, volume violet translucide à
  contour pointillé dans les 4 orientations, tronqué par « Voir l'intérieur », phrase « Zone de téléportation : … »,
  avertissement si le passage relevé diffère du passage demandé ;
- étapes 3-6 inchangées (feu vert requis).

## Résultat attendu

Un administrateur voit, avant toute pose, où sera la zone de téléportation de l'arche, sa taille et son sens de
traversée ; la pose puis « depuis l'arche posée » (#565) / « Lier l'arche posée » (#711) reprennent exactement ce
volume, sans activer le portail.

## Reset / retour à l'état initial

Rien à réinitialiser (lecture seule). Retour du code : rollback JAR + PlugAdmin ci-dessous.

## Déploiement VeryGames

- **Effectué** le 26/10 vers 21:26 : `scripts/deploy-verygames.sh -y` (« Validation : CI verte sur 72227f7 », JAR en
  ligne 4 442 989 octets == local), SHA-256 `a0ac7b6a51be4754…` re-téléchargé identique ; `scripts/verygames-restart.sh`
  → ONLINE, 0 joueur. PlugAdmin `72227f73` via `scripts/plugadmin/deploy.sh` : le `KO` de `/health` est la course connue,
  relecture `ONLINE`, `PassageVolume` présent dans le JAR servi, `/buildings/workshop` → 303.
- À transférer : le JAR seul (fait). Ne pas altérer : `data.db`, `world-portals`, `buildings/`, emplacements.
- Redémarrage requis : oui (fait). Migration : aucune.
- Sauvegarde : `rpgquest-20261026T202615Z-predeploy.jar` (= JAR `d5a1c48e`) ; release PlugAdmin précédente sous
  `/opt/plugadmin/releases/`.
- Rollback : `scripts/rollback-verygames.sh --latest` + redémarrage ; `scripts/plugadmin/rollback.sh app`. Un ancien
  plugin n'envoie pas `passage` : l'aperçu reste sans volume.

## Points d'attention

- **Décision A/B** sur les emplacements de type portail (commentaire #568) : A = migration additive `building_sites`
  + refus de poser une arche sur un emplacement « bâtiment » et inversement ; B = avertissement à la pose, sans
  migration.
- Le volume est dessiné devant les blocs volontairement (il est fait d'air) ; une arche épaisse le montre donc
  « au travers » de ses piliers avant.
