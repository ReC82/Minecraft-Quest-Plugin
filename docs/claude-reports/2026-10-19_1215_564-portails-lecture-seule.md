# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-19
* Heure : 12:15
* Sujet : #564 (#228-P1) — Portails : modèle unique de lecture et page PlugAdmin `/portals`
* Statut : DONE (livré, déployé DEV, vérifié techniquement) — validation en jeu en attente (WAITING_OWNER)
* Branche Git : `feature/564-portails-lecture` (PR #579, merge `46c01a1`) + `fix/564-portails-cesure` (PR #580, merge `209a5b2`) → `chore/257-ci-github-actions`
* Commit actuel si disponible : `209a5b2`
* Début de la tâche : 2026-10-19 11:12:27
* Fin de la tâche : 2026-10-19 12:15:34
* Durée totale : 01:03:07

## Demande
Session autonome lqorch, ticket #564 : page `/portals` **en lecture seule** fondée sur un modèle unique des
portails existants (world-portals `hub_to_wild` / `hub_to_claims` + portails de zone `portals/` s'il y en a,
marqués « ancien format ») : id, nom, état, monde/zone source, destination, stratégie, gardes Wild/Claims,
avertissements, diagnostics, lien vers `/worlds` (#560), permission `PORTAL_READ`. Réutiliser le moteur sans
altérer téléportations, avertissements ni conditions. Aucun portail créé/modifié/désactivé, aucun joueur
téléporté, aucune action mutative. Aucun autre lot après #564.

## Analyse
- Aucune branche ni PR préexistante pour #564 ; intégration à jour sur `306b5e2` (#560 livré).
- Deux moteurs : `WorldPortalRegistry` (`world-portals/`, déclenchement `WorldPortalTeleportListener`, gardes
  `ClaimWorldAccessGuard` + `WildEntryWarningService` composés) et `YamlPortalRegistry` / `PortalService`
  (`portals/` + `destinations/`, conditions, canalisation).
- Les gardes s'appliquent par **égalité exacte** du nom du monde d'arrivée avec `travel.wild-world` /
  `claims.world`. Pour que la page ne puisse pas diverger du jeu, ce prédicat a été extrait en méthode
  statique `appliesTo` dans chaque garde, appelée à la fois par `allowEntry` et par la vue.
- `WorldPortalRegistry.reload()` ignore silencieusement les fichiers invalides et ne détecte ni doublon ni
  chevauchement (limite déjà documentée) ; `portalAt` renvoie le premier portail **avant** le filtre
  `enabled` : un portail désactivé masque un portail actif qui le chevauche. Ces cas deviennent des diagnostics.

## Travail effectué
- **Plugin** : `travel.PortalOverview` (assemblage pur, testable sans serveur) ; action agent `portal.list`
  (`AgentActionType.PORTAL_LIST`, `AgentActions.portalList()`, `PortalListDetails`,
  `BukkitAgentActions.setPortalSources/portalList` sur le thread principal, caches mémoire seulement) ;
  `WorldPortalRegistry.sourceFiles()` / `skippedFiles()` (relevé seul, ce qui est chargé ne change pas) ;
  `WildEntryWarningService.appliesTo`, `ClaimWorldAccessGuard.appliesTo` ; `ClaimArrivalResolver.ATTEMPTS` public.
- Contenu de la vue : id, nom affiché **déduit** de la destination (aucun nom stocké), actif, fichier, monde de
  départ + rôle + chargé, coins min/max, taille, monde d'arrivée + rôle + chargé + point d'arrivée réel,
  stratégie et paramètres (`travel.random-safe-arrival`, `claims.arrival.*`, 40 tentatives), déclenchement,
  gardes (règles + textes actuels de l'avertissement Wild), conditions des portails de zone, diagnostics
  `PORTAL_*` (13 codes).
- **PlugAdmin** : `PortalPages` (cartes filtrables + fiche en 3 blocs : Emplacement, Téléportation, Règles
  appliquées par le serveur, + État), route `/portals`, groupe « Monde » (Mondes, **Portails**, Voyages),
  `PORTAL_READ` (Admin, Testeur, Builder, Lecture seule), catalogue `portal.list` seul, `DiagnosticHelp`
  PORTAL_*, fiche `/docs/portails`, icône `portal`, renvoi depuis la fiche d'un monde. Seul formulaire :
  « Rafraîchir les portails ».
- **Correctif PR #580** : le contrôle visuel avec les données réelles a montré des mots coupés au milieu
  (`word-break:break-all` hérité de `/worlds`) ; portée CSS `.p-scope` propre à `/portals`.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/travel/PortalOverview.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/PortalListDetails.java`
- `src/test/java/com/lodygames/rpgquest/travel/PortalOverviewTest.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/PortalListActionTest.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/PortalPages.java`
- `control-panel/src/main/resources/docs/portails.md`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/PortalsPageTest.java`
- `docs/claude-reports/assets/564-portails/*.png` (liste, fiche Wild, fiche Claims ; 1280 et 390 px)

## Fichiers modifiés
- Plugin : `travel/WorldPortalRegistry.java`, `travel/WildEntryWarningService.java`,
  `claim/ClaimWorldAccessGuard.java`, `claim/ClaimArrivalResolver.java`, `web/agent/AgentActionType.java`,
  `web/agent/AgentActions.java`, `web/agent/AgentActionExecutor.java`, `web/agent/BukkitAgentActions.java`,
  `bootstrap/RPGQuestBootstrap.java`, `src/test/.../travel/WorldPortalRegistryTest.java`.
- PlugAdmin : `AgentActionCatalog`, `Permission`, `Role`, `AgentPages`, `PanelApp`, `Layout`, `Icons`,
  `DiagnosticHelp`, `WorldPages`, `assets/plugadmin.css`, `docs/_index.txt`.
- Docs : `docs/RPGQUEST_BIBLE.md` §6, `docs-site/worlds.html`, `docs/current_state.md`,
  `docs/MANUAL_TEST_PLAN.md` (TC-363), `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`.

## Base de données / migrations
Aucune.

## Configuration / données
Aucune clé ajoutée. Sur DEV : `world-portals/hub_to_wild.yml` et `hub_to_claims.yml` seulement **lus**
(registre déjà en mémoire) ; aucun fichier écrit, aucun portail, monde, bâtiment ni donnée joueur touché ;
`lody_test_flat` non créé.

## Tests automatiques
- Local ciblé (plugin) : `PortalOverviewTest` (7), `PortalListActionTest` (2), `WorldPortalRegistryTest` (23 dont
  1 nouveau) + non-régression `WildEntryWarningServiceTest` (12), `ClaimWorldAccessGuardTest` (20),
  `WorldPortalTeleportListenerTest` (18), `CompositeWorldPortalEntryGuardTest` (4), `PortalServiceTest` (15),
  `YamlPortalRegistryTest`, `PortalLoaderTest`… — 0 échec.
- Local ciblé (panel) : `PortalsPageTest` (5), `WorldsPageTest` (5), `DiagnosticHelpTest` (7), `DocsPagesTest`,
  `DocLibraryTest`, `RolePermissionMatrixTest`, catalogues — 0 échec (après correction de deux titres
  contenant une apostrophe, qui cassaient l'ancre de doc).
- CI GitHub complète verte sur PR #579, sur le merge `46c01a1` (garde du script de déploiement) et sur PR #580.

## Tests manuels à effectuer
**TC-363 — PENDING MANUAL VALIDATION** (`docs/MANUAL_TEST_PLAN.md`) : Monde → Portails, deux fiches, texte de
l'avertissement comparé à la fenêtre en jeu, traversée des deux portails (comportement identique), aucun bouton
mutatif, lecture sur téléphone. Aucun test Minecraft n'a été fait par Claude.

## Résultat attendu
Deux cartes actives `hub_to_claims` et `hub_to_wild`, vraies coordonnées, « point sûr aléatoire » et
« claim du joueur, sinon point sûr », pastilles Avertissement Wild / Accès Claims + retour, 0 problème.

## Reset / retour à l'état initial
Rien à réinitialiser : la page n'écrit rien.

## Déploiement VeryGames
**Effectué le 19/10/2026** depuis le worktree propre détaché :
- JAR (`46c01a1`) : `RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --wait-ci` → CI verte sur
  `46c01a1`, « JAR en ligne : 3736477 octets (== local) », « Déploiement terminé » ; sauvegarde
  `rpgquest-20261019T100031Z-predeploy.jar` (version `294514e`). Seul LoDyMcFly en ligne → un
  `scripts/verygames-restart.sh --timeout 300` → ONLINE. JAR distant re-téléchargé : SHA-256
  `e1bbbb684c6d6ca98805e05e12e4cfbcbe632413dbd89a2d915ea809743a0bf7` = local, 13 classes `PortalOverview*` /
  `PortalListDetails` présentes.
- PlugAdmin : `scripts/plugadmin/deploy.sh` (sans sudo) à `46c01a1` puis à `209a5b2` (correctif) — ONLINE ;
  JAR servi contient `PortalPages`, `docs/portails.md` et la règle `.p-scope` ; `GET /portals` → 303 vers
  `/login` (route enregistrée).
- Vérification réelle : action `portal.list` sur DEV (ligne `created_by claude-564`) → SUCCESS « 2 portail(s),
  2 actif(s) » ; `hub_to_wild` world_hub 737,66,-673 → 740,69,-673 → `wild` (chargé) RANDOM_SAFE 500–5000,
  20 tentatives, repli spawn 0,85,0, garde WILD_WARNING ; `hub_to_claims` 730,66,-676 → 732,69,-676 → `claims`
  (chargé) CLAIM_HOME 64–2000, écart 100, 40 tentatives, garde CLAIMS_ACCESS ; aucun diagnostic, aucun fichier
  ignoré, aucun portail de zone.

### À transférer
JAR RPGQuest (`46c01a1`) + PlugAdmin (`209a5b2`). Le correctif #580 ne touche que PlugAdmin.

### Ne PAS transférer/altérer
`data.db`, `config.yml`, `world-portals/`, `portals/`, `destinations/`, mondes, bâtiments, autres plugins.

### Redémarrage requis
Oui pour le JAR (fait). PlugAdmin redémarré par son script.

### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh` (JAR `294514e` sauvegardé ci-dessus) + redémarrage ; PlugAdmin :
`scripts/plugadmin/rollback.sh app`. Aucune donnée à reprendre.

## Logs / diagnostic
- Journaux locaux : `/srv/rpgquest/564-deploy.log`, `/srv/rpgquest/564-panel-deploy.log`,
  `/srv/rpgquest/564-panel-deploy-2.log`.
- Fichier de portail invalide : diagnostic `PORTAL_FILE_SKIPPED` en tête de page (avant : seulement un
  avertissement console).

## Documentation mise à jour
Bible §6 (sous-section « Page PlugAdmin Portails »), `docs-site/worlds.html`, `docs/current_state.md`,
`docs/MANUAL_TEST_PLAN.md` (TC-363), `docs/deployment/SERVER_CHANGELOG.md`, fiche panel `docs/portails.md`,
registre `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 6d-ter ajoutée, décision 11 mise à jour ; rien de `main` à
reporter : `e52e0eb` y était déjà).

## Limitations / travail restant
- Pas d'action `portal.detail` séparée : la fiche se construit depuis le relevé `portal.list` (complet).
- Le nom affiché est déduit (« Portail vers le Wild ») : un vrai nom stocké relève de #565.
- Les gardes Wild/Claims restent liés au nom du monde d'arrivée (point 8 de l'étude) : la page le montre, ne
  le change pas.
- Captures faites sur un rendu statique de la page (même HTML/CSS que le panel, sans barre de navigation).

## Prochaine étape suggérée
Propriétaire : TC-363. Aucun autre lot lancé : #565–#570 et #561–#563 attendent un nouvel accord.
