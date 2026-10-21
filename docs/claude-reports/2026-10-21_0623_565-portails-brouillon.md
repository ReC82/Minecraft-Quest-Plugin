# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 06:23
* Sujet : #565 (#228-P2) — portails : création en brouillon, zone d'activation, activation/désactivation depuis PlugAdmin
* Statut : PARTIAL (livré et déployé ; validation en jeu soumise à un feu vert séparé du propriétaire)
* Branche Git : `feature/565-portails-brouillon` (PR #687, fusionnée dans `chore/257-ci-github-actions` → `1de1d576`)
* Commit actuel si disponible : `1de1d576`
* Début de la tâche : 2026-10-21 06:23:54
* Fin de la tâche : 2026-10-21 08:02:09
* Durée totale : 01:38:15

## Demande
Ticket #565 lancé par le superviseur lqorch : gérer les portails depuis PlugAdmin — brouillon (facultativement
lié à un emplacement #213 via `site:`), zone d'activation orientée ou min/max, contrôle des chevauchements à la
création **et au rechargement**, états brouillon / actif / désactivé, renommage avec id permanent, suppression sans
toucher l'emplacement ni la pose, emplacement référencé non supprimable, permissions `PORTAL_EDIT` / `PORTAL_ENABLE`,
audit avant/après. Autorisation propriétaire du 20/10 : développement complet, **aucune mutation de portail réel**
(pas même un brouillon sur DEV), tests simulés uniquement, déploiement de code inerte permis.

## Analyse
- Deux moteurs existaient ; DEV n'utilise que les world-portals (`hub_to_wild`, `hub_to_claims`). Décision Q2/Q3 de
  l'étude : étendre `WorldPortalRegistry` / `world-portals/<id>.yml`, lien porté par le portail (`site:`), sans
  migration.
- `WorldPortalRegistry.reload()` n'avait aucune validation croisée (anomalie documentée, tests qui la
  constataient). Relevé `portal.list` réel de DEV (lecture seule de la base PlugAdmin) : les deux portails sont
  **disjoints** (730..732 et 737..740 en X à Z=-676 / -673) → le refus au rechargement ne change rien pour eux.
- Choix de sécurité : un brouillon est écrit `enabled: false` + `draft: true`, donc ignoré par le listener actuel
  **et** par un JAR antérieur (rollback sûr).

## Travail effectué
Plugin :
- `WorldPortalDefinition.Details` (nom, site, brouillon, largeur/hauteur/profondeur), `state()`, `withEnabled`,
  `withZone`, `Zone`.
- `WorldPortalRegistry` : lecture/écriture des champs (seulement s'ils sont renseignés — un fichier historique reste
  identique), écriture atomique dans le **fichier d'origine**, mutations `synchronized`, `save()`, `fileOf()`,
  `create()` refuse un `<id>.yml` existant même ignoré ; **`reload()` refuse id en double et zones superposées**,
  portails déjà chargés prioritaires, sinon ordre alphabétique ; refus listés dans `skippedFiles()`.
- `PortalZone` (zone centrée orientée par la face de l'emplacement, 32 blocs max par côté), `PortalAuditLog`
  (`portal-audit.yml`, créé à la première modification), `WorldPortalAdminService` (création brouillon, mise à jour
  nom/zone, activation/désactivation, suppression ; empreinte SHA-256 tronquée exigée ; portails essentiels
  Hub/Wild/Claims → `confirm_essential` ; zone d'un actif verrouillée ; actif non supprimable ; superposition refusée
  aussi contre les portails de zone `portals/` ; `CLAIM_HOME` seulement vers `claims.world` ; activation : deux mondes
  chargés + emplacement existant).
- Actions agent `portal.create|update|enable|disable|delete` (`PortalAdminParams`), contexte relevé sur le thread
  principal puis écriture hors thread principal ; `portal.list` enrichi (état, nom stocké, site, dimensions,
  essentiel, empreinte, coins, mondes chargés, journal) ; diagnostics `PORTAL_DRAFT`, `PORTAL_SITE_MISSING` ;
  `building.site.delete` refusé (`SITE_USED_BY_PORTAL`) si un portail cite l'emplacement.
PlugAdmin :
- Permissions `PORTAL_EDIT` / `PORTAL_ENABLE` (propriétaire seul par défaut) ; catalogue : 5 actions sensibles,
  validation de forme, auteur = session.
- Page Portails : filtre Brouillons, formulaire « Créer un portail en brouillon » (emplacement + dimensions ou
  coordonnées, arrivée), fiche « Gérer ce portail » (activer/désactiver, nom, zone, suppression), seconde case pour
  les portails essentiels, journal « Modifications depuis PlugAdmin ».
- Page Emplacements : « Utilisé par le portail … » ; suppression refusée avant mise en file.
- Aide `docs/portails.md` + `DiagnosticHelp`.
- Rendu vérifié localement (instance isolée port 8097, relevés DEV copiés en lecture seule + un brouillon fictif,
  Chromium 1280 et 390 px, aucun débordement horizontal) ; correction de deux défauts vus à l'écran (champs texte non
  stylés, titre de zone dupliqué).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/travel/{PortalZone,PortalAuditLog,WorldPortalAdminService}.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/PortalAdminParams.java`
- `src/test/java/com/lodygames/rpgquest/travel/{PortalZoneTest,WorldPortalAdminServiceTest}.java`,
  `src/test/java/com/lodygames/rpgquest/web/agent/PortalAdminParamsTest.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/PortalForms.java`

## Fichiers modifiés
`WorldPortalDefinition`, `WorldPortalRegistry`, `PortalOverview`, `PortalListDetails`, `AgentActionType`,
`AgentActions`, `AgentActionExecutor`, `BukkitAgentActions`, `RPGQuestBootstrap` ; tests `WorldPortalRegistryTest`
(tests d'anomalie remplacés par le nouveau comportement), `PortalOverviewTest` ; panel `Permission`,
`AgentActionCatalog`, `AgentPages`, `PanelApp`, `PortalPages`, `DiagnosticHelp`, `docs/portails.md`,
`PortalsPageTest` ; docs `RPGQUEST_BIBLE.md`, `current_state.md`, `docs-site/worlds.html`, `MANUAL_TEST_PLAN.md`
(TC-387), `deployment/SERVER_CHANGELOG.md` ; registre `docs/SUIVI_VALIDATIONS_DEV.md` (PR de rapport).

## Base de données / migrations
Aucune.

## Configuration / données
Aucune clé de configuration. Nouveau fichier `plugins/RPGQuest/portal-audit.yml`, **créé seulement à la première
modification** (donc absent sur DEV). Champs YAML facultatifs `name`, `site`, `draft`, `size.*` dans
`world-portals/`. Aucun portail, emplacement, monde ni joueur touché sur DEV.

## Tests automatiques
- Ciblés en local : `travel.*` + `PortalAdminParamsTest` + `AgentActionExecutor*` (284 tests, 0 échec) ;
  panel `PortalsPageTest`, `DiagnosticHelpTest`, `AgentActionCatalogTest`, `BuildingSite*`, `DocsPagesTest`,
  `RolePermissionMatrixTest`, `WorldsPageTest`, `WorldSettingsPageTest` (0 échec).
- CI GitHub PR #687 : Tests plugin, Tests control-panel, Tests web-api, Scripts, Artefacts — **tous verts**.
- Garde-fou du script de déploiement : `scripts/deploy-verygames.sh -y` a relancé la suite complète du plugin en local : verte (étape 3/8 passée).

## Tests manuels à effectuer
TC-387 — **PENDING MANUAL VALIDATION**. Étapes 1 et 7 en lecture seule ; étapes 2 à 6 (création, activation…)
**seulement après un feu vert séparé du propriétaire**. Aucun test en jeu fait par Claude.

## Résultat attendu
`/portals` montre `hub_to_wild` / `hub_to_claims` inchangés (actifs, essentiels, zone verrouillée) et le formulaire
de brouillon ; un brouillon ne téléporte personne ; deux zones superposées sont refusées, y compris au redémarrage.

## Reset / retour à l'état initial
Supprimer un brouillon de test depuis sa fiche (fichier seul). Aucune donnée joueur concernée.

## Déploiement VeryGames
**Effectué le 21/10 vers 08:01.**
- JAR `1de1d576` transféré par `scripts/deploy-verygames.sh -y` : « JAR en ligne : 3988190 octets (== local) »,
  SHA-256 `d74fc530…` ; backup `rpgquest-20261021T060101Z-predeploy.jar` (SHA `dfb1a4d1…` = JAR #654, 3943029 o).
- **Serveur DEV toujours arrêté** (dernier heartbeat de l'agent : 20/10 23:02 UTC) : aucun redémarrage tenté ; le JAR
  sera chargé au prochain démarrage depuis le panel VeryGames.
- PlugAdmin `1de1d576` déployé par `scripts/plugadmin/deploy.sh` (CI verte, `/health` ONLINE, release précédente
  `/opt/plugadmin/releases/20261021-080147`) ; jar servi vérifié (`PortalForms`, aide « Créer et gérer un portail »).
- Aucun portail, emplacement, fichier `world-portals/` ni donnée DEV touché ; aucune action `portal.*` envoyée.
### À transférer
JAR RPGQuest `1de1d576` ; PlugAdmin `1de1d576` (AWS).
### Ne PAS transférer/altérer
`world-portals/hub_to_wild.yml`, `world-portals/hub_to_claims.yml`, `config.yml`, `data.db`, emplacements.
### Redémarrage requis
Oui pour charger le JAR (code inerte au démarrage).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` ; PlugAdmin : release précédente. Un brouillon éventuel reste inactif
sous l'ancien JAR (`enabled: false`).

## Logs / diagnostic
Au démarrage : « Chargement des portails simples : 2 chargé(s). » attendu. Un fichier refusé pour conflit est journalisé
« Portail simple ignoré (…) : zone d'activation qui recouvre … » et visible en `PORTAL_FILE_SKIPPED`.

## Documentation mise à jour
Bible (section #565), `current_state.md`, `docs-site/worlds.html`, aide panel `portails.md`, TC-387,
`SERVER_CHANGELOG.md`, registre de validation.

## Limitations / travail restant
- Destination précise (point fixe…), conditions, messages, test de téléportation : #566–#570.
- Zone « dérivée des ancrages de la pose » (#568) non traitée : seules dimensions saisies ou min/max.
- `/rpgadmin worldportal enable` sur un brouillon l'active aussi (chemin admin existant, sans empreinte).

## Prochaine étape suggérée
Propriétaire : TC-387 étape 1 (lecture seule) puis, s'il le souhaite, donner le feu vert pour un brouillon de test.
