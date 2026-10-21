# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 21:28
* Sujet : #568 (#228-P5) — portails : construction liée (Bibliothèque, Atelier IA, arche)
* Statut : DONE (livraison technique) — validation en jeu PENDING MANUAL VALIDATION (TC-396)
* Branche Git : `feature/568-portails-construction-liee` (PR #711, fusionnée dans `chore/257-ci-github-actions`, merge `f35c9538`) ; rapport : `docs/568-rapport`
* Commit actuel si disponible : `f35c9538` (code déployé)
* Début de la tâche : 2026-10-21 20:32:45
* Fin de la tâche : 2026-10-21 21:28:02
* Durée totale : 00:55:17

## Demande
Session autonome lqorch, ticket #568 : conserver `StructureKind.PORTAL` et une zone de passage relative à l'ancre dans la
Bibliothèque (contrat d'ancrages), transformée par `BuildingRotation` à la pose ; filtre « portails » ; Atelier IA en mode
portail ; fiche portail : lier la construction posée (zone déduite de la pose), remplacer l'arche sans perdre la
destination, délier sans démolir ; poser une arche n'active jamais un portail. Autorisation du 20/10 : **tests simulés
uniquement**, aucune pose / remplacement / suppression réelle, aucun portail réel modifié, aucun appel IA payant.

## Analyse
- P2 (#565) avait déjà le lien `site:` porté par le portail et `PortalZone.around` (dimensions saisies) ; P3/P4/P6 livrés.
- `BuildingDefinition` ne conservait ni le type du plan ni aucune notion de passage ; le type n'existait que dans le plan
  stocké sous `plan:` des bâtiments générés. Aucune arche n'existe sur DEV.
- La pose (#234, `BuildingPlacementService`) enregistre ancre + rotation réellement appliquées : suffisant pour déduire la
  zone sans nouvelle table. Décision : lien explicite dans le fichier du portail (`building:`), jamais écrit par la pose,
  conformément à l'étude (§5, Q3) — pas de second moteur, pas de colonne sur `building_sites`.
- Atelier : la seule façon fiable de lancer une arche est un type de projet ; ajout de 3 colonnes **additives** à
  `building_project` (même procédé que `max_depth` #305).

## Travail effectué
**Plugin**
- `PortalPassage` (record pur) : zone locale + `inWorld(definition, placement)` (rotation des 4 coins autour de l'ancre,
  même calcul que `BuildingFootprint`). `BuildingDefinition` : `kind` (défaut HABITABLE) + `passage` (réservé à PORTAL,
  contenu dans le schematic), constructeur historique conservé.
- `BuildingDefinitionYaml` : lit `kind:` (sinon le `kind:` du plan conservé — bâtiments générés avant #568) et
  `passage.min/max` ; `writeKind` n'écrit rien pour un bâtiment habitable sans passage (fichier identique).
- `PortalPassageFinder` : passage relevé sur la grille depuis l'ancre (colonnes traversantes dans l'axe de la façade,
  2 blocs mini, ancre dans la dalle → au-dessus). `BuildingGenerator` : verdict et publication d'une arche refusés
  `PORTAL_PASSAGE_MISSING` avant toute écriture ; message de verdict « Passage du portail : … ».
- Portail : `Details.building` (+ `withBuilding`, `linked`), YAML `building:` (ignoré sans `site:`), empreinte inchangée
  sans lien. `PortalBuildingLink` (pur) : `derive`, `status` (NONE, OK, BUILDING_MISSING, BUILDING_CHANGED,
  DEFINITION_MISSING, NO_PASSAGE, ZONE_MISMATCH), `describe`.
- `WorldPortalAdminService` : `LINK_BUILDING` / `UNLINK_BUILDING` ; lier refusé sur un portail actif, sans emplacement,
  sans construction, construction non-portail / sans passage / retirée, autre monde, passage > 32 ; **état conservé**
  (jamais d'activation) ; `ENABLE` refusé `BUILDING_LINK_BROKEN` si le lien est rompu ; saisir une zone délie.
  `Context` + `PortalOverview.Inputs` reçoivent la construction posée par emplacement (caches mémoire).
- `portal.verify` : contrôle `BUILDING` ; `portal.list` : bloc `construction` + diagnostic `PORTAL_BUILDING_LINK_BROKEN` ;
  `building.definition.list` : `kind`, `passage`. Actions agent `portal.building.link` / `portal.building.unlink`.

**PlugAdmin**
- Fiche portail : bloc « 2 · Construction » (Téléportation devient 3), formulaires Lier / Lier à nouveau / Délier sans
  démolir (PORTAL_EDIT, empreinte, confirmation, confirmation « portail essentiel »).
- Bibliothèque : filtres Tous / Bâtiments / Décorations / Portails, badge « Arche de portail », lignes Type et Zone de
  passage. Atelier : « Type de construction » + « Passage de l'arche » (replié sauf pour une arche) ; `briefForAi()`
  ajoute le contrat de portail au brief envoyé à l'IA (le brief enregistré ne change pas) ; refus si le passage ne laisse
  pas de pilier / de linteau.
- Aide `PORTAL_BUILDING_LINK_BROKEN` + section « Construction liée (arche) » de `docs/portails.md`.

**Déploiement DEV** : JAR + PlugAdmin `f35c9538` (détail plus bas). **Aucune** arche générée, posée ou liée ; aucun
portail, emplacement ni bâtiment réel modifié ; aucun appel IA.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/building/model/PortalPassage.java`
- `src/main/java/com/lodygames/rpgquest/building/plan/PortalPassageFinder.java`
- `src/main/java/com/lodygames/rpgquest/travel/PortalBuildingLink.java`
- `src/test/java/com/lodygames/rpgquest/building/PortalPassageTest.java`
- `src/test/java/com/lodygames/rpgquest/building/plan/PortalPassageFinderTest.java`
- `src/test/java/com/lodygames/rpgquest/travel/WorldPortalBuildingLinkTest.java`
- `docs/claude-reports/assets/568-portails-construction/*.png` (rendus locaux)

## Fichiers modifiés
- Plugin : `BuildingDefinition`, `BuildingDefinitionYaml`, `BuildingGenerator`, `WorldPortalDefinition`,
  `WorldPortalRegistry`, `WorldPortalAdminService`, `WorldPortalVerifier`, `PortalOverview`, `AgentActionType`,
  `AgentActionExecutor`, `AgentActions`, `BukkitAgentActions`, `PortalListDetails` ; tests `BuildingGeneratorTest`,
  `PortalListActionTest`.
- PlugAdmin : `AgentActionCatalog`, `BuildingDesignPipeline`, `BuildingDefinitionView`, `BuildingLibraryDirectory`,
  `BuildingProject`, `BuildingProjectStore`, `AgentPages`, `DiagnosticHelp`, `PanelApp`, `PortalForms`, `PortalPages`,
  `WorkshopPages`, `docs/portails.md` ; tests `PortalsPageTest`, `BuildingPlacementPageTest`, `BuildingProjectStoreTest`.
- Docs : `docs/RPGQUEST_BIBLE.md`, `docs-site/worlds.html`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-396),
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`.

## Base de données / migrations
- `data.db` : **aucune** migration.
- `control-panel.db` : migration **additive** et idempotente de `building_project` (`kind TEXT`, `passage_width INTEGER`,
  `passage_height INTEGER`) ; vides = bâtiment ordinaire ; aucune ligne réécrite. Vérifiée sur la base de DEV après
  déploiement (`PRAGMA table_info`).

## Configuration / données
Aucune clé de configuration. Nouveaux champs facultatifs : `kind:` / `passage:` (fichiers `buildings/`), `building:`
(fichiers `world-portals/`). Aucun fichier de contenu transféré.

## Tests automatiques
- Plugin ciblés : `PortalPassageTest` 6/0, `PortalPassageFinderTest` 4/0, `BuildingGeneratorTest` 26/0,
  `ArchitecturalAuditTest` 14/0, `WorldPortalBuildingLinkTest` 10/0, `WorldPortalAdminServiceTest` 22/0,
  `PortalOverviewTest` 12/0, `PortalListActionTest` 2/0, `WorldPortalDestinationTest` 16/0, `AgentActionExecutorTest`
  141/0, `BuildingLibraryTest` 16/0, `BuildingDefinitionTest` 13/0.
- PlugAdmin ciblés : `PortalsPageTest` 23/0, `BuildingPlacementPageTest` 33/0, `BuildingProjectStoreTest` 9/0,
  `AgentActionCatalogTest` 53/0, `WorkshopFlowTest` 39/0, `DiagnosticHelpTest` 7/0, `Estrade472PromptTest`,
  `PlanImportPreviewTest`, `BuildingSitesPageTest`.
- CI GitHub (suite complète) : PR #711 verte sur `8f2f898c` (run 39658091320) ; fusion `f35c9538` verte (run 39659681686).
- Rendu local (panel de test, relevés DEV + arche fictive, Playwright) : fiche portail, `hub_to_wild`, Bibliothèque,
  nouveau projet à 1280 et 390 px — aucun débordement, aucune erreur console. Le bouton « Délier » était bleu
  (style `btn-outline-*` écrasé par le panel) : corrigé en `btn sm secondary`. Les champs du passage, toujours visibles,
  ont été repliés. Captures : `assets/568-portails-construction/` (la Bibliothèque y est seedée depuis l'ancien JAR, sans
  `kind` : tout y est « Bâtiment »).

## Tests manuels à effectuer
TC-396 — PENDING MANUAL VALIDATION. Étape 1 en lecture seule ; étapes 2-6 (génération IA payante d'une arche, pose et
liaison sur un emplacement de test, remplacement, libération) **sur feu vert du propriétaire** uniquement.

## Résultat attendu
Une arche créée dans l'Atelier garde son type et son passage ; posée sur l'emplacement d'un portail, « Lier » donne au
portail la zone exacte de son passage quelle que soit la rotation, sans l'activer ; remplacer / réorienter / libérer
l'arche rompt le lien (activation refusée) sans toucher destination ni conditions ; « Lier à nouveau » ou « Délier »
répare. Rien ne change pour `hub_to_wild` / `hub_to_claims`.

## Reset / retour à l'état initial
Rien à réinitialiser : aucune donnée réelle créée. Un portail de test se délie (`Délier sans démolir`) puis se supprime ;
l'arche se libère depuis Emplacements (terrain d'origine restauré par #234).

## Déploiement VeryGames
### À transférer
JAR `f35c9538` — **fait** : `scripts/deploy-verygames.sh -y` (validation « CI verte sur f35c953 »), en ligne 4178087 o,
SHA-256 `79fb4faae8375123cdcc0f553b97ec176f59148bcd693f437ab6ed6d4519fb19`, retéléchargé par FTP et identique ;
sauvegarde `rpgquest-20261021T192521Z-predeploy.jar` (4156217 o, SHA `9fab196b…` = JAR #569, plus petit que le nouveau).
PlugAdmin `f35c9538` — **fait** : `scripts/plugadmin/deploy.sh` (`validation=CI verte sur f35c953`, `/health` ONLINE,
`portal.building.link` et la section d'aide présents dans le JAR servi, `/portals` → 303 vers `/login`).
### Ne PAS transférer/altérer
`world-portals/*.yml`, `buildings/*.yml`, `config.yml`, `data.db`, emplacements et poses.
### Redémarrage requis
Oui (JAR) — **fait** : `scripts/verygames-restart.sh` (0 joueur connecté avant), serveur de nouveau ONLINE.
### Migration automatique
`data.db` : aucune. `control-panel.db` : colonnes additives au démarrage de PlugAdmin (faites, vérifiées).
Relu après redémarrage (actions `portal.list` / `building.definition.list` créées par `claude-568`, lecture seule) :
`hub_to_wild` / `hub_to_claims` ACTIVE, construction `NONE`, empreintes `abbf0fe0…` / `87276553…` identiques, aucun
diagnostic ; 10 bâtiments, 0 problème, `hub_estrade_elias` et `estrade_elias` relus `decoration` depuis leur plan.

## Rollback
`scripts/rollback-verygames.sh --latest` + redémarrage ; `scripts/plugadmin/rollback.sh`. Un JAR antérieur ignore
`kind:` / `passage:` / `building:` ; un PlugAdmin antérieur ignore les colonnes ajoutées.

## Logs / diagnostic
Journaux locaux : `~/.local/state/rpgquest-build/deploy-568.log`, `plugadmin-568.log`. En jeu : `portal-audit.yml`
(`LINK_BUILDING`, `UNLINK_BUILDING`), `portal.verify` (contrôle `BUILDING`), diagnostic `PORTAL_BUILDING_LINK_BROKEN`.

## Documentation mise à jour
Bible (section #568), `docs-site/worlds.html`, `docs/current_state.md`, `MANUAL_TEST_PLAN.md` (TC-396), aide PlugAdmin
`portails.md`, `SERVER_CHANGELOG.md`, `SUIVI_VALIDATIONS_DEV.md` (ligne 6d-undecies, dépendances #331/#228).

## Limitations / travail restant
- La capture (#246) d'une arche posée crée un bâtiment habitable sans passage (documenté).
- Un `.schem` importé (#245, non livré) n'aura un passage que si sa définition le déclare à la main.
- Rien n'empêche de libérer / remplacer l'arche sous un portail **actif** : le portail garde sa zone et le diagnostic
  devient une erreur (`PORTAL_BUILDING_LINK_BROKEN`) ; à durcir si le propriétaire le souhaite (refus côté pose).
- Aucun essai réel : génération IA, pose, liaison et traversée restent à valider (TC-396, feu vert requis).

## Prochaine étape suggérée
Propriétaire : TC-396 étape 1 (lecture seule) ; décider s'il autorise l'étape 2 (un appel IA payant pour une arche de
test) et une pose sur un emplacement de test. lqorch : #570 (recette mondes/portails) reste en file.
