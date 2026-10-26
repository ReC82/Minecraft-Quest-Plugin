# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-26
* Heure : 20:42
* Sujet : #644 — exigence du 26/10 : versions posées immuables + audit lecture seule de l'église
* Statut : DONE (livré et déployé sur DEV ; TC-439 PENDING MANUAL VALIDATION)
* Branche Git : `feature/644-immutable-used-versions` (PR #886) → `chore/257-ci-github-actions`, merge `d5a1c48e`
* Commit actuel si disponible : `d5a1c48e`
* Début de la tâche : 2026-10-26 19:58:49
* Fin de la tâche : 2026-10-26 20:42:08
* Durée totale : 00:43:19

## Demande
Commentaire propriétaire du 26/10 08:35 sur #644 : (1) auditer **en lecture seule** l'église posée (placement,
version/hash, schematic, snapshots, versions) ; (2) bloquer suppression définitive **et** écrasement en place
des données d'une version encore posée ; (3) une amélioration Atelier ne doit jamais perdre la version posée ;
(5) référence incertaine ou ancienne version manquante → bloquer et diagnostiquer ; (6) tests simulés
v1 posée → v2 publiée → v1 non supprimable → restauration possible, sans muter le monde. Aucune opération
réelle sur DEV sur l'église, aucun appel IA.

## Analyse
- Seul chemin qui réécrit une version en place : la republication Atelier (`BuildingGenerator.generate`
  avec remplacement). Elle **copie** la version courante dans `buildings/.history/<id>/v<N>_<horodatage>/`
  (#290) puis écrase `schematics/<id>.schem` et `buildings/<id>.yml` — sans connaître les poses ni vérifier
  la copie. La capture (#246) n'écrase jamais ; le retrait (#284) et la purge (#644) refusent déjà un
  bâtiment posé ; la restauration du terrain utilise la sauvegarde prise à la pose, indépendante de la
  bibliothèque.
- Un placement mémorise l'empreinte SHA-256 du fichier collé (#234) : c'est la seule preuve fiable de la
  version posée.

**Audit lecture seule de l'église (DEV, 26/10 ~20:00)** — actions agent `building.site.list` /
`building.definition.list` (lecture) + listing/téléchargement FTP :
- `buildsite_0016` porte `test_eglise_747c` (« TEST #747c église ») **v1**, posée le 24/10 18:02 UTC,
  SHA `987ff2a3404a…`, rotation 0, restaurable (« terrain d'origine (1 sauvegarde) »,
  `schematics/origine_buildsite_0016_1792864943949_0.schem` présent).
- La bibliothèque ne porte qu'une église : `test_eglise_747c` **v2**, SHA `295b19adc615…` (publiée le 25/10
  07:58 UTC, d'où « une version plus récente existe »).
- `buildings/.history/test_eglise_747c/v1_20261025-075854/` contient `test_eglise_747c.schem` (SHA recalculé
  `987ff2a3…`, **identique** à la pose) et `test_eglise_747c.yml` (version 1). Pas d'archive `.retired`.
- Conclusion : la version posée n'a pas disparu et rien n'indique une modification des blocs ; elle est
  conservée octet pour octet dans l'historique. Rien n'a été modifié.

## Travail effectué
- `building/PlacedVersions` (lecture seule) : pour chaque pose, retrouve par empreinte la copie exacte —
  fichier courant, `.history/<id>/…`, `.retired/<id>_<horodatage>/…` — ou la déclare INTROUVABLE / non
  vérifiable (pose sans empreinte). Empreintes en cache (taille + date).
- `BuildingGenerator.protectingPlaced(...)` : republication d'un bâtiment **posé** refusée
  `PLACED_VERSION_PROTECTED`, sans rien écrire, si une pose n'a pas d'empreinte ou est introuvable (avant
  l'archivage), ou si la copie archivée ne porte pas la même empreinte (après l'archivage, avant
  l'écrasement). Un bâtiment non posé reste libre.
- `building.site.list` : champ `placedCopy` ; PlugAdmin, fiche d'emplacement : « Version posée conservée » +
  alerte si introuvable / non vérifiable.
- Atelier : `PLACED_VERSION_PROTECTED` traité comme un conflit (pas de correction IA) avec la consigne
  « changer le nom technique » ou libérer l'emplacement.
- Non fait (hors périmètre de cette session, à décider) : gestes PlugAdmin « comparer version posée et
  dernière », « reposer / restaurer une ancienne version depuis l'historique », sélecteur de versions dans
  la fiche Bibliothèque.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/building/PlacedVersions.java`
- `src/test/java/com/lodygames/rpgquest/building/plan/PlacedVersions644Test.java`

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/building/plan/BuildingGenerator.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/{AgentActions,AgentActionExecutor,BukkitAgentActions}.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/AgentActionExecutorTest.java`
- `control-panel/.../panel/building/{BuildingPlacementView,BuildingSiteDirectory,SaveFailure}.java`
- `control-panel/.../panel/web/{AgentPages,WorkshopPages}.java`
- `control-panel/src/test/.../panel/web/{BuildingLifecyclePageTest,WorkshopFlowTest}.java`
- `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-439),
  `docs/SUIVI_VALIDATIONS_DEV.md` (PR de rapport)

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. Aucun fichier de bâtiment, version, schematic, snapshot, emplacement ni portail modifié sur DEV.

## Tests automatiques
Local ciblé (`RPGQUEST_TEST_MAX_HEAP=768m`) : `PlacedVersions644Test` 6/6, `BuildingGeneratorTest` 26,
`BuildingFamilyPurgeTest` 12, `AgentActionExecutorTest` 142, `BuildingLifecyclePageTest` 27,
`WorkshopFlowTest` 54, `BuildingSiteDirectoryTest` 13 — verts. CI GitHub PR #886 et merge `d5a1c48e` vertes.
Scénario couvert : v1 posée → v2 publiée → v1 retrouvée dans `.history` avec la même empreinte → purge et
retrait refusés → sauvegarde de terrain intacte, `restorable()` vrai, aucun collage (aucun bloc).

## Tests manuels à effectuer
TC-439 — PENDING MANUAL VALIDATION (`docs/MANUAL_TEST_PLAN.md`), sur un bâtiment d'essai uniquement.

## Résultat attendu
La fiche de `buildsite_0016` affiche « Version posée conservée : v1 — conservée dans l'historique
(buildings/.history/test_eglise_747c/v1_20261025-075854/test_eglise_747c.schem) » — **déjà constaté** sur DEV
via `building.site.list` après déploiement (relevé lecture seule).

## Reset / retour à l'état initial
Rien à remettre : aucune donnée modifiée. Rollback du code ci-dessous.

## Déploiement VeryGames
### À transférer
JAR `d5a1c48e` (fait : `scripts/deploy-verygames.sh -y`, « CI verte sur d5a1c48 », 4 442 326 octets, SHA
`018cfc40…` retéléchargé identique) ; PlugAdmin `d5a1c48e` (fait : `scripts/plugadmin/deploy.sh`, /health KO
dû à la course connue, revérifié ONLINE, classes `placedCopy` présentes dans le JAR servi).
### Ne PAS transférer/altérer
`buildings/`, `buildings/.history/`, `schematics/` (dont `origine_*`/`backup_*`), `data.db`, config.
### Redémarrage requis
Oui (fait 20:40 via `scripts/verygames-restart.sh`, LoDyMcFly — le propriétaire — était en ligne).
### Migration automatique
Aucune.

## Rollback
JAR : remettre `~/.local/share/rpgquest/verygames-backups/rpgquest-20261026T194001Z-predeploy.jar` puis
redémarrer ; PlugAdmin : `scripts/plugadmin/rollback.sh app`. Aucun format de données changé.

## Logs / diagnostic
`~/deploy-logs/644-immutable-d5a1c48e.log`, `~/deploy-logs/644-plugadmin-d5a1c48e.log`. Actions agent de
lecture `created_by = claude-644` dans `control-panel.db`.

## Documentation mise à jour
Bible (version posée immuable), `current_state.md`, `MANUAL_TEST_PLAN.md` (TC-439), registre
`SUIVI_VALIDATIONS_DEV.md` (ligne #644, décision ajoutée). Pas de page `docs-site/` pour les bâtiments.

## Limitations / travail restant
- Les poses antérieures à #234 (sans empreinte) bloquent désormais la republication sous le même
  identifiant (choix prudent demandé) ; aucune n'existe aujourd'hui sur DEV (toutes les poses ont une
  empreinte).
- Le relevé hache les fichiers d'historique seulement pour une pose dont la version a changé (cache par
  taille/date).
- Comparer / remplacer en jeu par une ancienne version / sélecteur de versions : non livrés.

## Prochaine étape suggérée
Propriétaire : TC-439 (lecture de la fiche de l'église, puis essai sur `essai_version_posee_644`). Décider si
les gestes « comparer » et « reposer une version de l'historique » font l'objet d'un ticket dédié.
