# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-30
* Heure : 02:50
* Sujet : #747 — toit `gable minecraft:glass` compilé avec des états d'escalier (retour propriétaire du 29/10 18:34, maison du botaniste #658)
* Statut : DONE (livré, CI verte, fusionné dans la branche d'intégration) — **non déployé** (déploiement DEV sur accord écrit du propriétaire)
* Branche Git : `fix/747-gable-bloc-plein` (PR #931) → `chore/257-ci-github-actions` ; rapport sur `docs/747-gable-verre`
* Commit actuel si disponible : `bbf85b2a` (plugin), `b588defc` (PlugAdmin)
* Début de la tâche : 2026-10-30 02:30:32
* Fin de la tâche : 2026-10-30 03:01:06
* Durée totale : 00:30:34

## Demande

Retour propriétaire du 29/10 18:34 sur #747 (et #658) : trois générations de la « Maison du Botaniste » refusées,
`Plan refusé : États de bloc refusés par le registre Minecraft 1.21.11`. L'instruction 6, `gable minecraft:glass`
de `[3,7,1]` à `[13,10,10]`, produisait `minecraft:glass[facing=south|north,half=bottom,shape=straight,waterlogged=false]`.
Corriger la compilation GABLE (normaliser selon le registre avant les voxels, sans supprimer le contrôle de validité),
tests de non-régression (verre, dalle, escalier), empêcher la répétition de la même erreur dans la boucle,
examiner le journal IA et le nombre d'appels. Aucun appel IA payant, aucun déploiement sans accord écrit.

## Analyse

* `BuildingPlanExpander.gable` appelait toujours `BlockStates.stairs(matériau, …)`, quel que soit le matériau.
* `PlanRepair.gableStairs` (#747, 23/10) remplace déjà un bloc plein par l'escalier du même matériau
  (`polished_deepslate` → `polished_deepslate_stairs`), mais **il n'existe pas d'escalier de verre** : le candidat
  `glass_stairs` n'est pas dans le registre, la réparation ne fait rien, et l'expansion fabrique un état invalide.
  Le refus est donc structurel : aucune reformulation du modèle ne pouvait le corriger tant qu'il gardait le verre,
  d'où la boucle.
* Journal IA du projet `14d85d40…` (copie lecture seule de la sauvegarde `control-panel.db-20261029T183553Z-pre-881-da2257c1.backup`,
  jamais la base en service) : **6 appels gpt-4o** le 29/10 entre 18:26 et 18:35 — 1 conception (aucun plan),
  1 création (refusée pour une fenêtre hors mur), puis 4 « Améliorer » ; les 3 vérifications de 18:29, 18:30 et 18:30
  donnent exactement le même refus de registre, et la 5ᵉ version (18:35, `10e84a38`) garde aussi le toit de verre.
  Les 5 plans produits contiennent tous `gable … block: minecraft:glass`. Le coût n'est pas enregistré dans le journal
  (seuls les jetons : ≈ 9 000–11 000 en entrée, ≈ 850–900 en sortie par appel).

## Travail effectué

1. **Plugin** — `BuildingPlanExpander.roofState` : l'état d'un rang de toit suit les propriétés que le registre 1.21.11
   connaît au matériau. Escalier (`facing` + `half` + `shape`) → marches orientées comme avant ; dalle → `type=bottom` ;
   tout autre bloc connu (verre, verre teinté…) → **état simple**, le toit devient des gradins pleins, fermé.
   Un bloc inconnu garde l'ancien comportement : il reste refusé par le registre, avec ses suggestions. Le contrôle
   `unknownStates` n'est pas modifié.
2. **PlugAdmin** — `CompiledMeasures.pitchedColumns` : une colonne coiffée d'un bloc plein en gradin (versant, faîte,
   égout) compte comme toit en pente ; un toit plat ou bordé d'un parapet ne compte pas. Sans cela, le toit de verre
   compilé aurait été signalé « Toiture en pente » manquante et aurait relancé une correction.
   Le prompt (`BuildingPromptBuilder`, ligne `gable`) précise qu'il n'existe pas d'escalier de verre et que
   `block: minecraft:glass` donne des gradins de verre plein.
3. Les deux plans réels du botaniste (`96224470`, `10e84a38`) sont devenus des fixtures de test et rejoués sans appel :
   plus de `PLAN_INVALID` ; verdict `ARCHITECTURE_REFUSED` sur leurs **vrais** défauts (`OPENING_DETACHED` : la serre
   est dessinée à l'intérieur du volume principal, ses vitrages ne bordent aucun intérieur ; `DARK_AREA` ;
   `UNSUPPORTED_BLOCK` pour une lanterne sur le vide dans `96224470`). Ces retours sont désormais exploitables par la
   boucle de correction, au lieu de la même erreur de registre.

## Fichiers créés
* `src/test/java/com/lodygames/rpgquest/building/plan/GableMaterial747Test.java`
* `src/test/resources/building/plans/botaniste_747_96224470.yml`, `botaniste_747_10e84a38.yml` (plans réels, aucune donnée sensible)
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/ai/SteppedRoof747Test.java`
* ce rapport

## Fichiers modifiés
* `src/main/java/com/lodygames/rpgquest/building/plan/BuildingPlanExpander.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/CompiledMeasures.java`, `BriefRequirements.java` (libellé), `BuildingPromptBuilder.java`
* `docs/RPGQUEST_BIBLE.md` (ligne `gable`), `docs/MANUAL_TEST_PLAN.md` (TC-448), `docs/deployment/SERVER_CHANGELOG.md`,
  `docs/SUIVI_VALIDATIONS_DEV.md` (ligne #747), `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Lecture seule d'une **copie** de sauvegarde de `control-panel.db` (dans `/tmp/s747`) pour extraire les plans et le journal.

## Configuration / données
Aucune modification de configuration, de données joueurs ni de projets de l'Atelier.

## Tests automatiques
* Plugin, local (`RPGQUEST_TEST_MAX_HEAP=768m`) : `GableMaterial747Test` (8), `BuildingPlanExpanderTest`, `PlanRepair282Test`,
  `Boulangerie655Test`, `AuditRoofLevelsTest`, `Eglise747*`, `AcceptableDefects747Test` — 60 + 8 verts
  (un premier essai a échoué sur ma propre assertion de dalle : les pignons sans `fill` gardent la dalle à son état
  par défaut, valide ; assertion corrigée).
* PlugAdmin, local : `SteppedRoof747Test` (3), `RoofRequirement747Test` (5), `Cabane747Test` (4), `Eglise747*` (35),
  `RequirementUpdates747Test` (5), `BuildingCorrectionLoopTest` (16), `BuildingDesignPipelineTest` (27) — verts.
* CI GitHub de la PR #931 : **verte** (plugin, control-panel, web-api, scripts, artefacts, sur les deux exécutions) ; fusionnée dans `chore/257-ci-github-actions` en `2eee378b`.

## Tests manuels à effectuer
TC-448 — `PENDING MANUAL VALIDATION` (après déploiement autorisé) : rouvrir une version du botaniste → « Vérifier » :
plus de refus de registre, verdict sur les vrais défauts ; aperçu du toit de serre en gradins de verre.
Aucun test Minecraft ni PlugAdmin réel n'a été fait par Claude.

## Résultat attendu
Un `gable` de verre (ou de tout bloc sans escalier) est constructible ; la boucle de correction ne consomme plus
d'appels sur un refus que le modèle ne peut pas corriger.

## Reset / retour à l'état initial
Revenir avant `bbf85b2a` / `b588defc` (revert des deux commits).

## Déploiement VeryGames
### À transférer
JAR RPGQuest construit depuis la branche d'intégration, avec les scripts officiels (`deploy-verygames.sh -y`,
`RPGQUEST_TEST_MAX_HEAP=768m`, après CI verte du commit de fusion) ; PlugAdmin via `scripts/plugadmin/deploy.sh`.
**Non fait : attend l'accord écrit du propriétaire.**
### Ne PAS transférer/altérer
`data.db`, configurations, mondes, contenus ; projets de l'Atelier.
### Redémarrage requis
Oui pour le JAR (redémarrage DEV).
### Migration automatique
Aucune.

## Rollback
JAR précédent sauvegardé par le script (en ligne avant ce lot : `c5f1c323`), PlugAdmin `92d5c03d`
(`/opt/plugadmin/releases`).

## Logs / diagnostic
Journal `ai_journal_event` du projet `14d85d40-5e68-49af-ba20-e62b69bf55db`, séquences 1–17 (copie lecture seule).

## Documentation mise à jour
Bible (`gable`), TC-448, changelog serveur (entrée « NON DÉPLOYÉ »), registre de suivi DEV, index des rapports.

## Limitations / travail restant
* `stairRun` (instruction `stairs`) applique encore un état d'escalier quel que soit le matériau : même famille de
  défaut, non signalée, non modifiée (hors périmètre ; le registre le refuse toujours clairement).
* Le plan du botaniste reste refusé pour ses propres défauts (serre imbriquée dans le volume principal) : c'est au
  propriétaire de relancer une amélioration s'il le souhaite (appel payant).
* La détection de gradins est géométrique : un toit plat contigu à un volume exactement un bloc plus bas peut compter
  une rangée comme « pente » (seuil de 40 % des colonnes inchangé).

## Prochaine étape suggérée
Accord écrit du propriétaire pour déployer JAR + PlugAdmin sur DEV, puis TC-448.
