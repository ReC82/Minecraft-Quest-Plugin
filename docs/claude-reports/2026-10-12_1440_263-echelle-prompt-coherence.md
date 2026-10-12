# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-12
* Heure : 14:40 (heure locale, CEST)
* Sujet : #263 — le plan IA `bibliotheque_du_hub_v2` refusé sur une échelle `from: [1, 1]` ; cause,
  correctif, et test de cohérence entre le prompt et le moteur
* Statut : **DONE** côté code — CI verte, PR #264 fusionnée ; **non déployé** (JAR + panel,
  feu vert attendu) — correctif prioritaire de la prochaine livraison
* Branche Git : `fix/263-ladder-prompt` (PR #264 vers `chore/257-ci-github-actions`)
* Commit actuel si disponible : `ecf56f0`
* Début de la tâche : 2026-10-12 14:25:08
* Fin de la tâche : 2026-10-12 14:40:07
* Durée totale : 00:14:59

## Demande

Retour du test réel de la V2 : action d'audit `51874916-1b78-4962-a3dc-9946269b8754`, refus
`PLAN_INVALID — Instruction 16 : clé « from » absente ou mal formée (attendu : [x, y, z])` sur une
instruction `ladder` avec `facade: BACK`, `from: [1, 1]`, `to: [1, 5]`. Établir si la cause est le
prompt, la documentation du DSL ou le parseur ; corriger ; ajouter un test de cohérence entre les
exemples du prompt et les propriétés admises. Sans appel IA, sans toucher aux bâtiments, sans déployer.

## Analyse

- Le **parseur** est cohérent avec lui-même : `BuildingPlanYaml.box` exige deux triplets, et
  `BuildingPlanExpander.ladder` pose l'échelle dans la colonne de `from`, de `from.y` à `to.y` ; la
  façade ne donne que l'orientation.
- Le **prompt** décrivait `ladder` par « Clés : facade, from, to (le bas et le haut de l'échelle) »,
  sans jamais dire qu'il s'agit de points 3D. Juste au-dessus, `door` et `window` se repèrent par
  façade et décalage : le modèle a appliqué ce modèle et écrit des coordonnées de façade. **Aucun
  exemple** ne montrait `ladder` ni `stair_run`. **Cause : le prompt.**
- Défaut silencieux associé : un `to` d'échelle hors de la colonne de `from` était ignoré sans
  message.
- Le **test de cohérence** demandé, une fois écrit, a révélé deux autres défauts réels :
  1. l'« EXEMPLE COMPLET ET VALIDE » montré au modèle depuis la V2 était **refusé par le moteur**
     (hauteur déclarée 7, toit jusqu'à `y = 8`). Le test panel existant ne vérifiait que du texte.
  2. corrigé, il était **refusé par l'audit** : sur une petite emprise, chaque rangée d'escaliers d'un
     toit à deux pentes dépassait le quart de l'emprise et comptait comme un « niveau » — faux
     `LEVEL_UNREACHABLE` et `ROOF_MISSING`. Sur la bibliothèque (19 × 19), la rangée reste sous le
     seuil : c'est pourquoi rien ne l'avait montré.

## Travail effectué

- **Prompt** (`BuildingPromptBuilder`) : `[x, y, z]` explicites pour `light`, `stair_run`, `ladder`
  (trois nombres, mêmes x et z, cellule intérieure contre le mur, exemple chiffré) ; « seules door et
  window se repèrent par façade » ; bloc « UN EXEMPLE MINIMAL PAR INSTRUCTION D'ARCHITECTURE » ;
  exemple complet porté à une hauteur de 9.
- **Moteur** : échelle non verticale refusée (`BuildingPlan.validate`) ; liste de mauvaise longueur →
  message « N nombre(s) reçu(s), il en faut TROIS … « facade » ne remplace aucune coordonnée »
  (`BuildingPlanYaml`).
- **Audit** (`ArchitecturalAudit.analyseLevels`) : les niveaux sont les sols **abrités** (un bloc
  au-dessus dans la colonne). Sans aucun sol abrité, l'ancienne lecture s'applique : un bâtiment sans
  toit reste refusé (`ROOF_MISSING`).
- **Correction du plan réel**, à faire à la main dans le brouillon (#256), sans IA : plan
  `front: WEST`, 19 × 14 × 19 → BACK = mur est (`x = 18`), cellule intérieure `x = 17` ; lecture la
  plus probable : `from: [17, 1, 1]`, `to: [17, 5, 1]`.

## Fichiers créés

- `src/test/java/com/lodygames/rpgquest/building/plan/PromptPlanConsistencyTest.java`
- `src/test/java/com/lodygames/rpgquest/building/plan/AuditRoofLevelsTest.java`

## Fichiers modifiés

- `control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/BuildingPromptBuilder.java`
- `src/main/java/com/lodygames/rpgquest/building/plan/BuildingPlan.java`
- `src/main/java/com/lodygames/rpgquest/building/plan/BuildingPlanYaml.java`
- `src/main/java/com/lodygames/rpgquest/building/plan/ArchitecturalAudit.java`
- `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations

Aucune.

## Configuration / données

Aucune. Aucun bâtiment modifié, aucun appel IA.

## Tests automatiques

- `PromptPlanConsistencyTest` (6) — lit le **source réel** du prompt dans le dépôt : l'exemple complet
  est analysé **et audité** par le vrai moteur ; chaque exemple minimal est analysé par
  `BuildingPlanYaml` ; chaque instruction d'architecture a son exemple ; chaque clé annoncée
  (« Clés : … ») est une clé lue par le parseur ; le cas réel reste refusé avec un message utile ;
  une échelle oblique est refusée.
- `AuditRoofLevelsTest` (2) — pentes d'un petit toit ≠ niveaux ; bâtiment sans toit toujours refusé.
- Correctif d'audit **prouvé par neutralisation** : sans lui, 2 tests échouent.
- Rejoués en ciblé, verts : `ArchitecturalAuditTest` (14), `ArchitecturalAuditV1RegressionTest` (7),
  `BuildingGeneratorTest` (19), `BuildingPlanExpanderTest` (12), `BuildingCorrectionLoopTest` (panel).
- CI : run push **vert** sur `ecf56f0` (branche partie de la tête de la ligne d'intégration, donc résultat de fusion identique) ; PR #264 fusionnée.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — après déploiement JAR + panel : ouvrir le brouillon
`bibliotheque_du_hub_v2`, remplacer l'instruction 16 par des coordonnées 3D (ci-dessus),
**Enregistrer** puis **Auditer** : plus de `PLAN_INVALID` sur `from`. Un nouveau plan IA (appel
payant, à la discrétion du propriétaire) devrait produire des échelles en `[x, y, z]`.

## Résultat attendu

Le modèle reçoit un prompt dont chaque exemple est accepté par le moteur et audité valide ; une faute
de coordonnée est expliquée au lieu d'être simplement refusée.

## Reset / retour à l'état initial

Sans objet.

## Déploiement VeryGames

### À transférer

JAR RPGQuest (moteur + audit) **et** panel (prompt).

### Ne PAS transférer/altérer

Bâtiments posés, `data.db`.

### Redémarrage requis

Oui (JAR) ; panel par `deploy.sh`.

### Migration automatique

Aucune.

## Rollback

JAR et release du panel précédents.

## Logs / diagnostic

Message de refus de l'audit, désormais explicite pour une coordonnée à deux nombres.

## Documentation mise à jour

`SERVER_CHANGELOG.md`. Le prompt est lui-même la documentation du DSL côté modèle.

## Limitations / travail restant

- Les métriques `floors` peuvent baisser sur de petits bâtiments à toit en pente : c'est la
  correction, pas une régression.
- La position de l'échelle contre le mur n'est pas imposée (une échelle contre un mur intérieur est
  légitime) ; seule la verticalité l'est.

## Prochaine étape suggérée

Inclure #263 dans la prochaine livraison JAR + panel, puis corriger et auditer le plan V2 à la main.
