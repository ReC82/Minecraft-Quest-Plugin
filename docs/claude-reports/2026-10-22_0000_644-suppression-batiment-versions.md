# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-22
* Heure : 00:00
* Sujet : #644 — supprimer un bâtiment de la Bibliothèque avec toutes ses versions
* Statut : DONE côté livraison (code, CI, fusion, déploiement DEV) — validation propriétaire TC-399 PENDING MANUAL VALIDATION, ticket laissé ouvert
* Branche Git : `feature/644-build-library-family-delete` → fusionnée dans `chore/257-ci-github-actions` (PR #718, merge `318f31e2`)
* Commit actuel si disponible : `318f31e2`
* Début de la tâche : 2026-10-22 00:00:10
* Fin de la tâche : 2026-10-22 00:42:10
* Durée totale : 00:42:00

## Demande
#644 : depuis Bâtiments → Bibliothèque, supprimer un bâtiment **et toutes ses versions**, distinctement de l'archivage (#284) et de la suppression physique d'une construction ; aperçu exact avant confirmation, confirmation forte, permission dédiée, audit, refus en cas d'usage (pose, portail…), aucune ressource partagée supprimée, aucun bloc modifié, pas de demi-suppression, pas de réapparition, parcours « repartir de zéro ». Gouvernance : conception + code + tests isolés ; **aucune suppression, archivage ou purge sur les données DEV**.

## Analyse
Audit du livré (#284, #94, #308, #568) :
- La Bibliothèque = `buildings/*.yml` de premier niveau + `schematics/<id>.schem` ; une seule définition par identifiant.
- Les « versions » d'un bâtiment vivent dans `buildings/.history/<id>/v<N>_<horodatage>/` (copie faite par la régénération, #94) et `buildings/.retired/<id>_<horodatage>/` (archivage #284). Rien ne les nettoyait.
- Références : poses (`building_placements`, avec sauvegarde de terrain `backup_*`), portails liés (`details.building`, #568, y compris brouillons), autres fichiers déclarant le même id. Les projets/versions de l'Atelier sont dans `control-panel.db` (#308) et restent indépendants.
- « Repartir de zéro » existe déjà : nouveau projet Atelier isolé (#308) ; l'Atelier refuse un identifiant déjà dans la bibliothèque (« Changez-le… »).

## Travail effectué
- Plugin : `BuildingFamilyPurge` — aperçu en lecture seule (version courante, remplacées, archivées, effacement inachevé `.purging`), liste « conservé » (schem partagé, entrée non reconnue, bâtiment au nom proche, sauvegardes de terrain…), blocages (embarqué, posé, portail lié même brouillon, fichier de portail illisible qui le cite, autre yml déclarant l'id). Suppression : identifiant ressaisi, retrait de l'ensemble actif + revérification, déplacements atomiques vers `buildings/.purging/<id>_<horodatage>/`, compensation intégrale en cas d'échec ou si la bibliothèque le porte encore, puis effacement ; `PURGED_PENDING` si l'effacement final est incomplet (repris par une nouvelle suppression). Journal `buildings/.audit/purges.log`. Rien au démarrage, aucun bloc.
- Actions agent `building.family.preview` / `building.family.purge` ; câblage bootstrap (portails via `WorldPortalRegistry`, fichiers illisibles de `world-portals/`).
- PlugAdmin : permission `BUILDING_PURGE` (Propriétaire seul) ; zone « Supprimer ce bâtiment et toutes ses versions » distincte de « Retirer de la bibliothèque » ; aperçu obligatoire avant tout bouton ; identifiant à ressaisir + case ; refus avant la file si posé ; double clic sans doublon ; auteur = session ; état Atelier « Supprimé avec toutes ses versions » (l'identifiant redevient libre).
- QA visuelle locale (panel local, données synthétiques) : 1280 et 390 px, aucun débordement, aucune erreur console ; correctifs : espace après « Aperçu », chemins longs repliés (`tid--wrap`). Captures : `assets/644-suppression-batiment/`.

## Fichiers créés
`src/main/java/com/lodygames/rpgquest/building/BuildingFamilyPurge.java`, `src/test/java/com/lodygames/rpgquest/building/BuildingFamilyPurgeTest.java`, captures `docs/claude-reports/assets/644-suppression-batiment/`.

## Fichiers modifiés
Plugin : `web/agent/{AgentActionType,AgentActions,BukkitAgentActions,AgentActionExecutor}`, `bootstrap/RPGQuestBootstrap` ; test `AgentActionExecutorTest`. Panel : `authz/Permission`, `agent/AgentActionCatalog`, `building/CatalogState`, `web/{AgentPages,PanelApp,WorkshopPages}` ; test `BuildingPlacementPageTest`. Docs : Bible, current_state, TC-399, SERVER_CHANGELOG, registre de validation.

## Base de données / migrations
Aucune (ni `data.db`, ni `control-panel.db`).

## Configuration / données
Aucune clé. Dossiers `buildings/.purging/` et `buildings/.audit/` créés uniquement lors d'une suppression. **Aucun bâtiment, version, projet, schematic, snapshot, emplacement ni portail DEV touché ; aucun aperçu ni suppression lancé sur DEV ; aucun appel IA.**

## Tests automatiques
- Local ciblé : `BuildingFamilyPurgeTest` 12/12 (aperçu sans effet, suppression complète + redémarrage simulé + double clic, posé, portail, confirmation fausse, embarqué, schem partagé conservé, autre yml même id, homonymes/entrées inconnues conservés, famille seulement archivée, reprise d'un effacement inachevé, échec à mi-parcours → tout remis en place) ; `AgentActionExecutorTest` 142/142 ; panel `BuildingPlacementPageTest` 36/36 (+3), `RolePermissionMatrixTest`, `AgentActionCatalogTest`, `EffectivePermissionsTest`, `WorkshopFlowTest` verts.
- GitHub Actions : PR #718 (push + PR) toutes vérifications vertes ; CI du merge `318f31e2` verte (run 39686751426).

## Tests manuels à effectuer
TC-399 — PENDING MANUAL VALIDATION, **avec un bâtiment d'essai créé pour l'occasion et l'accord du propriétaire uniquement**.

## Résultat attendu
Le propriétaire voit, avant tout, la liste exacte des versions et fichiers d'un bâtiment, ce qui est conservé et ce qui bloque ; il ne peut supprimer qu'en ressaisissant l'identifiant ; un bâtiment posé ou lié à un portail est refusé ; rien ne réapparaît ; aucun bloc ne change.

## Reset / retour à l'état initial
Revenir avant `318f31e2`. Une suppression définitive n'est pas réversible (c'est son objet) ; l'archivage #284 reste le geste réversible.

## Déploiement VeryGames
**Effectué le 22/10 vers 00:40.**
- JAR `318f31e2` via `scripts/deploy-verygames.sh -y` (validation « CI verte sur 318f31e ») : en ligne 4 205 779 o, SHA-256 `596d478f298b7ef8ab4176785715676faa3a64d4c365408fd2edf5150d559bf2`, **retéléchargé par FTP et identique**, classes `BuildingFamilyPurge` présentes.
- Sauvegarde : `rpgquest-20261021T223951Z-predeploy.jar` (4 182 486 o, SHA-256 `c4f20086…` = JAR #675).
- DEV redémarré par `scripts/verygames-restart.sh` (0 joueur), ONLINE, plugins Citizens, LuckPerms, Multiverse-Core, RPGQuest, WorldEdit.
- PlugAdmin `318f31e2` via `scripts/plugadmin/deploy.sh` : `/health` KO à 2 s (course connue) puis ONLINE ; classes `BUILDING_PURGE` / `building.family.*` dans le JAR servi ; `/buildings/library` → 303 /login.
### À transférer
Fait : JAR + PlugAdmin.
### Ne PAS transférer/altérer
`buildings/`, `schematics/`, `world-portals/`, `data.db`, `control-panel.db`.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.
## Rollback
`scripts/rollback-verygames.sh` avec `rpgquest-20261021T223951Z-predeploy.jar` ; PlugAdmin : `scripts/plugadmin/rollback.sh app`.
## Logs / diagnostic
Plugin : `[building] « id » supprimé avec ses N version(s) par …` ; `buildings/.audit/purges.log`. Panel : audit `agent.action.create` (refus « bâtiment posé », « suppression déjà en attente »).
## Documentation mise à jour
Bible (« Supprimer un bâtiment et toutes ses versions »), current_state, TC-399, SERVER_CHANGELOG (entrée 22/10, à compléter : déployé), registre `SUIVI_VALIDATIONS_DEV.md`. Pas de page `docs-site/` Bâtiments existante.
## Limitations / travail restant
- Pas d'action « Restaurer » une version archivée (#284) ni de purge d'une famille qui n'est plus dans la bibliothèque depuis l'écran (l'action le permet, l'écran ne liste que les bâtiments du catalogue).
- Les projets de l'Atelier ne sont ni supprimés ni archivés par ce parcours (choix conservateur : historique d'administration conservé).
- Le titre de l'accordéon devient vertical sur téléphone pour un nom très long (préexistant, non lié).
## Prochaine étape suggérée
TC-399 par le propriétaire sur un bâtiment d'essai.
