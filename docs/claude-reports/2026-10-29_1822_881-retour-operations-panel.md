# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-29
* Heure : 18:22
* Sujet : #881 — TC-442 échec partiel : opérations de l'Atelier invisibles dans la cloche et l'Historique
* Statut : DONE (livré sur la branche d'intégration, non déployé)
* Branche Git : `fix/881-retour-operations-panel` → `chore/257-ci-github-actions` (PR #914, merge `94c8c4cf`)
* Commit actuel si disponible : `73defbc1` (code + doc), merge `94c8c4cf`
* Début de la tâche : 2026-10-29 17:56:10
* Fin de la tâche : 2026-10-29 18:24:00
* Durée totale : 00:27:50

## Demande
Retour propriétaire du 29/10 16:53 UTC (TC-442) : corbeille visible OK, mais la suppression d'`essai_881` et
la mise à la corbeille puis la suppression définitive d'un autre projet ne laissent aucune trace visible
dans les notifications ni dans l'historique consulté. Audit en lecture seule, cause établie, retour d'action
visible et fiable, sans nouveau projet, suppression, restauration ni déploiement.

## Analyse
Audit en lecture seule sur une copie `.backup` de la base live (17:56, `integrity_check` ok, hors dépôt) :

| ts (UTC) | acteur | action | résultat | cible |
|---|---|---|---|---|
| 16:51:30 | owner | building.project.delete | OK (DELETED) | `test_cabane_747d` « TEST #747d cabane (ne pas utiliser) » |
| 16:50:48 | owner | building.project.trash | OK (DONE) | même projet |
| 13:15:37 | owner | building.project.delete | OK | `essai_881` |

**L'exécution n'a pas échoué et l'audit n'a rien perdu.** L'onglet `/actions?status=panel` lit bien
`audit.recent(1000)` filtré sur `building.project.*` (ces lignes sont les plus récentes, donc dedans). Cause :
- la **cloche** (`NotificationCenter`) ne lit que `agent_action` : une opération du panel seul n'y apparaît jamais ;
- l'onglet **par défaut « Tous »** de `/actions` ne liste aussi que les actions agent ; les opérations sont
  dans l'onglet séparé « Opérations du panel », sans rien qui y renvoie.

Pages réellement ouvertes (journal `plugadmin`, 17:48–17:56 heure locale) : `/home`, 6 × `/actions`,
`/buildings/workshop`, `/trash`, les POST corbeille et suppression (303). La query n'est pas journalisée :
**l'onglet de `/actions` consulté n'est pas prouvable** ; l'explication la plus probable est l'onglet « Tous ».

## Travail effectué
- Cloche : section « Opérations du panel (Atelier) » sous « Dernières actions », hors de `data-notif-list`
  (que `panel.js` réécrit), avec les 3 derniers retraits (corbeille, restauration, suppression définitive —
  pas les créations ni les enregistrements, trop fréquents), « Fait / Refusé », heure, acteur, et le lien
  « Voir les opérations du panel ».
- `/actions` onglet « Tous » : bandeau qui dit que ces opérations ne figurent pas dans la liste, la dernière en
  date, et le lien « Voir les opérations du panel (N) ».
- Messages de succès corbeille / restauration / suppression : « Trace : Historique › onglet « Opérations du panel » ».
- Source unique `PanelApp.panelOperations(journal, max)` partagée par l'Historique et la cloche.

## Fichiers créés
- ce rapport.

## Fichiers modifiés
- `control-panel/.../web/NotificationCenter.java`, `ActionsPages.java`, `PanelApp.java`, `assets/plugadmin.css`
- `control-panel/src/test/.../WorkshopProjectCleanup881Test.java` (+1 test, une assertion bornée à `<main>`)
- `docs/RPGQUEST_BIBLE.md`, `docs/control-panel/BATIMENTS_PARCOURS.md`, `docs/MANUAL_TEST_PLAN.md` (TC-444),
  `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune migration, aucune écriture. Lecture de `audit_log` uniquement.

## Configuration / données
Aucune. Aucun projet créé, retiré, restauré ou supprimé. Aucun appel IA.

## Tests automatiques
- Local (heap 768m) : `WorkshopProjectCleanup881Test`, `*Notification*`, `*Actions*` — 22 classes vertes.
- CI PR #914 : Tests control-panel, plugin, web-api, Scripts, Artefacts, CI OK — verts.

## Tests manuels à effectuer
TC-444 — PENDING MANUAL VALIDATION, en lecture seule, après déploiement PlugAdmin autorisé : cloche →
section « Opérations du panel (Atelier) » avec « Supprimé définitivement TEST #747d cabane… » ; son lien ouvre
l'onglet ; `/actions` (Tous) montre le bandeau ; mobile 390 px.

## Résultat attendu
Une corbeille ou une suppression faite dans l'Atelier est visible dans la cloche et signalée depuis
l'onglet par défaut de l'Historique.

## Reset / retour à l'état initial
Revert du merge `94c8c4cf` ; aucune donnée à restaurer.

## Déploiement VeryGames
**Non déployé** (non autorisé par le retour du 29/10). À transférer quand autorisé : PlugAdmin seul via
`scripts/plugadmin/deploy.sh` (sauvegarde `.backup` de `control-panel.db` avant). JAR Minecraft non
concerné, pas de redémarrage Minecraft, aucune migration. Rien à ajouter au `SERVER_CHANGELOG` tant que non
déployé.

## Rollback
Release PlugAdmin précédente (`12365fb5`) via les releases conservées par `deploy.sh`.

## Logs / diagnostic
Copie de la base live lue hors dépôt (supprimée en fin de session) ; journal systemd `plugadmin` (chemins).

## Documentation mise à jour
Bible (§ historique de l'Atelier), parcours bâtiments, TC-444, registre de validation.

## Limitations / travail restant
- L'onglet consulté par le propriétaire n'est pas prouvable (query non journalisée).
- Réserves de la session précédente non traitées : ligne « Projet créé » sans nom pour les entrées d'avant
  #881 ; défilement horizontal des colonnes sur téléphone dans l'onglet.

## Prochaine étape suggérée
Autoriser le déploiement PlugAdmin de `94c8c4cf`, puis TC-444 en lecture seule.
