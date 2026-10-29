# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-29
* Heure : 17:06
* Sujet : #881 — Atelier : corbeille réversible, historique visible des suppressions, origine distincte de l'usage (retour propriétaire TC-438 du 29/10)
* Statut : DONE (livraison technique) — non déployé, en attente du feu vert du propriétaire
* Branche Git : `feature/881-atelier-corbeille` → fusionnée dans `chore/257-ci-github-actions` (PR #906)
* Commit actuel si disponible : `12365fb5` (merge PR #906 ; commits `00e49241` code, `b4550b4f` docs)
* Début de la tâche : 2026-10-29 16:31:57
* Fin de la tâche : 2026-10-29 17:06:43
* Durée totale : 00:34:46

## Demande

Session autonome lqorch (sélection automatique #836, nouvelle activité du propriétaire). Commentaires du
propriétaire du 29/10 sur #881 :

1. la suppression d'« Essai 881 » **n'apparaît pas dans l'Historique des actions** malgré l'audit annoncé :
   auditer la persistance **et** l'affichage ;
2. le besoin réel est de **désencombrer l'Atelier** : section « Supprimés / Corbeille » réversible, hors liste
   principale, restauration et suppression définitive séparée et fortement confirmée ; journaliser qui / quand /
   quoi (suppression, restauration, refus) avec liens ; dire si Essai 881 est récupérable, sans le recréer ;
3. sécurité des dépendances : ne pas confondre nom de l'emplacement, nom du projet et bâtiment posé
   (« Bibliothèque du Hub » vs `la_grande_librairie` sur `buildsite_0009`).

Contraintes : aucune suppression / archivage / mutation de projet existant sur DEV, tests isolés d'abord,
accord explicite pour toute migration ou opération réelle. Le label `agent:auto` n'autorise pas de déploiement.

## Analyse

* **Persistance** : relu en **lecture seule** dans `/var/lib/plugadmin/control-panel.db` (`sqlite3 -readonly`,
  aucune écriture) : `audit_log` contient bien `2026-10-29T13:15:37Z owner building.project.delete
  project=9ceb167b… building=essai_881 OK DELETED`, et la création à 13:11. **La persistance fonctionne.**
* **Affichage** : `/actions` (`ActionsPages`) ne lisait que la table `agent_action` et, depuis #169, les refus
  `agent.action.create` de l'audit. Aucune page ne montrait les opérations `building.project.*`, faites par le
  panel seul. C'est la cause du constat 1.
* **Essai 881 récupérable ?** **Non.** La suppression #881 était un `DELETE` en une transaction de toutes les
  lignes du projet ; la seule sauvegarde de la base du panel sur la machine
  (`control-panel.db.bak-256-20261012-120515`) est antérieure à sa création. Il n'a pas été recréé.
* **Corbeille sans migration** : la colonne `building_project.archived` existe depuis #267 ; la méthode
  `archive()` qui la posait n'était appelée nulle part, et sur DEV les 17 projets ont `archived = 0` (relu en
  lecture seule). Toutes les lectures de projet filtrent déjà `archived = 0` (liste, fiche, gestes, images,
  bibliothèque). Elle peut donc porter la corbeille **sans migration ni réécriture**.
* **Origine** : le projet « Bibliothèque du Hub » (`bibliotheque_du_hub`) a `source_site_id = buildsite_0009` :
  il a été **repris** de cet emplacement (#267), qui porte aujourd'hui `la_grande_librairie` v1. L'usage est
  calculé sur l'identifiant de bâtiment (correct : `bibliotheque_du_hub` n'est posé nulle part), mais la fiche ne
  disait rien de ce lien d'origine, d'où la confusion possible.

## Travail effectué

* **Fiche projet** : la zone « Supprimer le projet » devient **« Mettre à la corbeille — <usage> »** (ancre
  `#supprimer` conservée) : rien n'est effacé, le projet quitte la liste avec tout ce qu'il possède. Mêmes refus
  serveur qu'avant (posé, portail, publié, usage incertain, geste en cours, rôle sans `BUILDING_WRITE`, version
  vue). `project_delete` depuis la fiche est désormais **refusé** (« Mettez d'abord le projet à la corbeille »).
  La fiche d'un projet à la corbeille redirige vers la corbeille.
* **Corbeille** `/buildings/workshop/trash` (bouton « Corbeille (N) » en tête de l'Atelier) : par projet, qui et
  quand, nombre de versions, usage, origine ; **« Restaurer dans l'Atelier »** ; replié, **« Supprimer
  définitivement… »** avec la liste de ce qui serait effacé / conservé, **identifiant du bâtiment à recopier** +
  case. Les refus d'usage sont revérifiés (un projet devenu posé entre-temps n'est pas supprimable).
* **Store** : `BuildingProjectStore.trash / restore / trashed` (version avancée à chaque passage, contrôle de
  version vue, `TrashOutcome DONE / STALE / NOT_FOUND`) ; `archive()` inutilisée retirée.
  `WorkshopProjectDeletion.delete` ne supprime plus **que** dans la corbeille (`archived = 1`), nouvelle issue
  `NOT_IN_TRASH`.
* **Historique** : `/actions` → onglet **« Opérations du panel (N) »** (`?status=panel`), lu dans `audit_log`
  (`building.project.*`) : création, reprise, modification, images, corbeille, restauration, suppression
  définitive, **refus avec motif**, acteur, date, lien « Ouvrir » (fiche ou corbeille) ou « n'existe plus ».
  Recherche texte. Les nouvelles entrées d'audit portent aussi le nom du projet ; pour les anciennes, le nom est
  lu sur le projet s'il existe encore. L'entrée d'Essai 881 apparaîtra donc (bâtiment `essai_881`, « n'existe
  plus »).
* **Origine** (`WorkshopTrashPages.Origin`) : « Importé de l'emplacement « <nom> » `<id>` : porte aujourd'hui le
  bâtiment `<id>` vN » / libre / inconnu, sur la fiche et dans la corbeille, avec la phrase « nom de
  l'emplacement, nom du projet et bâtiment posé sont distincts ».

## Fichiers créés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/WorkshopTrashPages.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/WorkshopTrashPagesTest.java`
* ce rapport

## Fichiers modifiés

* `control-panel/.../building/BuildingProjectStore.java`, `WorkshopProjectDeletion.java`
* `control-panel/.../web/PanelApp.java`, `ActionsPages.java`, `WorkshopPages.java`
* tests `WorkshopProjectCleanup881Test.java`, `WorkshopProjectDeletionTest.java`
* `docs/RPGQUEST_BIBLE.md`, `docs/control-panel/BATIMENTS_PARCOURS.md`, `docs/MANUAL_TEST_PLAN.md` (TC-442),
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations

**Aucune migration.** La colonne existante `building_project.archived` porte la corbeille. Au démarrage, aucun
projet n'est modifié. Lectures en base DEV : **lecture seule** (`sqlite3 -readonly`), aucune écriture.

## Configuration / données

Aucune. Aucun projet réel touché, aucun appel IA, aucune action envoyée au serveur Minecraft.

## Tests automatiques

* Ciblés : `WorkshopProjectCleanup881Test` **12/12** (corbeille → restauration → suppression définitive avec
  identifiant recopié, seconds clics, historique visible avec refus et acteur ; version périmée ; jamais hors
  corbeille ; projet devenu posé non supprimable ; refus posé / publié / portail / sans relevé / vérification en
  attente / rôle), `WorkshopProjectDeletionTest` **4/4**, `WorkshopTrashPagesTest` **2/2**.
* Non-régression locale : `ActionsCenterTest`, `ActionPlayerTargetTest`, `NotificationsBugfixTest`,
  `WorkshopFlowTest`, `BuildingSitesPageTest`, `PortalsPageTest`, `BuildingLifecyclePageTest`, `PanelAppTest`,
  `Workshop*` : **218/218**.
* CI GitHub : **verte** sur PR #906 (Tests control-panel, plugin, web-api, Scripts, Artefacts, CI OK), fusionnée en `12365fb5`.

## Tests manuels à effectuer

**TC-442 — PENDING MANUAL VALIDATION** (`docs/MANUAL_TEST_PLAN.md`), après déploiement : onglet « Opérations du
panel » (Essai 881 visible), projet d'essai `essai_corbeille_881` → corbeille → restauration → suppression
définitive, fiche « Bibliothèque du Hub » (origine affichée, aucun bouton). Aucun test en jeu ni en PlugAdmin
n'a été fait par Claude ; aucun rendu Playwright dans cette session (à faire avant déploiement).

## Résultat attendu

L'Atelier se désencombre sans perte : un projet retiré est restaurable ; la suppression définitive est un
second geste confirmé ; chaque geste et chaque refus est visible dans l'Historique.

## Reset / retour à l'état initial

Restaurer depuis la corbeille. Rollback logiciel : release PlugAdmin précédente (un projet à la corbeille y
serait seulement masqué, jamais effacé).

## Déploiement VeryGames

Aucun : changement PlugAdmin seul (AWS), **non déployé** — le ticket n'autorise pas de déploiement. À
transférer sur décision : PlugAdmin via `scripts/plugadmin/deploy.sh` (JAR Minecraft inchangé, aucun
redémarrage Minecraft). À ne pas altérer : `control-panel.db` (snapshot `.backup` avant). Migration : aucune.

## Rollback

Redéployer la release PlugAdmin précédente (`deploy.sh`).

## Logs / diagnostic

Audit `building.project.trash`, `building.project.restore`, `building.project.delete` (OK / DENIED + motif),
visibles dans `/actions?status=panel`.

## Documentation mise à jour

Bible (Atelier : corbeille, refus, origine, historique), `BATIMENTS_PARCOURS.md`, TC-442, `SERVER_CHANGELOG.md`,
registre `SUIVI_VALIDATIONS_DEV.md` (ligne #881).

## Limitations / travail restant

* Les entrées d'audit antérieures à ce lot n'ont pas le nom du projet : pour un projet supprimé définitivement
  avant (Essai 881), seul l'identifiant de bâtiment s'affiche.
* L'onglet est limité aux 1 000 dernières entrées d'audit (même borne que les refus #169).
* Pas de purge automatique de la corbeille (volontaire).

## Prochaine étape suggérée

Feu vert du propriétaire pour déployer PlugAdmin, puis TC-442 sur un projet d'essai.
