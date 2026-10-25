# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-25
* Heure : 02:33 (CET)
* Sujet : #828 — UX Atelier : identifier et retrouver directement les versions de plan dans l'historique
* Statut : DONE (livré et déployé sur PlugAdmin ; validation propriétaire TC-432 en attente)
* Branche Git : `feature/828-atelier-historique-versions` (PR #854, fusionnée dans `chore/257-ci-github-actions`), rapport sur `docs/828-rapport`
* Commit actuel si disponible : fusion `5b5af556`
* Début de la tâche : 2026-10-25 02:53:57 (CEST, UTC+2)
* Fin de la tâche : 2026-10-25 02:33:41 (CET, UTC+1 — passage à l'heure d'hiver à 03:00 CEST pendant la session)
* Durée totale : 00:39:44 (calculée en UTC depuis 00:53:57)

## Demande
Ticket #828 (constat propriétaire du 24/10) : dans PlugAdmin > Atelier > « TEST #747c église », l'historique ne montrait que date, provenance et « Reprendre cette version » ; impossible de retrouver V-8776ad09 pour TC-428 sans ouvrir les versions une à une. Attendu : ID court copiable, statut d'audit réel (Conforme / Avertissements acceptables / Bloqué, codes, explication), recherche par ID ou lien profond, version absente expliquée, version affichée mise en évidence, aucune IA / enregistrement / pose, responsive, tests des états, TC-428 avec lien exact. Hors périmètre : modifier versions, plans, modèle IA, constructions.

## Analyse
- Rendu : `WorkshopPages.draftList` (liste simple `<ul class="tight">`).
- Statut par version : `BuildingPlanWorkbench.state(draft, AUDIT)` (dernier audit lié, marqué périmé s'il porte sur un texte antérieur) ; « contournable » = clé `acceptable` du détail d'audit (#747), relue par `AcceptableDefects.of(details)`.
- Ouverture d'une version : `?id=<projet>&draft=<id complet>` ; un `draft` inconnu retombait silencieusement sur la version par défaut.
- Forme courte `V-` + 8 caractères déjà employée par les cartes d'échec/stagnation (#747).
- Base DEV (lecture seule, `mode=ro`) : V-8776ad09 (ENGINE_REPAIR) et V-007bcdcc (AI_CORRECTION) existent, uniques, dans le projet `3f71dd09…` « TEST #747c église (ne pas utiliser) ».

## Travail effectué
- `PlanVersionRef` : libellé court, normalisation (avec/sans `V-`, sans casse, `[0-9a-f-]{4,36}` seulement — aucun joker SQL), résolution par préfixe unique, messages « introuvable » / « plusieurs versions ».
- `PlanVersionStatus` : Conforme / Avertissements acceptables / Bloqué / Vérification en cours / Non vérifiée, codes, phrase (`PlanVerdictText`), audit sur texte antérieur signalé. `PLAN_INVALID` reste Bloqué (pas de clé `acceptable`).
- `BuildingPlanDraftStore.byIdPrefix` (lecture) pour le lien profond sans projet.
- `PanelApp` : `?draft=V-…` sans `id` → redirection vers le projet contenant la version (#ws-history), sinon retour à l'Atelier avec le motif ; `?id=…&draft=V-…` résout l'ID court ; version absente → bandeau d'erreur explicite ; audits de chaque version passés à la vue (`ProjectView.versionAudits`).
- `WorkshopPages` : titre « affichée : V-… », formulaire GET « Aller à une version », chaque ligne = ID copiable, date, provenance, pastille de statut, codes + explication, « Reprendre V-… » (aria-label), « Copier le lien » ; version affichée encadrée, `aria-current`.
- `panel.js` : `[data-copy-url]` copie l'adresse complète (origine + chemin). `plugadmin.css` : styles `ws-version*`, mobile pleine largeur.
- Aucune écriture : la consultation ne crée ni action agent, ni appel IA (testé).

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/PlanVersionRef.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/PlanVersionStatus.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/building/PlanVersionRefTest.java`
- ce rapport

## Fichiers modifiés
- `control-panel/.../building/BuildingPlanDraftStore.java`, `.../web/PanelApp.java`, `.../web/WorkshopPages.java`
- `control-panel/src/main/resources/assets/panel.js`, `plugadmin.css`
- `control-panel/src/test/.../web/WorkshopFlowTest.java` (nouveau test ; `theDiagnosticIsCopyableAndCarriesNoSecret` vise désormais le bouton « Copier le diagnostic », l'historique portant aussi des `data-copy`)
- `docs/RPGQUEST_BIBLE.md`, `docs/control-panel/BATIMENTS_PARCOURS.md`, `docs/MANUAL_TEST_PLAN.md` (TC-432 nouveau, TC-428 étapes 1 et 5 avec liens exacts), `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Lecture seule de `building_plan_draft` (nouvelle requête `LIKE` sur un préfixe validé).

## Configuration / données
Aucune. Aucune version, aucun plan ni projet modifié.

## Tests automatiques
- Local : `:control-panel:test --tests '*PlanVersionRefTest' --tests '*WorkshopFlowTest'` → vert (1er passage : 2 échecs de test corrigés — libellé de pastille et `data-copy` du diagnostic).
- CI GitHub : PR #854 verte (plugin, control-panel, web-api, scripts, artefacts) ; commit de fusion `5b5af556` vert.

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — TC-432 (consultation seule), puis reprise de TC-428 (#747). Claude n'a pas ouvert la page dans un navigateur connecté.

## Résultat attendu
`/buildings/workshop/project?draft=V-8776ad09` ouvre « TEST #747c église » sur V-8776ad09, encadrée, « Avertissements acceptables » `UNSUPPORTED_BLOCK` ; V-007bcdcc s'affiche « Bloqué » `PLAN_INVALID`.

## Reset / retour à l'état initial
Rien à réinitialiser (aucune donnée écrite).

## Déploiement VeryGames
### À transférer
Rien : changement PlugAdmin seul. PlugAdmin déployé sur AWS par `scripts/plugadmin/deploy.sh` (validation = CI verte sur `5b5af55`) le 25/10 02:32 CET.
### Ne PAS transférer/altérer
JAR DEV inchangé (`12484eb5`), aucun contenu.
### Redémarrage requis
Non pour Minecraft. `plugadmin.service` redémarré par le script (le `/health` à 2 s a répondu KO — course connue ; recontrôlé ONLINE, classes `PlanVersionRef`/`PlanVersionStatus` et CSS `ws-version` présentes dans le JAR servi, route `?draft=V-8776ad09` → 303 /login).
### Migration automatique
Aucune.

## Rollback
`scripts/plugadmin/rollback.sh app` (release précédente `12484eb5` sous `/opt/plugadmin/releases/`).

## Logs / diagnostic
`~/deploy-828.log`, `~/build-828.log`.

## Documentation mise à jour
Bible (historique #828), BATIMENTS_PARCOURS, MANUAL_TEST_PLAN (TC-432, TC-428), registre DEV.

## Limitations / travail restant
- Lien copié = adresse complète via JS ; sans JS, le chemin relatif est copié par l'infobulle seulement.
- Le statut reflète le dernier audit enregistré ; une version vérifiée avant #747 (sans clé `acceptable`) apparaît « Bloqué » même si un nouvel audit la rendrait « Avertissements acceptables » (TC-428 étape 1 prévoit de revérifier si besoin — geste gratuit).

## Prochaine étape suggérée
Propriétaire : TC-432 puis TC-428 avec le lien exact.
