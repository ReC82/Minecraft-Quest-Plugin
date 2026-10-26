# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-26
* Heure : 15:08
* Sujet : #881 — Atelier : rechercher, filtrer et supprimer les projets non utilisés (TC-438)
* Statut : DONE (livré et déployé sur PlugAdmin ; validation propriétaire en attente, ticket laissé ouvert)
* Branche Git : `feature/881-atelier-recherche-suppression` (PR #883, fusionnée dans `chore/257-ci-github-actions`) ; rapport sur `docs/881-rapport`
* Commit actuel si disponible : merge `077802d2`
* Début de la tâche : 2026-10-26 13:54:52
* Fin de la tâche : 2026-10-26 15:08:14
* Durée totale : 01:13:22

## Demande
Ticket #881 (session lqorch autonome) : dans l'Atelier de création, ajouter recherche, filtres et tri de la
liste des projets, un indicateur toujours visible « Utilisé en jeu » / « Non utilisé », et une suppression
sûre d'un projet non utilisé — désactivée dans l'interface ET refusée côté serveur si le projet est utilisé ou
si l'usage est incertain, sans dupliquer le parcours de suppression Bibliothèque de #644. Autorisation :
conception et code avec tests isolés ; aucune opération réelle sur DEV, aucun appel IA payant, ticket laissé ouvert.

## Analyse
- Un projet Atelier (`building_project` dans la base du panel) est distinct du bâtiment de la bibliothèque
  (fichiers côté plugin) et de ses exemplaires posés (placements, relevé `building.site.list`). Les données
  d'un projet sont toutes dans la base du panel : versions de plan (`building_plan_draft` + révisions, liens
  de vérification, jetons), conceptions détaillées, conceptions automatiques, images de référence.
- L'usage réel se lit sans requête sur les derniers relevés : placements de `building.site.list` (déjà
  utilisés par le refus de retrait #284/#644), portails de `portal.list` (champ `building`), état
  Bibliothèque `CatalogState` (#290).
- Supprimer un projet ne touche donc jamais le serveur. Le risque est ailleurs : faire croire qu'un bâtiment
  publié a disparu, ou laisser une réponse de vérification orpheline. D'où la règle retenue : suppression
  permise seulement si le bâtiment n'est **ni posé, ni lié à un portail, ni publié** dans la bibliothèque,
  si un relevé des emplacements existe, et si aucun geste n'est en cours. Un bâtiment publié renvoie vers la
  fiche Bibliothèque (#644), seule à pouvoir supprimer une définition. Un bâtiment retiré (#284) ou purgé
  (#644) n'empêche pas la suppression du projet : la Bibliothèque n'offre de toute façon pas de purge pour un
  bâtiment retiré, et ses versions archivées restent côté serveur.
- La présence en bibliothèque n'est pas assimilée à « utilisé » (exigence du ticket) : elle bloque la
  suppression, mais le libellé d'usage reste « Non utilisé ».

## Travail effectué
- `ProjectUsage` : classification Utilisé en jeu / Non utilisé / Utilisation à vérifier (aucun relevé des
  emplacements, ou bâtiment disparu du relevé de la bibliothèque sans retrait connu), et liste des motifs de
  refus en français.
- `WorkshopProjectDeletion` : aperçu (nombres réels) et suppression en **une transaction** ; la fiche est
  effacée d'abord avec contrôle de la version vue (`STALE`), un second clic donne `NOT_FOUND`, une
  conception automatique en cours donne `BUSY` (revérifié dans la transaction).
- `WorkshopProjectQuery` : `q` (nom, nom technique, description, identifiant ; casse/accents/espaces
  ignorés), `filtre` (tous, brouillons, bibliotheque, utilises, non_utilises, a_verifier, a_corriger), `tri`
  (recents, anciens, nom) — paramètres GET, sans JavaScript, puces avec compteur.
- `WorkshopPages` : barre de recherche, puces de filtres, tuiles restructurées (titre lien, pastille
  d'usage avec glyphe, nombre d'emplacements, état Bibliothèque, « Voir où utilisé »), message « Aucun projet
  ne correspond… » + « Effacer la recherche et les filtres » ; zone repliée « Supprimer le projet — <usage> »
  sur la fiche (ce qui est effacé / conservé, motifs, case de confirmation, bouton rouge seulement si permis).
- `PanelApp` : calcul de l'usage (relevé des emplacements lu une fois par page), geste `project_delete`
  (permission `BUILDING_WRITE`, case, revérification complète, audit `building.project.delete` OK/DENIED),
  second clic sur un projet déjà supprimé → message neutre. `AgentPages.portalsUsingBuilding`.
- CSS : styles de la barre et des tuiles, correction du chevauchement icône/texte de recherche.

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/ProjectUsage.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/WorkshopProjectDeletion.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/WorkshopProjectQuery.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/building/WorkshopProjectDeletionTest.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/WorkshopProjectCleanup881Test.java`
- ce rapport

## Fichiers modifiés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/{PanelApp,WorkshopPages,AgentPages}.java`
- `control-panel/src/main/resources/assets/plugadmin.css`
- `docs/RPGQUEST_BIBLE.md`, `docs/control-panel/BATIMENTS_PARCOURS.md`, `docs/MANUAL_TEST_PLAN.md` (TC-438),
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune migration. Tables existantes ; des lignes ne sont effacées que sur clic d'un administrateur.

## Configuration / données
Aucune. Aucun projet, version, bâtiment, emplacement, portail ou snapshot réel n'a été supprimé ni modifié.
Lecture seule de la base du panel en production pour préparer la recette : le relevé `building.site.list`
du 24/10 18:02 UTC montre 8 bâtiments posés, dont `test_eglise_747c` (projet « TEST #747c église ») sur
`buildsite_0016` ; « Église du hub » (`eglise_du_hub`) n'y est posée nulle part.

## Tests automatiques
- Nouveaux : `WorkshopProjectCleanup881Test` 10/10 (HTTP réel : recherche accent/casse, description et
  identifiant, filtres + recherche combinés, tri nom/anciens, message sans résultat ; tuiles ; suppression
  d'un brouillon non utilisé avec versions, image et conception, autre projet intact, aucune action agent
  créée, double clic ; version périmée ; refus serveur par requête forgée si posé, publié, sans relevé,
  portail, vérification en attente, rôle Éditeur de contenu sans `BUILDING_WRITE`) ;
  `WorkshopProjectDeletionTest` 3/3 (portée, STALE, BUSY, règles d'usage).
- Non-régression locale : `WorkshopFlowTest` 53, `BuildingSitesPageTest` 39, `PortalsPageTest` 26 — 131/131.
- CI GitHub verte sur la PR #883 et sur le commit de fusion `077802d2` (plugin, panel, web-api, scripts, artefacts).
- QA visuelle : instance de test temporaire (test JUnit non commité) + Playwright Chromium, 1280 et 390 px :
  liste, recherche, filtre + tri, sans résultat, zone de suppression permise et refusée ; aucun défilement horizontal.

## Tests manuels à effectuer
TC-438 — PENDING MANUAL VALIDATION (`docs/MANUAL_TEST_PLAN.md`). Uniquement sur un projet d'essai « Essai 881 ».

## Résultat attendu
Liste filtrable et cherchable ; « TEST #747c église » marqué « Utilisé en jeu » sans bouton de suppression ;
un projet d'essai non publié et non posé se supprime après la case, sans effet sur la bibliothèque ni le monde.

## Reset / retour à l'état initial
Rien à réinitialiser. Une suppression de projet faite par le propriétaire n'est pas réversible ; les plans
enregistrés ou vérifiés restent importables depuis l'historique des actions (#308).

## Déploiement VeryGames
Aucun déploiement VeryGames (JAR inchangé).

### À transférer
Rien côté Minecraft. PlugAdmin `077802d2` déployé sur AWS le 26/10 à 15:07 (`scripts/plugadmin/deploy.sh
--wait-ci`, attente de la CI du commit de fusion).

### Ne PAS transférer/altérer
`control-panel.db` (projets réels), bâtiments, emplacements, portails.

### Redémarrage requis
Service `plugadmin` redémarré par le script. Aucun redémarrage Minecraft.

### Migration automatique
Aucune.

## Rollback
`scripts/plugadmin/rollback.sh app` (release précédente `a034f14a` sous `/opt/plugadmin/releases/`).

## Logs / diagnostic
Le script a affiché `/health KO` (course connue juste après le redémarrage) ; contrôle immédiat :
`{"panel":"ONLINE"}`, service actif, `DEPLOYED` = `077802d2`, classes nouvelles dans le JAR servi,
`/buildings/workshop?q=eglise` → 303 /login. Journal du déploiement :
`~/.local/state/lodyquests-orch/deploy-881.log`. Audit des gestes : `building.project.delete`.

## Documentation mise à jour
Bible (Atelier : liste des projets et suppression), parcours Bâtiments, TC-438, changelog serveur, registre
de validation DEV (ligne #881 + dépendance), index des rapports.

## Limitations / travail restant
- L'usage repose sur les **derniers relevés** (emplacements, portails, bibliothèque) : un relevé ancien peut
  être en retard sur le monde — rafraîchir avant de nettoyer (relevé des emplacements absent = suppression bloquée ;
  relevé des portails absent = pas de blocage par portail, cohérent avec l'exigence d'un bâtiment non publié).
- Deux projets portant le même nom technique partagent son usage (choix prudent).
- Pas d'action « Archiver/Masquer » distincte (optionnelle dans le ticket) : non ajoutée.
- L'immuabilité des versions utilisées côté Bibliothèque (exigence #644 du 26/10) reste à faire dans #644.

## Prochaine étape suggérée
Recette TC-438 par le propriétaire ; puis #644 (versions utilisées immuables, audit lecture seule de l'église).
