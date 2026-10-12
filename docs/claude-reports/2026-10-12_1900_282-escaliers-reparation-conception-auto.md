# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-12
* Heure : 19:00
* Sujet : #282 — étages réellement reliés, réparation déterministe des plans, correction IA sans intermédiaire, conception automatique sous enveloppe
* Statut : PARTIAL (code, tests et CI terminés, PR #285 fusionnée dans la ligne d'intégration ; essais réels OpenAI sur DEV et déploiement en attente du feu vert du propriétaire)
* Branche Git : `feature/282-ai-build-vertical-circulation` → fusionnée dans `chore/257-ci-github-actions` (PR #285, merge `f4960c3`)
* Commit actuel si disponible : `f4960c3` (code : `207f258`, `d37a5e3`, `37aa437`, `d6323e7` ; docs : `dc2b111`)
* Début de la tâche : 2026-10-12 18:03:15
* Fin de la tâche : 2026-10-12 19:00:45
* Durée totale : 00:57:30

## Demande

Propriétaire, 12/10/2026 (trois messages successifs) :

1. Le 3e plan IA de la « Bibliothèque du Hub » échoue encore (`ARCHITECTURE_REFUSED` / `LEVEL_UNREACHABLE`, 2 étages détectés, 1 accessible). Étudier les actions réelles `b88b887b…` (audit) et `4e787f1a…` (aperçu), corriger la **cause structurelle** — pas un ajustement de prompt —, permettre à l'IA de proposer escalier + trémie + vrai chemin, vérifiés par le moteur. Tests déterministes sans appel payant, validation par GitHub Actions. Qualité avant coût : pipeline multi-appels (conception, vérification, correction, critique, amélioration) avec garde-fous et coûts transparents ; prévoir de vrais essais OpenAI sur DEV et **présenter l'enveloppe de coût avant**. Critère : bibliothèque médiévale de deux étages, belle, aménagée, praticable, depuis une description française, sans édition du plan. Pas de déploiement sans feu vert, aucune donnée réelle modifiée. Ensuite #283 puis #284.
2. Nouveau cas : « Maison du bibliothécaire » 11 × 11 × 8, `PLAN_INVALID`, instruction 18 GABLE jusqu'à y=8 (audit `077e207d…`, aperçu `962182ee…`). Les erreurs élémentaires doivent être détectées et corrigées automatiquement — réparation déterministe sûre d'abord, sinon IA avec diagnostic exact.
3. Après `PLAN_INVALID`, « Ce qu'il faut changer » reste vide et « Améliorer avec l'IA » exige un texte. La correction doit reprendre d'elle-même le diagnostic du dernier audit de la version courante ; commentaire facultatif ; test complet génération → audit en échec → correction IA sans commentaire → nouvel audit.

## Analyse

**Plans réels relus en lecture seule** dans `control-panel.db` (`sqlite3 -readonly`, aucune écriture) : brouillon `1fd80f65…` v1 (projet `c7b8c53e…`) et paramètre de l'action `077e207d…`. Rejoués localement par le vrai parseur, le vrai expanseur et le vrai audit.

Bibliothèque, 3e plan — trois défauts indépendants **et** un faux négatif de l'audit :
- `stair_run` de 5 marches de y=1 vers un plancher en y=6 : il en fallait 6. La dernière marche (9/5/5) arrive sous le plancher, hors de la trémie `x 5–8, z 5–8` ; le « palier » de `stair_run` (`y + steps`) **perce le plancher** en (10,6,5) au lieu de s'y poser ;
- instruction 17, `box` de bibliothèques `[2,7,2]→[16,7,16]`, posée **après** l'escalier : rebouche la hauteur libre (9/7/5) et couvre 15 × 15 cellules de l'étage ;
- échelle de y=1 à y=4, deux blocs sous le plancher, sans trémie ;
- second `stair_run` qui part *dans* la trémie et flotte dans l'étage ;
- **faux négatif** : le niveau « y=8 » détecté était le dessus des étagères. L'escalier réparé, l'ancien audit aurait accepté l'étage (on monte d'un bloc depuis l'allée de 1 bloc sur le dessus des étagères) — un étage inutilisable déclaré conforme.

Maison : `gable` de y=5 à y=8 dans un volume de hauteur 8 (y de 0 à 7) ; refus avant toute expansion, donc aucune réparation possible dans l'architecture précédente.

Champ vide : `building.plan.audit` mettait le motif dans le message d'action (tronqué à 500 caractères par `AgentEndpoints`) et **pas** dans `details` ; `BuildingPlanVerdict.message()` était donc toujours vide, et `correctionReason` n'avait rien à préremplir. `BuildingCorrectionLoop` (budget, arrêts) existait mais n'était branché à aucun écran.

Physique vérifiée : franchir une demi-marche porte la tête d'un joueur à ~3,3 blocs au-dessus de la marche quittée — 2 blocs de hauteur libre au bord d'une trémie ne suffisent pas.

## Travail effectué

**Lot A — plugin, circulation verticale (`207f258`).**
- `op: staircase` (`Staircase`) : `from` (première marche), `direction`, `floor` (y du plancher), `width` 1–3. Marches = `floor - y + 1` ; 3 blocs dégagés au-dessus de chaque marche plafonnés à `floor + 2` (= trémie calculée) ; départ 3 blocs ; palier 2 blocs **posé** sur le plancher. Appliqué **après** toutes les autres instructions (volume réservé). Refus nommant la cellule si la volée touche le mur extérieur, une porte ou un vitrage, ou si départ/palier ne portent pas. `stair_run` inchangé (régénération des bâtiments existants identique).
- Provenance : l'expanseur retient l'instruction qui a écrit chaque cellule (`Result.provenance()`).
- `AuditGrid` (grille extraite de l'audit), `Circulation` : niveaux = planchers **structurels** (meubles exclus) sur ≥ ¼ de l'emprise intérieure, étages espacés de ≥ 3 blocs, 3 blocs libres exigés au-dessus du rez-de-chaussée (sinon combles) ; marche montante exige la place de la tête.
- `LEVEL_CLUTTERED` (nouveau refus, `minLevelFreeShare` 30 %) avec les instructions responsables.
- `LEVEL_UNREACHABLE` détaillé : ouvertures du plancher, point le plus haut atteint, marche la plus haute, échelles trop courtes, cellules bloquantes avec l'instruction qui les a posées, puis escalier proposé par `CirculationPlanner` (intérieur strict, appuis, ni porte/vitrage/lumière, longe un mur, 2 voies si possible) **vérifié en rejouant l'audit complet**.

**Lot A' — plugin, réparation déterministe (`d37a5e3`).**
- `BuildingPlanYaml.readUnvalidated` + `write` (aller-retour identique, testé).
- `PlanRepair` : zone débordant ≤ 3 blocs → volume agrandi dans les limites du projet, sinon zone ramenée ; escalier ajouté ; escaliers/échelles/`clear` de plancher morts retirés ; `box` de meubles couvrant ≥ 40 % d'un plancher → `walls`. Chaque correction rejouée ; jetée si elle crée un refus nouveau.
- `BuildingGenerator.inspect/preview(plan, Limits)` ; `Verdict.repair`. Agent : `building.plan.audit`/`preview` acceptent `max_width`/`max_height`, renvoient `details.message` complet et `details.repair`.

**Lot B — panel (`37aa437`).**
- `WorkshopAutopilot` : tout plan reçu de l'IA est vérifié + prévisualisé aussitôt ; un audit de la version courante qui revient avec une réparation crée une version `ENGINE_REPAIR` « Réparé par le moteur », revérifiée d'elle-même. Branché sur `AgentEndpoints.onResult` (nouvel écouteur). Idempotent (clé : id de l'audit), jamais de réparation d'une réparation, audit périmé ignoré.
- « Améliorer avec l'IA » : diagnostic d'office (`PlanDiagnosis`, avec repli sur le message d'action pour les anciens résultats), affiché sous « Ce que l'IA recevra » ; champ « Vos consignes en plus (facultatif) » ; refus gratuit et clair si la version n'est pas encore jugée.
- Prompt : `staircase` enseigné à la place de `stair_run`, 8 points de contrat (+ « étages praticables »), exemple complet **à deux étages** (audité par le vrai moteur dans `PromptPlanConsistencyTest` : 2 niveaux, 2 atteints) ; la correction impose d'appliquer « RÉPARATION VÉRIFIÉE PAR LE MOTEUR ».

**Lot C — panel, conception automatique (`d6323e7`).**
- `BuildingDesignPipeline` (thread démon) : conception → vérification → réparation moteur → correction IA (diagnostic exact) → critique architecturale (`critiquePrompt`) ; niveaux Standard / Soigné / Exigeant = 4 / 8 / 12 appels, 0 / 1 / 2 critiques, plafond dur 12.
- Arrêts : conforme (embelli selon le niveau), enveloppe épuisée, plan déjà essayé, serveur sans verdict en 180 s. Deux refus identiques → **conception neuve** recevant les écueils rencontrés. Une critique refusée ne remplace jamais la version conforme.
- `BuildingDesignRunStore` (`building_design_run`) : jeton unique, journal des passes avec jetons, une session par projet, `interruptOrphans()` au démarrage (jamais de reprise automatique).
- Carte « Conception automatique » : enveloppe par niveau (appels, jetons, $ au tarif public pour gpt-4o / 4o-mini / 4.1 / 4.1-mini, sinon jetons seuls), case de consentement obligatoire, suivi avec rechargement automatique (meta refresh, sans script — CSP).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/building/plan/{Staircase,AuditGrid,Circulation,CirculationPlanner,PlanRepair}.java`
- `src/test/java/com/lodygames/rpgquest/building/plan/{VerticalCirculation282Test,PlanRepair282Test}.java`
- `src/test/resources/building/plans/{bibliotheque_hub_282_v3,maison_bibliothecaire_282}.yml` (plans réels, recopiés des actions)
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/{WorkshopAutopilot,PlanDiagnosis,BuildingDesignRunStore}.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/BuildingDesignPipeline.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/ai/BuildingDesignPipelineTest.java`

## Fichiers modifiés
- Plugin : `PlanStep`, `BuildingPlan`, `BuildingPlanYaml`, `BuildingPlanExpander`, `ArchitecturalAudit`, `ArchitecturalRules`, `BuildingGenerator`, `web/agent/{AgentActions,BukkitAgentActions,AgentActionExecutor}` ; test `PromptPlanConsistencyTest`.
- Panel : `AgentActionCatalog`, `AgentEndpoints`, `BuildingPromptBuilder`, `AiBuildingStudio`, `BuildingPlanDraft` (`ENGINE_REPAIR`), `BuildingPlanDraftStore` (`repairedFrom`), `BuildingPlanVerdict`, `PanelApp`, `WorkshopPages` ; tests `WorkshopFlowTest`, `BuildingCorrectionLoopTest`.
- Docs : `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-290, TC-291), `docs/deployment/SERVER_CHANGELOG.md`.

## Base de données / migrations
- `data.db` : **aucune** migration.
- `control-panel.db` : nouvelle table `building_design_run` (+ index), créée au démarrage par `CREATE TABLE IF NOT EXISTS` ; nouvelle valeur d'origine `ENGINE_REPAIR` dans `building_plan_draft.origin` (colonne texte, pas de contrainte).
- Base de production du panel : **lue seulement** (`sqlite3 -readonly` sous sudo), jamais écrite.

## Configuration / données
- Aucun fichier de configuration. Propriété JVM optionnelle `rpgquest.design.verdictTimeoutSeconds` (défaut 180).
- Aucun bâtiment, schematic, emplacement ni projet réel modifié. Aucun appel OpenAI.

## Tests automatiques
- Plugin (ciblés, locaux) : `com.lodygames.rpgquest.building.plan.*` (dont 7 + 5 nouveaux tests #282 sur les plans réels), `com.lodygames.rpgquest.web.agent.*`, `com.lodygames.rpgquest.building.*` — verts.
- Panel (ciblés, locaux) : `*Building*`, `*AgentAction*`, `*AgentEndpoint*`, `*.ai.*`, `*Workshop*`, `*Catalog*` (446 tests) — verts après mise à jour de 3 tests qui figeaient l'ancien prompt (7 points, `stair_run`, 3 fenêtres) ; `BuildingDesignPipelineTest` 10/10 ; `WorkshopFlowTest` dont les 4 nouveaux scénarios #282.
- **GitHub Actions (suite complète `test` + `build`, 3 modules)** : vert sur `37aa437` (run 38279564785), `d6323e7` (run 38280449756) et sur la PR #285 (run 38280679147).
- Suite complète locale **non relancée** (cadence convenue : ciblés en local, complet en CI).

## Tests manuels à effectuer
- TC-290 — réparation moteur + correction IA sans commentaire (gratuit sauf 1 appel) — PENDING MANUAL VALIDATION.
- TC-291 — conception automatique de bout en bout avec OpenAI sur DEV — PENDING MANUAL VALIDATION, **appels facturés, enveloppe à accepter** (voir « Prochaine étape »).

## Résultat attendu
- Le 3e plan réel de la Bibliothèque est réparé par le moteur **sans appel IA** : escalier de 6 marches, volée et échelle mortes retirées, bibliothèques le long des murs, 2 étages sur 2 atteints, plan conforme (prouvé par `PlanRepair282Test`).
- Le plan réel de la Maison est réparé (toit arasé à 8, ou volume porté à 9 si le projet l'autorise) et conforme.
- Dans l'Atelier, ces réparations apparaissent seules ; « Améliorer avec l'IA » part sans texte ; la conception automatique enchaîne les passes sous l'enveloppe consentie.

## Reset / retour à l'état initial
Revenir au commit `131b8b4` de `chore/257-ci-github-actions` (avant le merge `f4960c3`) côté code ; la table `building_design_run` peut rester (ignorée par l'ancien panel).

## Déploiement VeryGames
**Non effectué** — feu vert requis.
### À transférer
JAR RPGQuest construit depuis `f4960c3` ; panel PlugAdmin depuis le même commit (`scripts/plugadmin/deploy.sh`). Deux déploiements distincts.
### Ne PAS transférer/altérer
`data.db`, mondes, `buildings/*.yml` et schematics existants, quêtes/dialogues du serveur, `control-panel.db` (seulement sauvegardée). Ne pas déployer depuis `/srv/rpgquest/repo` : arbre sale (fichiers de contenu non commités du propriétaire) — utiliser un worktree propre.
### Redémarrage requis
Oui pour le JAR (à 0 joueur, via RCON), et redémarrage du service panel.
### Migration automatique
Panel : création de `building_design_run` au démarrage. Plugin : aucune.
## Rollback
Remettre le JAR et le panel sauvegardés (backup daté, sans écraser le précédent). Rien à défaire dans `data.db`.
## Logs / diagnostic
- Panel : `event=workshop_autopilot_failed`, `event=design_run_failed` ; audit `building.plan.engine_repair`, `building.design.start`, `building.plan.generate` (avec `run=` et `pass=`).
- Dans l'Atelier : « Copier le diagnostic » et journal des passes de la carte « Conception automatique ».
## Documentation mise à jour
Bible (« Étages reliés, réparation déterministe et conception automatique », tables des primitives et des contrôles), `current_state.md`, `MANUAL_TEST_PLAN.md` (TC-290/291), `SERVER_CHANGELOG.md`. `docs-site/` n'a pas de page sur les bâtiments : rien à y modifier.
## Limitations / travail restant
- **La beauté n'est pas prouvée par les tests** : les tests garantissent l'accessibilité, la réparation et l'orchestration ; la qualité esthétique dépend des essais réels (TC-291).
- La critique architecturale est un prompt : son effet sur la variété des façades et toits reste à mesurer sur DEV ; #283 (images de référence, nouvelle conception indépendante) la complétera.
- Les plans réels utilisent ~800 jetons de sortie pour ~20 instructions : une passe de critique peut en produire 3 000+. Le plafond actuel du fournisseur (4 000) suffit pour Standard/Soigné ; le relever à 8 000 est conseillé pour « Exigeant ».
- Le pipeline tourne dans le processus du panel : un redémarrage interrompt la session (marquée, non relancée).
- #282 non fermée (validation manuelle restante).

## Prochaine étape suggérée
1. **Feu vert pour déployer** JAR + panel sur DEV (sans ce déploiement, aucun essai réel n'est possible).
2. **Enveloppe proposée pour les essais réels OpenAI** (modèle actuellement configuré : `gpt-4o`, tarif public 2,50 $ / 10 $ par million de jetons ; hypothèse prudente 9 000 jetons d'entrée et 4 000 de sortie par appel, soit ≈ 0,06 $ l'appel) :

| Essai | Niveau | Appels max | Coût max estimé |
|---|---|---|---|
| Bibliothèque médiévale 2 étages (brief réel) | Soigné | 8 | ≈ 0,50 $ |
| Même brief, second tirage (reproductibilité) | Soigné | 8 | ≈ 0,50 $ |
| Bibliothèque, niveau vitrine | Exigeant | 12 | ≈ 0,75 $ |
| Maison du bibliothécaire (plain-pied) | Standard | 4 | ≈ 0,25 $ |
| **Total plafonné** | | **32** | **≈ 2 $ (plafond dur ≈ 2,50 $ en arrondissant)** |

   Chaque essai sur un **nouveau** projet d'Atelier, jamais enregistré dans la bibliothèque sans décision du propriétaire.
3. Puis #283 (« Recommencer depuis ma description », images de référence) et #284 (suppression sûre depuis la Bibliothèque).
