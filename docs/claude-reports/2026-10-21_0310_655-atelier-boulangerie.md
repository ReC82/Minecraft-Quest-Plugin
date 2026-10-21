# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 03:00
* Sujet : #655 — P0 Atelier IA « Boulangerie de Central » : échecs répétés et corrections payantes stériles
* Statut : PARTIAL (livré et déployé ; PENDING MANUAL VALIDATION propriétaire, DEV arrêté)
* Branche Git : `fix/655-atelier-boucle-boulangerie` (PR #678, fusionnée dans `chore/257-ci-github-actions`, merge `03c66a0e`) ; rapport : `docs/655-rapport-atelier`
* Commit actuel si disponible : `03c66a0e`
* Début de la tâche : 2026-10-21 01:30:14
* Fin de la tâche : 2026-10-21 03:00:41
* Durée totale : 01:30:27

## Demande
Ticket #655 (P0, propriétaire 20/10) : auditer le contrat DSL / prompt / validateur de l'Atelier, corriger
de façon déterministe ce qui est sûr sans facturer l'IA, empêcher l'oscillation (conserver le meilleur
candidat), borner le mode automatique, actualiser l'UI jusqu'à l'état terminal, tests de non-régression
sur les actions réelles `d852629f`, `d54a87b1`, `73affe8a`, `14b2493d` (mocks, aucun appel payant),
scénario de référence d'un petit bâtiment habitable avec entrée traversable. Interdits : génération IA
payante, toute écriture sur le projet réel `96a24bba`, pose/retrait de blocs, monde/PNJ/joueurs.

## Analyse
Lecture seule de `/var/lib/plugadmin/control-panel.db` (actions, versions, journal de conception) :

1. **Contradiction d'orientation, cause racine.** Description : « Façade principale et entrée à
   l'OUEST » ; sélecteur du projet : `EAST`. L'IA perçait l'ouest (`clear` en x=0..1), le serveur
   cherchait l'entrée sur la façade EST (x=9) → `NO_ENTRANCE` / `ENTRANCE_STEP` en boucle. Aucune
   correction ne pouvait réussir.
2. **Session « Exigeant » `6a91d328` (38 passes, 12 appels, 104 552 / 11 188 jetons).** Elle a
   **obtenu un plan conforme** : version `35ce1e1a` (réparation moteur, passe 15, audit `a2f4b56d`
   SUCCESS, 338 blocs). La critique suivante l'a cassé (`PLAN_INVALID`, clé `block` absente) ; le
   pipeline a alors dépensé **les 10 appels restants** à corriger la critique (NO_ENTRANCE,
   ENTRANCE_STEP, UNSUPPORTED_BLOCK, PLAN_INVALID en alternance). Le statut final était
   « SUCCEEDED, version retenue 35ce1e1a », mais la page rouvrait sur la dernière version
   (`f8540b38`, refusée) : « Un point est à corriger ». L'anti-répétition ne jouait que sans plan
   conforme et que sur deux refus *consécutifs* aux mêmes codes.
3. **Fautes sûres non réparées par le moteur** :
   - `d852629f` : `facade: EAST` (et `SOUTH`/`NORTH`) dans des instructions — la lecture
     échouait avant toute réparation. La correction payante de l'IA avait traduit SOUTH → RIGHT,
     **faux** (pour `front: EAST`, SOUTH = LEFT).
   - `d54a87b1` / `73affe8a` (+ `b8237295`, `6aeec0e8`) : toit `gable` **entièrement** en y=10
     (murs jusqu'à y=9, volume haut de 10) : non « ramenable » par découpe (zone hors volume),
     4 corrections payantes stériles (l'IA réduisait x au lieu de y) ; `minecraft:brick` (objet).
   - Derrière ces fautes : fenêtre FRONT débordant du mur (z=8..11) et marches d'entrée
     plaquées contre le four, sans dénivelé. Comme le plan restait `PLAN_INVALID`, la réparation
     partielle était **jetée** en entier.
   - `739ea04b` : ouverture sans porte, plancher à y=0, ancre y=0 → « entrée à sauter » ; la
     réparation ne traitait que les blocs de porte. Message avec une clause vide « ; . ».
   - `14b2493d` (preview de `f8540b38`) : aucune porte sur la façade EST.

## Travail effectué
**Plugin (`building.plan`)**
- `BuildingPlanYaml.readForRepair` : une façade d'instruction en point cardinal est traduite
  *exactement* depuis `front` (`Facade.direction`) avec une note par instruction ; la lecture stricte
  continue de la refuser ; le `front` du bâtiment n'est jamais modifié.
- `PlanRepair` : `materials` (identifiant inconnu → pluriel s'il est un bloc du registre 1.21.11,
  `brick` → `bricks`) ; toit `gable` entièrement au-dessus du volume abaissé (≤ 3 blocs, emprise x/z
  inchangée) ; `needlessEntrySteps` (marches d'entrée refusées dont `floor` ≤ ancre y) ; `doorway`
  (`NO_ENTRANCE` sans porte avant : `op: door` sur FRONT, au premier plancher, depuis le milieu de la
  façade, gardée seulement si l'audit complet ne trouve plus `NO_ENTRANCE` ni nouveau refus ; jamais si
  une porte avant existe) ; `sunkFloor` (entrée sans porte à sauter : ancre +1/+2, rejouée par
  l'audit) ; `progressWhileInvalid` : un plan encore invalide garde les corrections sûres si la
  première faute a changé ou si la lecture stricte devient possible.
- `ArchitecturalAudit` : message `ENTRANCE_STEP` sans clause vide.
- `BuildingGenerator.examine` branché sur `readForRepair`.

**Panel**
- `BriefOrientation` : détecte une tournure explicite (« entrée à l'ouest », « entrée principale au
  nord », « façade d'entrée à l'est », « entrée côté sud ») ; plusieurs côtés ou aucun → jamais de
  conflit. `PanelApp` refuse à coût nul les gestes payants (`propose`, `correct`, `design`, `restart`,
  `design_write`) tant que description et sélecteur se contredisent ; bandeau explicatif et formulaire
  « Le bâtiment souhaité » ouvert. Rien n'est réécrit à la place de l'administrateur.
- `BuildingDesignPipeline` : une critique ratée a droit à **une** correction, puis retour à la version
  conforme (passe « NOTE » explicite) et critique suivante ou fin ; diagnostic déjà reçu (codes + texte,
  SHA-256) → conception neuve une fois, puis arrêt « l'IA tourne en rond ».
- `BuildingPromptBuilder.frame` : repère propre au bâtiment dans chaque demande (`FRONT = façade EST ·
  BACK = OUEST · LEFT = SUD · RIGHT = NORD`), « y ≤ size.y − 1, toit compris », `minecraft:bricks`.
- `WorkshopPages.defaultDraft` : sans version dans l'URL, la page ouvre la version **retenue** par la
  dernière conception terminée (si aucune version n'a été créée après). Pour la Boulangerie, c'est
  `35ce1e1a` (conforme) au lieu de `f8540b38`.
- « Vérification en cours… » porte `data-pa-track-action` + `data-pa-track-reload="terminal"` (mécanisme
  #214) : rechargement automatique au résultat, réussi ou non ; « Rafraîchir » reste en secours.

**Ce qui n'a pas été fait** : simplification des boutons (#306, hors périmètre, à coordonner) ;
réconciliation automatique de l'emprise 12×11 vs champs 12×12 (seule l'orientation est contrôlée) ;
fixture complète de la Boulangerie « après export contrôlé » (les 6 plans réels sont copiés tels quels,
lus en lecture seule) ; décompte du coût réel provider (les jetons étaient déjà comptés par passe).

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/BriefOrientation.java`
- `src/test/java/com/lodygames/rpgquest/building/plan/Boulangerie655Test.java`
- `src/test/resources/building/plans/boulangerie_655_{d852629f,d54a87b1,73affe8a,14b2493d,739ea04b,35ce1e1a_conforme}.yml`
- `control-panel/src/test/java/.../panel/building/BriefOrientationTest.java`
- `control-panel/src/test/java/.../panel/ai/BuildingPromptFrame655Test.java`
- `control-panel/src/test/java/.../panel/web/WorkshopDefaultDraft655Test.java`
- ce rapport

## Fichiers modifiés
- Plugin : `BuildingPlanYaml.java`, `PlanRepair.java`, `BuildingGenerator.java`, `ArchitecturalAudit.java`
- Panel : `BuildingDesignPipeline.java`, `BuildingPromptBuilder.java`, `PanelApp.java`, `WorkshopPages.java`
- Tests : `BuildingDesignPipelineTest.java` (+2), `WorkshopFlowTest.java` (+2)
- Docs : `docs/RPGQUEST_BIBLE.md` (« Boucle bornée, réparations sûres et orientation vérifiée »),
  `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-383), `docs/SUIVI_VALIDATIONS_DEV.md`,
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`.
  Pas de page `docs-site/` existante pour l'Atelier : rien à y reporter.

## Base de données / migrations
Aucune migration (ni `data.db`, ni `control-panel.db`). Aucune écriture dans la base du panel : uniquement
des `SELECT`.

## Configuration / données
Aucune. Projet `96a24bba`, ses versions, sa conception, son journal et ses images : **non modifiés**.

## Tests automatiques
- Plugin, ciblé : `com.lodygames.rpgquest.building.*` — 454 tests, 0 échec (dont `Boulangerie655Test`, 11).
  `14b2493d` et `739ea04b` deviennent **conformes** après réparation (petit bâtiment habitable, entrée
  traversable, audit complet) ; `d852629f`, `d54a87b1`, `73affe8a` passent de `PLAN_INVALID` à un seul
  refus restant (`NO_LIGHT`), transmis à l'IA ; `35ce1e1a` reste conforme.
- Panel, ciblé : `BriefOrientationTest` 3, `WorkshopDefaultDraft655Test` 2, `BuildingPromptFrame655Test` 2,
  `BuildingDesignPipelineTest` 18 (forme de la session réelle : 5 appels au lieu de 12 ; diagnostic
  répété → arrêt), `BuildingCorrectionLoopTest` 16, `Estrade472PromptTest` 2, `WorkshopFlowTest` 39
  (gestes payants suspendus avec 0 appel IA ; suivi terminal de la vérification). 0 échec.
- CI GitHub PR #678 : Tests plugin, Tests control-panel, Scripts, Artefacts — verts ; CI du merge
  `03c66a0e` : success.
- Aucun appel IA payant (serveur IA factice dans les tests).

## Tests manuels à effectuer
TC-383 (`docs/MANUAL_TEST_PLAN.md`) — PENDING MANUAL VALIDATION. Aucun test Minecraft ni Atelier réel
n'a été fait par Claude.

## Résultat attendu
Page « Boulangerie de Central » ouverte sur la version conforme `35ce1e1a` ; bandeau de contradiction
OUEST/EST et gestes payants suspendus jusqu'à ce que le propriétaire tranche ; vérifications suivies
sans clic ; une future conception automatique ne poursuit plus une critique ratée et laisse le moteur
corriger gratuitement façades cardinales, `brick`, toit à y=max, marches inutiles, entrée à sauter et
entrée absente.

## Reset / retour à l'état initial
Revenir aux artefacts précédents : JAR `2b4a2089` (sauvegarde `rpgquest-20261021T005844Z-predeploy.jar` sur le serveur) et PlugAdmin
précédent (`/opt/plugadmin/releases/<horodatage>/`). Aucune donnée à restaurer.

## Déploiement VeryGames
| Élément | Valeur |
|---|---|
| À transférer | JAR RPGQuest `03c66a0e` — **fait** (`scripts/deploy-verygames.sh -y`), « JAR en ligne : 3917463 octets (== local) », SHA-256 `186f064d…` retéléchargé identique |
| Backup | `rpgquest-20261021T005844Z-predeploy.jar` (JAR `2b4a2089`, #81) dans `~/.local/share/rpgquest/verygames-backups/` |
| PlugAdmin | `scripts/plugadmin/deploy.sh` — `DEPLOYED` commit `03c66a0e`, validation « CI verte sur 03c66a0 » ; le script a affiché `/health KO` (course connue), revérifié : `{"panel":"ONLINE"}`, `BriefOrientation` dans le JAR servi, route projet 303 → login |
| Ne PAS altérer | `data.db`, configs, contenus, mondes ; projet Atelier `96a24bba` et ses versions |
| Redémarrage requis | Oui pour le JAR. **DEV est arrêté depuis l'arrêt RCON de 01:03 (#81)** : RCON refusé, dernier heartbeat 23:02:41Z. Aucun redémarrage tenté (consigne anti-boucle VeryGames) — **à démarrer depuis le panel VeryGames** ; le nouveau JAR sera chargé alors |
| Migration | Aucune |
| Rollback | `scripts/rollback-verygames.sh --latest` ; `scripts/plugadmin/rollback.sh app` |

Les changements du panel (orientation, version retenue, boucle bornée, suivi terminal) sont actifs dès
maintenant ; les réparations du moteur ne le seront qu'au démarrage de DEV.

## Registre de validation
`docs/SUIVI_VALIDATIONS_DEV.md` : ligne « 00 » #655 / TC-383 (À tester) ajoutée en tête de « Priorité immédiate » ;
décision OUEST/EST ajoutée dans « Dépendances et décisions à garder visibles ». Les ajouts du propriétaire sur `main`
(#655, #654, #135) étaient déjà présents dans la branche d'intégration.

## Points restants
- Décision propriétaire : façade d'entrée OUEST ou EST pour la Boulangerie (gestes payants suspendus d'ici là).
- Démarrer DEV (panel VeryGames) ; puis TC-383.
- Hors lot : #306 (un seul bouton principal), réconciliation emprise brief/champs, règle « un seul niveau » du brief
  vs escalier ajouté par le moteur vers un plancher intermédiaire (constaté sur d54a87b1 réparé).
