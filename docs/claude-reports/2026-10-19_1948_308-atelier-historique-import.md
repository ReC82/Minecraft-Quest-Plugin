# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-19
* Heure : 19:48
* Sujet : #308 — Atelier bâtiments : historique du projet isolé, import d'un plan d'ailleurs explicitement distinct
* Statut : DONE (livré, PlugAdmin déployé sur DEV et vérifié en lecture) — validation PlugAdmin par le propriétaire `PENDING MANUAL VALIDATION` (TC-366)
* Branche Git : `fix/308-workshop-history-import` (PR #601) fusionnée dans `chore/257-ci-github-actions` ; rapport : `docs/308-rapport`
* Commit actuel si disponible : merge `fb06e63` (code `aa94a5f`)
* Début de la tâche : 2026-10-19 19:17:41
* Fin de la tâche : 2026-10-19 19:52:30
* Durée totale : 00:34:49

## Demande
Ticket #308 (session autonome lqorch) : sur un projet neuf (« Estrade d'Elias », `d2ebe398…`),
« Détails techniques → Reprendre un plan depuis l'historique des actions » affichait
`bibliotheque_du_hub` ; le propriétaire le lisait comme un historique déjà rempli par un autre
bâtiment. Attendu : historique du projet limité à ses versions (état vide explicite), import d'anciens
plans dans une action distincte, repliée, avec recherche et provenance, examen avant import (ID, nom,
dimensions, orientation, incompatibilités), import = copie sans déplacer la source ni transférer brief
ou conception, contrôles côté serveur, aucune facturation.

## Analyse
* Aucun développement récent ne traitait déjà ce point (derniers commits bâtiments : #472, #299, #301, #246).
* **Stockage non contaminé** : `BuildingPlanDraftStore.forProject` filtre déjà par `agent_id` et
  `project_id` ; contrôle en lecture de la base DEV : les 9 versions actives de l'Estrade d'Elias
  portent toutes `id: hub_estrade_elias` (6 corrections IA, 2 propositions, 1 réparation moteur), aucune
  reprise d'action.
* **Cause réelle (présentation)** : `BuildingPlanWorkbench.recoverable()` liste les actions **de tout
  l'agent** qui portent un plan et n'appartiennent à aucun brouillon. Cette liste globale était rendue
  dans la fiche du projet, sous un libellé « historique », sans provenance. Les « Versions du plan »
  du projet n'étaient affichées qu'à partir de 2 versions : un projet neuf n'avait donc aucun
  historique visible, seulement la liste globale.
* **Défauts secondaires** dans `planRecover` : une action déjà portée par un ancien brouillon **sans
  projet** rattachait ce brouillon au projet courant (déplacement silencieux) ; une action d'un autre
  projet renvoyait vers cet autre projet au lieu d'importer ; aucun examen préalable.

## Travail effectué
* **Fiche projet** (`WorkshopPages`) :
  * « **Historique de ce projet — versions du plan (n)** » toujours présent ; projet neuf → « Aucune
    version de plan pour ce projet : il est neuf. Les plans d'autres projets ne s'affichent jamais ici. »
  * Nouvelle section distincte, **repliée par défaut**, « **Importer un plan d'un autre projet ou de
    l'historique global** » (sortie de « Détails techniques ») : texte qui dit que ces plans
    n'appartiennent pas au projet, recherche (nom technique, nom, identifiant d'action), liste avec
    « Examiner avant import », champ « identifiant d'action » pour un plan d'un autre projet ou un
    ancien brouillon sans projet (récupération legacy explicite).
  * **Examen avant import** (`?import=<action>`, GET, aucune écriture) : provenance (historique
    global / projet « X » / ancien brouillon sans projet / déjà dans ce projet), action source, nom
    technique, nom, dimensions, entrée, limites du projet, incompatibilités (emprise, hauteur, entrée,
    nom technique) ; bouton « Importer comme nouvelle version de ce projet » + « Annuler ».
* **Import** (`PanelApp.planRecover`) : sans `confirm=1` → renvoi vers l'examen ; confirmé →
  **toujours une copie** (origine RECOVERED, `source_action_id`), aucun brief ni conception transférés,
  source jamais déplacée ni modifiée, l'action n'est rattachée à la copie que si elle n'appartenait à
  aucun brouillon (sinon le verdict reste à la source), idempotent **par projet**
  (`BuildingPlanDraftStore.recoveredInto`). Audit `building.plan.draft.recover` enrichi de
  `source_draft` / `source_project`. Contrôles serveur : `AI_USE`, projet et action du même agent.
* **Fiche d'action** : « Examiner l'import dans ce projet » (mène au même examen).
* Nouveau `PlanImportPreview` (lecture tolérante des en-têtes du plan, provenance, incompatibilités).

## Fichiers créés
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/PlanImportPreview.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/building/PlanImportPreviewTest.java`
* `docs/claude-reports/2026-10-19_1948_308-atelier-historique-import.md`

## Fichiers modifiés
* `control-panel/.../building/BuildingPlanDraftStore.java` (`recoveredInto`)
* `control-panel/.../building/BuildingPlanWorkbench.java` (`recoverable` avec recherche)
* `control-panel/.../web/PanelApp.java` (examen, import par copie)
* `control-panel/.../web/WorkshopPages.java` (historique, section import, examen, libellés)
* `control-panel/src/test/.../web/WorkshopFlowTest.java`
* `docs/RPGQUEST_BIBLE.md`, `docs/control-panel/BATIMENTS_PARCOURS.md`, `docs/MANUAL_TEST_PLAN.md` (TC-366)
* `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune migration. Nouvelle requête en lecture seule sur des colonnes existantes. Aucune donnée DEV
modifiée (lectures `sqlite3 -readonly` seulement).

## Configuration / données
Aucune. Aucun projet, brouillon, bâtiment, emplacement ni action touché.

## Tests automatiques
* Local (ciblé, `RPGQUEST_TEST_MAX_HEAP=768m`) : `PlanImportPreviewTest` 4/4, `WorkshopFlowTest` 35/35
  (+4 nouveaux : projet neuf/historique vide/import replié avec redémarrage ; examen sans écriture ;
  import depuis un autre projet = copie, source et verdict intacts, idempotent, brief gardé, 0 appel IA ;
  plan d'un autre agent refusé côté serveur), `BuildingPlanDraftStoreTest` 9/9 — 48 tests, 0 échec.
* 2 tests existants adaptés (non affaiblis) : la reprise passe désormais par la confirmation explicite
  `confirm=1` ; le test de la fiche d'action vérifie en plus que rien n'est copié sans confirmation.
* CI GitHub verte sur la PR #601 et sur le commit de fusion `fb06e63`.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — **TC-366** (5 min, PlugAdmin seulement, aucun appel IA) : Estrade
d'Elias → historique « il est neuf »/ses seules versions, import replié, recherche `biblio`, « Examiner
avant import » sans création, Maison de Lily limitée à ses versions. Voir `docs/MANUAL_TEST_PLAN.md`.
Remarque : l'Estrade d'Elias a aujourd'hui 9 versions à elle ; l'historique affichera donc ces 9
versions (toutes `hub_estrade_elias`), pas l'état « neuf ».

## Résultat attendu
Un projet ne montre comme historique que ses propres versions ; un plan venu d'ailleurs n'entre dans un
projet que par un import examiné et confirmé, sous forme de copie traçable.

## Reset / retour à l'état initial
Une copie importée par erreur : Détails techniques → « Retirer cette version de la liste » (archivage,
rien d'effacé).

## Déploiement VeryGames
### À transférer
Rien sur VeryGames. PlugAdmin seul (AWS) via `scripts/plugadmin/deploy.sh --wait-ci` : fait le
19/10 à 19:48, `DEPLOYED` = `fb06e63`, validation « CI verte sur fb06e63 ».
### Ne PAS transférer/altérer
JAR du plugin (inchangé, toujours `67a556c`), `data.db`, contenus, mondes.
### Redémarrage requis
Minecraft : non. PlugAdmin : redémarré par le script (dernière activité propriétaire sur le panel 36 min avant).
### Migration automatique
Aucune.

## Rollback
`scripts/plugadmin/rollback.sh app` (release précédente conservée sous `/opt/plugadmin/releases/`).

## Logs / diagnostic
Vérifié servi : `PlanImportPreview*.class` présents dans `/opt/plugadmin/app/lib/control-panel-0.1.0-SNAPSHOT.jar`,
chaîne « Historique de ce projet » dans `WorkshopPages.class`, `/buildings/workshop/project?…&import=…`
répond 303 → `/login` (route enregistrée), `/health` ONLINE. Rendu navigateur non capturé dans cette
session : validation visuelle par le propriétaire (TC-366).

## Documentation mise à jour
Bible (Atelier de création + tableau des gestes), `BATIMENTS_PARCOURS.md` (sections + garde-fous de
tests), `MANUAL_TEST_PLAN.md` (TC-366), registre `SUIVI_VALIDATIONS_DEV.md` (ligne 7e « À tester » ;
report préalable depuis `main` de la section « Revue #360 » et du relevé #542 détaillé), index des rapports.
Pas d'entrée `SERVER_CHANGELOG.md` : rien ne change sur le serveur Minecraft.

## Limitations / travail restant
* L'import depuis un autre projet passe par l'identifiant d'action (fiche d'action ou champ) : la liste
  recherchable ne montre que les plans orphelins de l'historique global, comme avant.
* Les projets sont partagés par agent (pas de propriété par utilisateur) : l'autorisation reste la
  permission `AI_USE`, comme pour tous les gestes de brouillon.
* Ticket laissé ouvert (validation propriétaire).

## Prochaine étape suggérée
Propriétaire : TC-366 étapes 1 à 3 (5 minutes, PlugAdmin).
