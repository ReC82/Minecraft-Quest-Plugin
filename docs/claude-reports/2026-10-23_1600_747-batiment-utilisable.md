# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-23
* Heure : 16:00
* Sujet : #747 (P0) — retour du 23/10 fin de matinée : « le générateur doit produire un bâtiment utilisable »
* Statut : WAITING_OWNER (livré et déployé sur DEV ; essai réel fait ; aucune version conforme dans l'enveloppe ; décision du propriétaire sur le modèle)
* Branche Git : `fix/747-batiment-utilisable` (PR #784), `fix/747-repere-et-wall` (PR #785), `fix/747-mobilier-dans-le-sol` (PR #787), `fix/747-eclairage-moteur` (PR #789) ; intégration `chore/257-ci-github-actions`
* Commit actuel si disponible : `7093bc80` (JAR DEV) ; PlugAdmin DEV `8bb79159`
* Début de la tâche : 2026-10-23 12:24:34
* Fin de la tâche : 2026-10-23 16:10:00
* Durée totale : 03:45:26

## Demande
Spécification du propriétaire (`specs/2026-10-23-747-batiment-utilisable.md`, commentaire sur #747) : rejouer le
plan V-2caffe63 sans appel IA et trancher le refus STAIRCASE ; vérifier les exemples du prompt contre le moteur ;
conception structurée avant le plan ; exigences vérifiées sur conception, plan et résultat compilé ; corrections
moteur sûres ; puis UN essai de bout en bout sur un nouveau projet de test, au plus 4 appels réels.

## Causes confirmées (journal relu en LECTURE SEULE sur une copie de `control-panel.db`, événements 90-91)
- **STAIRCASE de V-2caffe63** : la géométrie était bien impossible — sol et murs occupent toute la profondeur
  (z 0 à 18), la marche d'entrée tombait dans le mur de façade, sous la porte. Mais **le diagnostic était faux** :
  le message de l'escalier *intérieur* (« entièrement à l'intérieur »). Et il y avait une **vraie incohérence
  prompt/moteur** : devant une porte, une volée `facade:` était **toujours** refusée (son palier vidait la porte
  ou touchait le mur). Les exemples minimaux du prompt se disaient pris dans « l'exemple complet » (taille et
  murs différents) : les marches d'entrée y étaient refusées et les `pillars` listés après la porte l'auraient
  remplacée. La réparation moteur existante (retrait des marches, `anchor y: 1`) avait bien tourné (V-fa3cf1de),
  restaient échelle sans mur et zone sombre.
- **Conception détaillée vide** : l'Église n'a jamais eu de conception ; « Créer un plan » et « Nouvelle
  conception avec les enseignements » n'en faisaient pas rédiger — seul le pipeline automatique le faisait.
- **Exigences** : « cloche présente » se lisait sur la palette ; trois cloches au sommet d'une colonne d'un bloc
  passaient. Le lit ajouté n'était signalé nulle part. Les enseignements masquaient les nombres (« # bloc(s) »).
- **Essai réel** (nouveaux constats) : le prompt de conception n'avait **pas le repère** des coordonnées (tour
  d'entrée conçue au nord) ; fautes déterministes répétées par gpt-4o : `wall:` à une cellule du mur, toit `gable`
  en bloc plein, mobilier posé **dans** la couche du sol, lanternes au-dessus du plafond ; une marche extérieure
  comptée comme zone intérieure sombre.

## Travail effectué
**Plugin (moteur)** — PR #784, #785, #787, #789
- Marches d'entrée par façade : le palier est le seuil (porte et murs conservés, une voie vers la porte suffit) ;
  façade sur le bord du volume → refus « marches d'entrée » avec les deux solutions.
- Cloches : attache calculée sur la grille finale ; cloche flottante refusée (`UNSUPPORTED_BLOCK`).
- Réparations sûres, tracées avant/après dans la version « Réparé par le moteur » : `wall:` recalé (≤ 2 cellules) ;
  toit `gable` en bloc plein → escalier du même matériau ; mobilier (tapis, escaliers-assises, bougies, pots,
  pupitre) posé dans le sol → relevé sur la première couche libre ; zone sombre → torche murale au plus près
  (ou lanterne sous plafond proche) ; chaînes de lanterne jusqu'à 8 maillons.
- `DARK_AREA` ignore les cellules à ciel ouvert (perron, marche extérieure).
- Prompt : exemples minimaux décrits dans leur vrai bâtiment et **compilés ensemble** par le moteur (test) ; une
  seule des deux entrées ; `pillars` avant les portes ; repère NORD/SUD/EST/OUEST en coordonnées.

**PlugAdmin** — PR #784, #785
- `ArchitecturalDesign` : lignes `VOLUME …` / `ÉLÉMENT …` lues, vérifiées (limites, liens, tour distincte plus
  haute, exigences placées), transmises au plan comme **CONTRAT DE VOLUMES** avec leurs lacunes ; « Structure lue
  par le panel » sous la conception.
- « Créer un plan » sans conception, et « Nouvelle conception avec les enseignements », font d'abord rédiger la
  conception (appel journalisé « MANUEL — CONCEPTION AVANT LE PLAN »).
- `CompiledMeasures` : tour (≥ 3 × 3, +4 blocs sur la médiane), cloche dans la tour, allée (tapis dans l'axe de
  l'entrée), volumes de la conception — sur la grille compilée de l'aperçu ; « non mesurée » sans grille.
  Ajouts non demandés (lit, four, enclume, établi, coffre, étagères) signalés à l'écran et à l'IA.
- Enseignements : sens du code et dernier endroit mesuré au lieu de « # ».

## Fichiers créés
`ArchitecturalDesign.java`, `CompiledMeasures.java`, `Eglise747DesignTest.java`, fixtures
`control-panel/src/test/resources/ai/eglise747/*`, `src/test/resources/building/plans/eglise_747_{2caffe63,979a80e5,8565a125}.yml`, ce rapport.

## Fichiers modifiés
Plugin : `BuildingPlanExpander`, `Staircase`, `Stability`, `BlockStates`, `PlanRepair`, `ArchitecturalAudit`,
`Eglise747Test`, `PromptPlanConsistencyTest`. Panel : `AiBuildingStudio`, `AttemptMemory`, `BriefRequirements`,
`BuildingDesignPipeline`, `BuildingPromptBuilder`, `PanelApp`, `WorkshopPages`, `Eglise747MemoryTest`,
`WorkshopFlowTest`. Docs : Bible, `BATIMENTS_PARCOURS.md`, `MANUAL_TEST_PLAN.md` (TC-421), `SERVER_CHANGELOG.md`,
`SUIVI_VALIDATIONS_DEV.md`, index des rapports.

## Base de données / migrations
Aucune migration (`data.db` et `control-panel.db`). Écritures DEV : projet de test « TEST #747c église (ne pas
utiliser) » (`3f71dd09`), ses 2 images copiées de l'Église, 1 conception, 8 versions ; le premier « Vérifier »
envoyé par script a enregistré une révision du plan V-979a80e5 qui ne diffère que d'un saut de ligne final.
Compte temporaire `claude-747c` créé puis supprimé.

## Configuration / données
Aucun réglage IA modifié (modèle configuré `gpt-4o`). Église du hub et autres projets **non modifiés** ;
aucun bâtiment placé ni retiré, aucun bloc posé.

## Tests automatiques
- Sans appel payant : rejeu de V-2caffe63, V-979a80e5 et V-8565a125 (`Eglise747Test`, +9 cas), exemples minimaux
  compilés ensemble (`PromptPlanConsistencyTest`), `Eglise747DesignTest` (11 : prompt, lecture et lacunes de la
  conception, contrat, mesures sur la vraie grille de V-fa3cf1de — pas de tour, cloche hors tour, tapis en
  travers, lit signalé —, grille synthétique avec tour, enseignements), `WorkshopFlowTest` (+1 parcours :
  conception puis plan).
- Local : 494 tests `building.*` (plugin), 276 tests panel `ai.*` + `Workshop*` verts. CI GitHub verte sur les 4 PR
  et sur les 4 fusions (un échec transitoire de résolution `paper-api` sur #785, relancé, vert).

## Essai réel sur DEV (nouveau projet de test, brief et 2 images de l'Église copiés)
| Appel | Version | Résultat |
|---|---|---|
| 1 conception | design v1 | 2 volumes : nef `x 2..12 z 2..16 y 0..11`, tour `x 5..8 z 0..4 y 0..21` ; tour au **nord** (repère absent, corrigé ensuite) ; bougies, lanternes non placées → lacunes transmises |
| 2 plan | V-979a80e5 | `PLAN_INVALID` (`wall: 15`) → après les correctifs moteur, réparation gratuite V-5086e913 : 10 corrections, restent lanterne flottante + nef sombre |
| 3 amélioration (auto) | V-8565a125 | régresse (mobilier redescendu, marche retirée) → réparation gratuite V-8776ad09 : **un seul défaut**, lanterne pendue sous la cloche |
| 4 amélioration (réparation) | V-007bcdcc | défait de nouveau les corrections moteur, ajoute une croix hors volume (y=23-24) → `PLAN_INVALID` ; arrêt |

Mesures sur le résultat compilé (V-5086e913 / V-8776ad09) : **tour distincte** 4 × 5, sommet y=22 ✓ ; **cloche
dans la tour** ✓ ; **allée** 9 tapis selon z ✓ ; volumes nef 48/48 et tour 14/14 ✓ ; bancs (escaliers hors toit)
présents ; bougies ✗ ; autel, estrade, porche, croix, charpente : à juger à l'œil. **Plan compilable** oui (V-8776ad09
se construit), **aperçu disponible** oui, **conforme** non (1 défaut). Statut vert ou non, **rien de cela n'est une
validation esthétique**.

Coût : 46 455 jetons d'entrée, 4 061 de sortie, `gpt-4o-2024-08-06` ≈ **0,16 $** (2,50 $ / 10 $ par million).

## Le modèle reste l'obstacle — preuves et proposition
Sur 2 corrections, gpt-4o a **défait les corrections du moteur** (mobilier redescendu dans le sol, marche et torches
retirées, tapis réduit à une rangée) et ajouté de nouvelles fautes (croix hors volume), alors que le prompt les
nommait. Le moteur les re-répare gratuitement à chaque fois, mais la convergence s'arrête à 1 défaut. Proposition
(non appliquée, pas de changement silencieux) : un essai de **4 appels** sur le même projet de test avec un modèle
plus fort configuré par le propriétaire (par ex. un modèle OpenAI plus récent ou Claude), conception neuve
comprise (le repère corrigé replacera la tour au sud). Volume mesuré par appel : 4-15 k jetons d'entrée, ~1 k de
sortie ; à multiplier par le tarif du modèle choisi (avec gpt-4o : ≈ 0,04 $ par appel).

## Tests manuels à effectuer
TC-421 (`PENDING MANUAL VALIDATION`) ; TC-419, TC-408 toujours en attente.

## Résultat attendu
Le pipeline conçoit avant de placer, mesure ce qui est réellement construit, et corrige seul les fautes
déterministes ; le dernier défaut et l'esthétique dépendent encore du modèle.

## Reset / retour à l'état initial
Rien à défaire hors du projet de test (retirable depuis l'Atelier).

## Déploiement VeryGames
### À transférer
Fait : JAR `d5fab1c9` (11:31), `8bb79159` (14:12), `e72a550e` (14:46), `7093bc80` (15:43) via
`scripts/deploy-verygames.sh -y`, SHA retéléchargé identique à chaque fois (dernier `1dad952a…`), DEV redémarré
(seul le propriétaire connecté au 2e redémarrage). PlugAdmin `d5fab1c9` puis `8bb79159` (`deploy.sh --wait-ci`,
`/health` ONLINE, nouvelles classes vérifiées dans le JAR servi).
### Ne PAS transférer/altérer
`data.db`, configs, mondes, contenus.
### Redémarrage requis
Fait.
### Migration automatique
Aucune.

## Rollback
JAR : `scripts/rollback-verygames.sh` avec `rpgquest-20261023T134323Z-predeploy.jar` (JAR `e72a550e`) ou, pour revenir
avant #747, `rpgquest-20261023T113122Z-predeploy.jar` (JAR `4cc191cb`), puis redémarrage. PlugAdmin :
`scripts/plugadmin/rollback.sh app`.

## Logs / diagnostic
`~/.local/state/lodyquests-build/747c-deploy-{jar,jar2,jar3,jar4,panel,panel2}.log`.

## Documentation mise à jour
Bible (Atelier), `BATIMENTS_PARCOURS.md`, TC-421, changelog serveur, registre (+ sections propriétaire de `main`
`9ac8b1c3` reportées), index.

## Limitations / travail restant
- Aucune version conforme : dernier défaut (lanterne sous la cloche) et régressions viennent du modèle.
- La conception de l'essai date d'avant le correctif du repère : tour au nord ; non retesté faute d'enveloppe.
- La conformité du pipeline automatique (« Conception automatique ») ne réécrit pas la conception en cas de
  « conception neuve » ; la mémoire des tentatives ne compte pas les volumes de la conception.

## Prochaine étape suggérée
Propriétaire : ouvrir V-8776ad09 du projet de test (gratuit), juger l'aperçu, puis choisir le modèle pour un
dernier essai de 4 appels.
