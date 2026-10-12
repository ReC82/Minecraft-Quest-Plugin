# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-12
* Heure : 21:23
* Sujet : parcours de création de bâtiments — façades, blocs, stabilité, accès, reprise, conception, descriptions (#288, #289, #290, #291, #292)
* Statut : PARTIAL — code et tests automatiques livrés, validation en jeu et générations IA à faire par le propriétaire ; adaptation au terrain réel à la pose non traitée
* Branche Git : `fix/288-292-building-pipeline` (PR #294, brouillon, base `chore/257-ci-github-actions`)
* Commit actuel si disponible : `b932ea9` (code) ; ce rapport dans le commit suivant
* Début de la tâche : 2026-10-12 21:23:56
* Fin de la tâche : 2026-10-12 23:11:43
* Durée totale : 01:47:47

## Demande

Corriger le parcours de création de bâtiments à partir des essais réels du propriétaire, dans le
moteur et le panel (pas de retouche de YAML ni de simple consigne de prompt) : tickets #288 à #292,
avec le cas de régression **Maison de Lily** (projet `bef26b6d`, version `aa8ff980`, audit SUCCESS
780 blocs, enregistrement `9c1b84fe` en `SCHEMATIC_FAILED` sur `minecraft:bed`). Commencer par les
façades et la validation des blocs. Aucun appel IA payant, aucune donnée réelle modifiée, ne pas
fermer les tickets, rapport distinguant prouvé / à tester / limites.

## Analyse

Lecture **seule** des plans et résultats réels : copie de `control-panel.db` par `.backup` en mode
lecture seule dans le répertoire temporaire de session ; rien n'a été écrit dans la base du panel ni
sur le serveur.

1. **Façades.** `Facade.cell` résolvait toute façade sur la **limite du volume** (19 × 19) alors que
   les murs de Lily allaient de [3,1,3] à [15,4,15] : porte en z=18, vitres en x=0/18 et z=0/18,
   détachées. L'audit cherchait ensuite l'entrée sur les **bornes de la grille** — que ces vitres
   isolées élargissaient jusqu'au volume : la porte posée dans le jardin y était un « passage »,
   d'où le faux SUCCESS.
2. **Blocs.** `BlockState` ne contrôlait que la forme ; le registre n'était consulté qu'à l'écriture
   (`parseFromInput` de WorldEdit). Le registre officiel 1.21.11 (généré par le serveur vanilla,
   `--reports`) confirme : `minecraft:bed` et `minecraft:stained_glass_pane` **n'existent pas**
   (seulement les 16 couleurs) ; un essai antérieur avait aussi échoué sur `minecraft:stained_glass`.
   Le registre a aussi révélé que `minecraft:chain` s'appelle `minecraft:iron_chain` en 1.21.11.
3. **Lit absent / lanternes (#288)**, plan réel `adc9189d` : `box white_bed` de deux cellules = deux
   pieds sans tête, détruits à la première mise à jour ; lanternes de `light` à y=4 décidées
   « posées » avant le toit, donc sur le vide ; `light` sur plusieurs hauteurs empilait des
   lanternes.
4. **Accès (#291)** : l'ancre d'un emplacement est la case libre au-dessus du sol ; avec l'ancre
   Atelier par défaut (y=0) et un sol en y=0, **tout** bâtiment a un seuil d'un bloc à sauter.
5. **#290** : l'Atelier jugeait « Enregistré » sur le seul succès de l'enregistrement de la version
   affichée — un retrait ultérieur n'était pas vu. **Rattachement** : aucun lien erroné en base ;
   le lien projet ↔ bâtiment est l'identifiant (`maison_bibliothecaire_test` ne correspond qu'au
   projet « Maison du bibliothécaire ») ; la mention « Bibliothèque du Hub » vient de l'emplacement
   `buildsite_0009`, lui-même nommé ainsi, dont la description avait été reprise dans ce projet.
   Je n'ai pas vu la capture : cette explication est la seule que le code et les données permettent.
6. **#289** : la légende de l'aperçu était bornée à **dix** matériaux — d'où lectern, spruce_planks,
   oak_fence, oak_stairs « absents » alors que présents ; certains peuvent aussi être recouverts par
   une instruction postérieure (désormais signalés comme tels, sans parler de perte).
7. **#292** : borne de 6 000 dans quatre endroits ; `\r\n` des formulaires comptés double ; échec
   d'enregistrement visible seulement dans l'historique.

## Travail effectué

Cinq commits, poussés sur la PR #294 :

| Commit | Contenu |
|---|---|
| `335984b` | Lot 1 — `BlockCatalog` (1 166 blocs 1.21.11, généré), validation exhaustive et regroupée, contrôle de chaque état produit, `SchematicGateway#rejected` (vrai parseur WorldEdit avant verdict, `BLOCK_REJECTED`), `SCHEMATIC_FAILED` = échec d'écriture ; ouvertures dans les murs construits (`wall:`), `Access` (marche terrain → porte → intérieur : `NO_ENTRANCE`, `ENTRANCE_STEP`, `OPENING_DETACHED`, `WINDOW_OBSTRUCTED`) ; réparations : décalage d'ouverture, marches dans le volume, fondation enterrée ; prompt |
| `11c53d9` | Lot 2 (#288) — `op: bed`, lanternes résolues sur la grille finale, espacement vertical de `light`, `Stability` (`UNSUPPORTED_BLOCK`, `BROKEN_ASSEMBLY`), réparation par chaîne `iron_chain` ; neuvième point du contrat |
| `b9a27ef` | Lot 3 (#292) — borne unique 12 000, normalisation `\r\n`, enveloppe calculée sur le contenu, `SaveFailure` affiché dans l'Atelier avec motif complet (`details.message`) et action utile |
| `77f588e` | Lots 4–5 (#290, #289) — `CatalogState`, « Reprendre la conception », « Réenregistrer », « Publier une nouvelle version » (`expected_version`, `VERSION_CONFLICT`, archive `buildings/.history/`), liens Bibliothèque ; conception détaillée versionnée (`building_design_doc`), brief + conception dans chaque demande, critique « OMISSIONS ET ÉCARTS », capacités/limites du moteur, verdict sans prétention esthétique, légende complète ; documentation |
| `b932ea9` | Test du parcours complet de la Maison de Lily réelle jusqu'au schematic |

Choix structurants, pris en autonomie et réversibles :
- `offset` garde son sens (coordonnée du plan le long de la façade) : seule la profondeur change,
  et les bâtiments sur toute l'emprise s'expansent comme avant.
- Seuil à sauter **bloquant** pour un bâtiment habitable (constaté comme défaut par le
  propriétaire) ; la réparation le corrige sans IA dans les cas vus.
- `processResources` n'étend plus que `plugin.yml` (le gabarit Groovy refusait le registre de 94 Ki).

## Fichiers créés

- `scripts/building/gen-block-catalog.py`, `src/main/resources/building/blocks-1.21.11.txt`
- Plugin : `building/plan/BlockCatalog.java`, `Access.java`, `Stability.java`, `Bed.java`
- Panel : `building/SaveFailure.java`, `CatalogState.java`, `BuildingDesignDocStore.java`
- Tests : `Facades288Test`, `Stability288Test` ; fixtures `maison_de_lily_aa8ff980.yml`,
  `maison_bibliothecaire_adc9189d.yml` (copies en lecture seule des plans réels)

## Fichiers modifiés

Plugin : `BuildingPlan`, `BuildingPlanExpander`, `BuildingPlanYaml`, `PlanStep`, `ArchitecturalAudit`,
`PlanRepair`, `BuildingGenerator`, `BlockState`, `SchematicGateway`, `SchematicWorkshop`,
`WorldEditSchematicGateway`, `BuildingSite`, `AgentActions`, `BukkitAgentActions`,
`AgentActionExecutor`, `RPGQuestBootstrap`, `build.gradle.kts`.
Panel : `BuildingPromptBuilder`, `AiBuildingStudio`, `BuildingDesignPipeline`, `AgentActionCatalog`,
`AgentStore`, `BuildingProject`, `BuildingProjectStore`, `BuildingSiteView`, `BuildingPlanDraft`,
`PlanDiagnosis`, `PlanVerdictText`, `IsometricView`, `PanelApp`, `WorkshopPages`, `AgentPages`.
Tests existants adaptés : `ArchitecturalAuditTest`, `SemanticPrimitivesTest`, `PlanRepair282Test`,
`PromptPlanConsistencyTest`, `BuildingGeneratorTest`, `AgentActionExecutorTest`,
`BuildingCorrectionLoopTest`, `BuildingDesignPipelineTest`, `WorkshopFlowTest`,
`AgentActionCatalogTest`, `BuildingProjectStoreTest`, `BuildingSitesPageTest`.

## Base de données / migrations

- `data.db` : aucune migration.
- `control-panel.db` : table `building_design_doc` créée au démarrage (`CREATE TABLE IF NOT EXISTS`).

## Configuration / données

Aucune configuration. Nouveau dossier serveur `plugins/RPGQuest/buildings/.history/` créé à la
première republication. Aucune donnée réelle modifiée pendant le travail.

## Tests automatiques

**Prouvé automatiquement** (déterministe, sans serveur ni appel payant) :
- Lily réelle : les deux blocs inexistants refusés **d'un coup** avec instructions et suggestions ;
  ouvertures dans les murs [3..15], plus rien sur la limite 0/18 ; marche ajoutée en z=16 ; parcours
  complet jusqu'à la définition et au schematic remis au moteur (moteur simulé) — y compris deux
  défauts réels que l'ancien audit masquait et que le moteur refuse honnêtement : six lanternes en
  plein vide (trop loin du toit pour une chaîne) et un **second toit ouvert sous débord** (56
  cellules), corrigés dans le test comme l'IA devrait le faire.
- Quatre orientations, pleine emprise (porte inchangée, fondation enterrée), deux corps (`wall:`),
  porte et vitre isolées jamais comptées, état calculé invalide nommé avec sa cellule.
- Plan réel `adc9189d` : box de lit refusée ; avec `op: bed`, lit complet ; ses 9 lanternes
  flottantes nommées puis suspendues, plan conforme. Lits dans les 4 directions, sérialisation,
  assemblages brisés, lanterne sur lanterne, torche et échelle sans mur.
- Bibliothèque v3 réelle : réparée et conforme (escalier, fondation enterrée, chaînes).
- Republication : `VERSION_CONFLICT`, archive v1, version 2.
- Parcours HTTP : échec d'écriture affiché avec motif complet et « Réessayer » ; échec de contenu
  envoyé à l'IA sans commentaire ; 12 000 caractères avec `\r\n` conservés jusqu'à l'IA, 12 001
  refusés ; publier → reprendre → modifier → republier (case, version vue) ; retirer → redémarrage
  → « Retiré », plus de pose, réenregistrer ; Bibliothèque → projet nommé + reprise ; conception
  rédigée, modifiée sous contrôle de version (texte refusé réaffiché), suivie par le plan ;
  conception automatique commençant par la conception détaillée.

Exécutions locales (tests ciblés, puis) : plugin `building.*` + `web.agent.*` 603 tests verts
(lot 1) puis verts aux lots suivants ; **suite complète du panel : 1 481 tests verts** (lot 4) ;
suites ciblées vertes après le lot 5 ; `./gradlew build -x test` vert. CI GitHub : runs des lots
2 et 3 verts (`38301987770`, `38303927865` et PR) ; **CI complète verte sur la tête `b932ea9`** — runs `38308295546` (push) et `38308303281` (PR) : tests plugin, tests control-panel, web-api, artefacts, scripts ; le run de `77f588e` a été annulé par la poussée suivante (concurrence), pas en échec.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` :
- **TC-294** lit, lanternes, stabilité après pose et mises à jour physiques, rotations 90°/180° ;
- **TC-295** Maison de Lily : blocs, façades, entrée sans saut, enregistrement (appels facturés) ;
- **TC-296** reprendre, retirer, réenregistrer, republier, conflit de version ;
- **TC-297** conception détaillée et jugement esthétique (appels facturés).

## Résultat attendu

Un « conforme » ne peut plus précéder un échec d'écriture dû à un bloc ; une porte ou une fenêtre
hors des murs n'est jamais comptée ; on entre sans sauter ; ce qui tomberait à la pose est refusé
ou suspendu ; un bâtiment publié reste modifiable et republiable ; un retrait se voit dans
l'Atelier ; une description de 12 000 caractères passe entière ; un échec d'enregistrement se lit
dans l'Atelier.

## Reset / retour à l'état initial

Revenir au JAR et au panel précédents (voir Rollback). La table `building_design_doc` peut rester.

## Déploiement VeryGames

**Non déployé** — aucun déploiement sans le feu vert du propriétaire.

### À transférer
JAR RPGQuest construit depuis la PR fusionnée, **et** PlugAdmin (`scripts/plugadmin/deploy.sh`).

### Ne PAS transférer/altérer
Mondes, `data.db`, `config.yml`, `buildings/`, `schematics/`, projets et versions de l'Atelier.

### Redémarrage requis
Oui pour le JAR (à 0 joueur) ; redémarrage du service panel.

### Migration automatique
Table panel `building_design_doc` uniquement.

## Rollback

Panel : `scripts/plugadmin/rollback.sh app`. Plugin : `scripts/rollback-verygames.sh --latest` puis
redémarrage contrôlé (TLS : autorisation nécessaire tant que le certificat VeryGames est expiré).

## Logs / diagnostic

Nouveaux journaux d'audit panel : `building.plan.resume`, `building.design.write`,
`building.design.save`. Effets de `building.generate` : « version N archivée : buildings/.history/… ».

## Documentation mise à jour

`docs/RPGQUEST_BIBLE.md` (section #288–#292, enveloppe #283), `docs/current_state.md` (ce lot ;
#282–#284 marqués déployés), `docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md`
(TC-294 à TC-297).

## Limitations / travail restant

- **Écriture réelle du `.schem`** : prouvée en jeu seulement (sans serveur, le registre WorldEdit
  n'existe pas) ; le contrôle préalable par le vrai parseur ne joue que sur le serveur.
- **Adaptation au terrain réel à la pose (#291)** : non traitée. L'audit suppose un terrain plat
  sous `anchor.y` ; aucune marge extérieure n'est jamais ajoutée (marches dans le volume ou
  fondation enterrée), donc emprise, collisions, sauvegarde et restauration sont inchangées.
- **Rotation** : les états (lit, marches, portes) sont tournés par WorldEdit à la pose — vérification
  en jeu (TC-294).
- **Rollback d'une republication** : la version remplacée est archivée, mais il n'y a pas d'action
  « Restaurer » dans le panel (restauration à la main depuis `buildings/.history/`).
- **Qualité artistique** : non mesurée et non prétendue ; jugement du propriétaire (TC-297).
- Registre limité à 1.21.11 : à régénérer par le script à chaque montée de version du serveur.
- Tickets #288 à #292 laissés ouverts (validation manuelle à faire).

## Prochaine étape suggérée

Revue et fusion de la PR #294 une fois la CI verte, puis, sur feu vert, déploiement JAR + panel sur
DEV et passage de TC-294 à TC-297 ; ensuite l'adaptation au terrain réel au moment de la pose.
