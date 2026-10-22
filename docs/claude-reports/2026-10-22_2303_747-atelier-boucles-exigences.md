# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-22
* Heure : 23:03
* Sujet : #747 (P0) — Atelier IA : boucles de correction et perte des exigences du brief (mémoire des tentatives, régressions, conformité)
* Statut : DONE (livré et déployé sur DEV ; ticket laissé ouvert pour la validation du propriétaire)
* Branche Git : `fix/747-atelier-boucles-exigences` (PR #750), `fix/747-memoire-versions` (PR #751), `docs/747-rapport` ; intégration `chore/257-ci-github-actions`
* Commit actuel si disponible : `a8a0cdb3` (fusion #751) ; JAR déployé = `5bfb9cef` (fusion #750)
* Début de la tâche : 2026-10-22 21:00:26
* Fin de la tâche : 2026-10-22 23:03:41
* Durée totale : 02:03:15

## Demande
Session autonome lancée par `lqorch`, ticket #747 et spécification du propriétaire du 22/10
(`specs/2026-10-22-747-atelier-boucles-exigences.md`) : analyser d'abord le journal IA du projet église
régénéré en lecture seule, rejouer ses plans localement sans appel payant, puis corriger le générateur
de façon GÉNÉRALE (mémoire structurée, régressions, stagnation, réparation vs amélioration, conformité au
brief, audit du contrat prompt/DSL/compilateur/validateur, images), tester, déployer sur DEV et faire un
parcours réel PlugAdmin sur un projet de test, dans une enveloppe de 3 appels payants au plus.

## Analyse

### Journal régénéré (lecture seule)
Copie de `control-panel.db` DEV par `sqlite3 … ".backup"` en `mode=ro`, puis export par la **même
fonction que le bouton** (`BuildingAiJournalExport.render`, classes de PlugAdmin déployées) :
`journal-ia-eglise-du-hub-20261022-210138.txt` (39 événements, 13 appels). Projet `d770e76f`
« Église du hub ».

**Concordance avec les observations du propriétaire** :
- « 9 appels » : exact pour la fenêtre 19:10–20:23 (Bruxelles) — n°2 à n°26 ; le journal en compte
  13 au total, 4 autres ayant suivi à 20:34–20:37. Tous des « MANUEL — AMÉLIORER AVEC L'IA »,
  modèle rapporté `gpt-4o-2024-08-06`.
- « Les diagnostics des versions précédentes ne sont pas transmis » : confirmé (contexte de chaque requête).
- `images: []` sur les 9 appels : **aucune image n'était attachée au projet** à ce moment-là (les deux
  références ont été ajoutées à 20:34:08 et 20:37:01). Les 4 appels suivants les ont **bien transmises**
  (pixels, empreintes `21a65754…` puis `42cd6060…`). Pas de perte d'image.
- « Corrige UNIQUEMENT ce que le motif désigne » : confirmé.
- Plans sans clocher, cloche ni bancs : confirmé.
- `clear [7,1,1] → [11,12,17]` après fenêtres et lumières : confirmé (voir causes).
- `UNSUPPORTED_BLOCK` disparu à 20:20:12 (V-207e6e4f) puis revenu à 20:20:27 (V-21b94ae2) : confirmé.
- Second pignon au-dessus du premier, 504 → 630 cellules, `LEVEL_UNREACHABLE` : confirmé (V-b4d37c6c).
- Non signalé dans les observations : les deux dernières réponses (n°36, n°39, avec 2 images) valent
  « I'm sorry, I can't assist with that. » — rangées comme plan et soumises au serveur (PLAN_INVALID).

### Rejeu local (vrai compilateur, vrai validateur, aucun appel payant)
Les 13 plans extraits sans retouche (`src/test/resources/building/plans/eglise_747_*.yml`) et rejoués par
`BuildingGenerator.inspect` avec les bornes du projet (19 × 19 × 23) : **verdicts identiques au journal**.

### Causes confirmées
1. **« clear » destructif** : les instructions s'appliquent dans l'ordre ; ce `clear` vidait la porte
   (2/2 cellules), le grand vitrail arrière (18/18), des lanternes, et perçait les murs avant et arrière
   sur toute la hauteur. Aucun contrôle ne le disait (une ouverture de 5 de large passait pour une entrée).
2. **Faux positif `ENVELOPE_OPEN`** : le contrôle lisait les faces du **volume** (x=0/18, z=0/18) alors que
   les murs étaient en retrait (1..17) sous un toit débordant (0..18) : l'avant-toit comptait comme 504
   « ouvertures », et le message accusait toujours les pignons → le modèle a empilé un second toit
   (b4d37c6c), ce qui a créé un faux étage (`LEVEL_UNREACHABLE`) et de nouveaux pignons ouverts (630).
   Après correction, la même version donne **72** cellules : les deux vrais trous du `clear`.
3. **`UNSUPPORTED_BLOCK`** = lanternes posées sur le vide (le nom du code est trompeur) ; résolu puis
   réintroduit, faute de mémoire : chaque reprise ne recevait que le dernier diagnostic.
4. **Aucune vérification des exigences du brief** ; toutes les reprises étaient des réparations restrictives.
5. **Refus du modèle traité comme un plan.**

## Travail effectué

### Plugin (JAR)
- `BuildingPlanExpander` : provenance des `clear` (cellule vidée → rang du `clear`, effacements par
  instruction avec la part effacée).
- `ArchitecturalAudit` : `CLEAR_ERASES` **bloquant** (porte, lit, échelle, ou ≥ moitié d'une
  fenêtre/lumière effacée) et `CLEAR_NIBBLES` (avertissement) ; `ENVELOPE_OPEN` tolère un mur en retrait de
  1–2 cellules (angles compris) et **localise** les ouvertures par face, étendue et cause (« vidées par
  l'instruction N (clear …) », « sous le toit de l'instruction N : PIGNON non rempli », « mur absent »),
  avec « Ne superpose PAS un second toit » ; palette du résultat compilé dans les mesures.
- `PlanRepair.destructiveClears` : remonte un `clear` destructif avant la première instruction
  d'architecture qu'il efface, seulement si rien d'autre ne les sépare ; jugé par l'audit complet.
- `BukkitAgentActions` : `metrics.palette` transmis au panel.

### PlugAdmin
- `AttemptMemory` : versions jugées du projet (relues dans les versions et leur dernier verdict serveur —
  PR #751, voir parcours réel), constats réduits à **code + emplacement + cause**, erreurs résolues (une
  version `PLAN_INVALID` ne résout rien), régressions, fautes récurrentes, meilleure version refusée,
  stagnation (3 tentatives sans battre la meilleure) ; bloc de prompt borné (≤ 4 000 caractères).
- `BriefRequirements` : lexique **général** (aucun bâtiment codé) — contrôles déterministes sur le plan
  ET la palette compilée (cloche, pupitre, vitraux, tapis, lanternes, bougies, fleurs, bancs hors
  toit/volée, lit, étagères, four, enclume, établi, coffre), appréciations visuelles séparées (tour,
  autel, estrade, allée, porche, croix, cheminée, charpente), négations (« sans lit ni coffre, avec un four »).
- `BuildingPromptBuilder` : modes **Réparation technique / Amélioration architecturale**, blocs mémoire et
  conformité, critique avec la conformité ; contrat : ordre des `clear`, un seul `gable` par corps.
- `AiBuildingStudio` : manifeste du journal avec `mode`, `memory`, `requirements` (présence mot pour mot) ;
  une réponse sans `steps:` n'est jamais soumise.
- `BuildingDesignPipeline` : candidat régressif **non promu** (la correction repart de la meilleure
  version refusée, note explicative), stagnation → conception neuve puis **arrêt** « Stagnation : … »,
  passe « COMPLÉTUDE DU BRIEF » (une, dans l'enveloppe, même en Standard), version conforme moins fidèle
  non retenue, issue « INCOMPLET vis-à-vis du brief ».
- `PanelApp` / `WorkshopPages` : « Améliorer avec l'IA » envoie mémoire + conformité + mode (champ
  « Nature de la reprise » : Automatique avec raison, Réparation, Amélioration) ; bloc replié montrant
  ce qui partira et l'état des images ; textes sans jargon ; `PlanVerdictText` connaît `CLEAR_ERASES`.

### Parcours réel sur DEV (après déploiement)
Compte PlugAdmin temporaire (créé puis **supprimé**), nouveau projet **« TEST #747 église (ne pas
utiliser) »** (`444b0b52`, brief de l'Église copié, SOUTH 19 × 19 × 23), plan 8a2a9614 importé **par
copie** (« recover », source inchangée, aucun appel IA) :

| Étape | Version | Verdict DEV | Conformité vérifiable |
|---|---|---|---|
| Import + Vérifier (gratuit) | b65b4cea | ENVELOPE_OPEN (72, « vidées par l'instruction 20 (clear …) »), CLEAR_ERASES (porte 2/2, vitrail 18/18, 3 lanternes) | 3/8 |
| Appel 1 (auto → Amélioration) | 9e287b8a | UNSUPPORTED_BLOCK, DARK_AREA, ENVELOPE_OPEN, CLEAR_ERASES | cloche, bancs, tapis, fleurs ajoutés |
| Réparation moteur (gratuite) | 5a8bbd88 | DARK_AREA, ENVELOPE_OPEN (lanternes suspendues, `clear` remonté) | 7/8 |
| *(correctif #751 : la mémoire ignorait 9e287b8a, jamais améliorée elle-même — trouvé ici, corrigé, redéployé avant l'appel 2)* | | | |
| Appel 2 (auto → Amélioration, mémoire 3 versions) | 9c99a660 | UNSUPPORTED_BLOCK, DARK_AREA, ROOF_MISSING — **plus de `clear`, enveloppe 0, porte et grand vitrail rétablis** | 8/8 |
| Appel 3 (auto, régression + stagnation signalées) | 043206af | UNSUPPORTED_BLOCK, ROOF_MISSING | 7/8 (bougies retirées) |
| Réparation moteur (gratuite) | 23ddf2ff | **ROOF_MISSING seul** (56 colonnes) | 7/8 |

Le journal du projet de test prouve, pour chaque requête : `mode=IMPROVE`, `memory` et `requirements`
« présents mot pour mot : oui », contexte « Mémoire des tentatives transmise … / Conformité au brief … /
Mode … », `images: []` avec « Aucune image de référence attachée au projet ». **Coût** : 3 appels `gpt-4o`
(27 360 jetons d'entrée, 3 219 de sortie) ≈ **0,10 $** au tarif public. Modèle : `gpt-4o` (celui configuré
sur DEV) plutôt que le moins cher, pour ne pas modifier les réglages IA partagés du panel.

## Fichiers créés
- `src/test/java/com/lodygames/rpgquest/building/plan/Eglise747Test.java`, 13 fixtures `src/test/resources/building/plans/eglise_747_*.yml`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/AttemptMemory.java`, `BriefRequirements.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/ai/Eglise747MemoryTest.java`, `control-panel/src/test/resources/ai/eglise_747_brief.txt`, `eglise_747_8a2a9614.yml`
- ce rapport

## Fichiers modifiés
- Plugin : `ArchitecturalAudit.java`, `BuildingPlanExpander.java`, `PlanRepair.java`, `BukkitAgentActions.java`, `Facades288Test.java`
- PlugAdmin : `AiBuildingStudio.java`, `BuildingDesignPipeline.java`, `BuildingPromptBuilder.java`, `PanelApp.java`, `WorkshopPages.java`, `PlanVerdictText.java`, `BuildingDesignPipelineTest.java`, `WorkshopFlowTest.java`
- Docs : `docs/RPGQUEST_BIBLE.md`, `docs/control-panel/BATIMENTS_PARCOURS.md`, `docs/MANUAL_TEST_PLAN.md` (TC-408), `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune migration (ni `data.db`, ni `control-panel.db`). Écritures DEV : le projet de test et ses versions,
son journal IA ; compte temporaire créé puis supprimé.

## Configuration / données
Aucune configuration modifiée. Église du hub et autres projets **non touchés** ; rien posé en jeu.

## Tests automatiques
- Plugin : `building.*` + `web.agent.*` verts localement (dont `Eglise747Test`, 8 cas, et `Boulangerie655Test` inchangé) ; `Facades288Test` adapté (réparation « fondation enterrée » désormais retenue pour Lily, cause documentée).
- PlugAdmin : `ai.*` (dont `Eglise747MemoryTest`, 5 nouveaux scénarios `BuildingDesignPipelineTest` : non-promotion, stagnation, complétude, brief incomplet, refus du modèle), `WorkshopFlowTest` (bloc affiché, jargon, mémoire de toutes les versions), `building.*` verts.
- La suite complète panel lancée localement a dépassé la limite de 20 min de la session (arrêtée, pas en échec) ; suite complète : **CI GitHub verte** sur PR #750, PR #751 et sur les deux commits de fusion.

## Tests manuels à effectuer
- **TC-408** (`PENDING MANUAL VALIDATION`) : bloc « Mémoire des tentatives et conformité à la description », « Nature de la reprise », journal de la requête.
- Sur l'Église du hub (décision et coût du propriétaire) : vérifier que le diagnostic nomme le `clear` et ne parle plus de pignons.

## Résultat attendu
Une reprise ne réintroduit plus silencieusement une erreur résolue, la stagnation s'arrête, un bâtiment
valide mais incomplet est dit incomplet, et le serveur dit où et pourquoi l'enveloppe est ouverte.

## Reset / retour à l'état initial
Le projet de test « TEST #747 église (ne pas utiliser) » peut être retiré depuis l'Atelier. Rien d'autre à défaire.

## Déploiement VeryGames
### À transférer
JAR `5bfb9cef` — déjà fait (22:33, SHA `34c2cd21…`, « JAR en ligne : 4241200 octets (== local) »).
### Ne PAS transférer/altérer
`data.db`, configs, mondes, bâtiments posés.
### Redémarrage requis
Oui (fait, 0 joueur connecté, retour ONLINE).
### Migration automatique
Aucune.

## Rollback
JAR : `scripts/rollback-verygames.sh --latest` (backup `rpgquest-20261022T203255Z-predeploy.jar`) puis
`scripts/verygames-restart.sh`. PlugAdmin : `scripts/plugadmin/rollback.sh app`.

## Logs / diagnostic
`/health` du 2e déploiement PlugAdmin : « KO » au premier sondage (course connue), ONLINE à la relecture ;
`/opt/plugadmin/app/DEPLOYED` = `a8a0cdb3`, classes `AttemptMemory` / `BriefRequirements` présentes.

## Documentation mise à jour
Bible (table d'audit, Atelier), guide `BATIMENTS_PARCOURS.md`, TC-408, registre, changelog serveur, index.

## Limitations / travail restant
- **`ROOF_MISSING` n'est pas localisé** (« 56 colonnes ») : même défaut de forme que l'ancien `ENVELOPE_OPEN`
  — ici un `gable` trop bas pour sa portée. Prochaine correction générale probable : dire où et pourquoi,
  et une réparation moteur sûre (relever le faîte dans le volume).
- La tour-clocher n'est jamais vérifiée : appréciation visuelle seulement (aucune garantie inventée).
- Le dernier appel a retiré les bougies (7/8) : la régression de conformité est mesurée mais un appel
  manuel unique ne peut pas la refuser (seule la conception automatique ne promeut pas une version moins fidèle).
- Stagnation comptée par nombre de codes : deux versions à égalité ne comptent pas comme un progrès.
- Les réponses « I'm sorry… » avec 2 images restent inexpliquées (refus du modèle sur ces images ?) : désormais elles ne sont plus soumises.

## Prochaine étape suggérée
Validation TC-408 par le propriétaire ; puis localiser `ROOF_MISSING` et réparer un toit trop bas, en partant
du projet de test (gratuit, rejeu local de 23ddf2ff).
