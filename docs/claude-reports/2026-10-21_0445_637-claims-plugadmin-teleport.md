# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 04:45
* Sujet : #637 — PlugAdmin : consulter les claims par joueur et s'y téléporter pour diagnostic
* Statut : PARTIAL (livré et testé automatiquement ; validation en jeu TC-385 en attente du propriétaire)
* Branche Git : `feature/637-claims-plugadmin` (PR #682 → `chore/257-ci-github-actions`)
* Commit actuel si disponible : `17618c05`, `fa13d7ca`, `c14438da` ; fusion PR #682 `c8e83f0a`
* Début de la tâche : 2026-10-21 04:25:01
* Fin de la tâche : 2026-10-21 05:50:33
* Durée totale : 01:25:32

## Demande
Ticket #637 (demande propriétaire du 20/10, non prioritaire) : depuis PlugAdmin, consulter les claims de chaque
joueur (principal identifié, propriétaire, ID stable, monde, position, limites, palier si disponible ; absent ≠
indisponible ; hors ligne OK ; vue globale filtrable) et une action « M'y téléporter » réservée au personnage de
l'administrateur connecté, cible + destination affichées avant, confirmation, jamais automatique, destination sûre,
refus explicite, aucun succès avant TP confirmé, audit, permissions lecture/TP distinctes, sans propriété ni
membership ni bypass #35 ni réécriture de l'unlock #321. Audit préalable obligatoire : réutiliser l'existant.
Interdits : TP réel par la session, toute modification de claim, de terrain ou de droit.

## Analyse
Audit de la branche d'intégration `chore/257-ci-github-actions` (`1075d1d7`) :

| Brique | État avant | Utilisation |
|---|---|---|
| `ClaimService` (claims chargés de la base, `mainClaimOf`, `find`, `hasClaimTierOne`) | livré | seule source du relevé et de la TP |
| `ClaimTeleportService#findSafeArrivalInClaim` (centre puis colonnes du claim, `RandomSafeLocationFinder`) | livré (Jo, `/claim admin tp`, CLAIM_HOME) | réutilisé tel quel |
| `/claim admin tp <joueur>` (en jeu) | livré | **limité** : claim principal seulement, annonce « Téléporté » avant l'arrivée effective (`teleportAsync` non attendu) |
| Règles du monde des claims (`ClaimWorldSafetyListener`, `AdminBypassMode` #35) | livré | reprises en **contrôle préalable** : un admin non éligible serait renvoyé au Hub juste après l'arrivée → faux succès évité |
| Pont compte PlugAdmin ↔ joueur (#200, `McBridgeDirectory`) | livré | désigne le personnage déplacé (« personnage lié ») |
| Annuaire joueurs #96 / fiche joueur #328 | livré | carte « Claims » ajoutée à la vue d'ensemble |
| Suivi d'action panel.js, toasts, `submit_token`, audit `agent.action.create` | livré | réutilisés sans changement |
| Action agent / page / permission claims | **manquant** | livré par ce lot |

## Travail effectué
**Plugin**
- `claim.ClaimOverview` (pur) : relevé de tous les claims — propriétaire (UUID + pseudo connu du serveur, `null`
  sinon), principal (`mainClaimOf`), monde chargé ou non, bornes, réservation, dimensions, centre, palier déduit de
  la taille réelle (carré = côté d'un `ClaimTier`, sinon « forme libre »), membres, redstone publique ;
  `available=false` distinct d'un relevé vide.
- `claim.ClaimAdminTeleport` : claim relu (refus `UNKNOWN_CLAIM`, `CLAIM_CHANGED` si le propriétaire diffère de celui
  affiché), personnage connecté (`OFFLINE`), monde chargé (`WORLD_UNAVAILABLE`), une demande à la fois par personnage
  (`BUSY`), éligibilité au monde des claims contrôlée **avant** (bypass actif, claim possédé ou `CLAIM_TIER_1` ;
  sinon `NOT_ELIGIBLE`, rien écrit), position sûre du claim (`NO_SAFE_LOCATION` sinon), `teleportAsync` attendu
  (`REFUSED` si annulé), **relecture 10 ticks après** (`BOUNCED` si le personnage n'est plus sur le claim) →
  `TELEPORTED` seulement alors. Journaux `[claim-admin-tp]` demande / résultat.
- Actions agent `claim.list` et `claim.teleport` (`AgentActionType`, `AgentActions`, `AgentActionExecutor` —
  validation de l'id et de l'UUID du propriétaire —, `ClaimListDetails`, `BukkitAgentActions#setClaimAdmin`,
  câblage `RPGQuestBootstrap`).

**PlugAdmin**
- Permissions `CLAIM_READ` (Propriétaire, Administrateur, Testeur) et `CLAIM_TELEPORT` (Propriétaire,
  Administrateur) ; Lecture seule n'a pas accès (positions des bases).
- Page `/claims` (`ClaimPages`, nav « RPGQuest → Claims ») : cartes filtrables (Principaux / Autres / Monde non
  chargé), recherche pseudo / UUID / claim / monde, `?owner=` (claims d'un joueur, « ne possède aucun claim »),
  fiche d'un claim (propriétaire, emplacement, palier, réservation).
- « M'y téléporter » : personnage déplacé + destination affichés, liste des conditions, case de confirmation,
  `submit_token` ; côté serveur (`PanelApp#createAgentAction`) le paramètre `player` est **remplacé** par l'UUID du
  personnage lié à la session (refus si aucun), et une seule téléportation en attente par personnage. « Dernière
  demande » rappelle l'état réel (en cours tant que le serveur n'a pas relu la position).
- Carte « Claims » sur la fiche joueur (vue d'ensemble).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/claim/ClaimOverview.java`
- `src/main/java/com/lodygames/rpgquest/claim/ClaimAdminTeleport.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/ClaimListDetails.java`
- `src/test/java/com/lodygames/rpgquest/claim/ClaimOverviewTest.java`
- `src/test/java/com/lodygames/rpgquest/claim/ClaimAdminTeleportTest.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/ClaimActionsTest.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/ClaimPages.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/ClaimsPageTest.java`
- ce rapport

## Fichiers modifiés
- Plugin : `AgentActionType`, `AgentActions`, `AgentActionExecutor`, `BukkitAgentActions`, `RPGQuestBootstrap`
- PlugAdmin : `Permission`, `Role`, `AgentActionCatalog`, `AgentPages`, `PanelApp`, `Layout`, `Icons`,
  `resources/docs/claims.md`
- Docs : `docs/RPGQUEST_BIBLE.md` (section 8), `docs/CLAIMS.md`, `docs/current_state.md`,
  `docs/MANUAL_TEST_PLAN.md` (TC-385), `docs-site/worlds.html`, `docs/SUIVI_VALIDATIONS_DEV.md`,
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Aucune table, aucune colonne ; le panel n'ajoute que des permissions d'énumération (rôles prédéfinis).

## Configuration / données
Aucune clé de configuration. Aucun claim, terrain, variable, droit ni mode bypass modifié par le code ni par la
session. Aucune téléportation réelle exécutée.

## Tests automatiques
- `ClaimOverviewTest` 2/0, `ClaimAdminTeleportTest` (MockBukkit) 6/0, `ClaimActionsTest` 4/0 — plugin.
- `ClaimsPageTest` 6/0 ; non-régression `RolePermissionMatrixTest` 16/0, `PortalsPageTest` 6/0,
  `PlayerAdminPageTest` 11/0, `ActionReturnPathTest`, `EffectivePermissionsTest` verts — PlugAdmin.
- MockBukkit n'implémente pas `teleportAsync` : le déplacement est injectable et remplacé en test par une
  téléportation synchrone au même contrat (booléen). `/claims` rendu en local (Playwright, 1280 et 390 px, données
  synthétiques) : liste, fiche, filtre par joueur, aucun débordement horizontal.
- CI : PR #682 verte (plugin, control-panel, web-api, scripts, artefacts — deux exécutions) ; CI du commit de fusion `c8e83f0a` verte (run 39535366474). `deploy-verygames.sh` a relancé `./gradlew test` + `build` : verts.

## Tests manuels à effectuer
TC-385 — PENDING MANUAL VALIDATION (propriétaire, avec son personnage lié et l'accord du propriétaire du claim visité).

## Résultat attendu
Le propriétaire voit tous les claims réels, les retrouve par joueur (hors ligne compris), et peut se rendre sur un
claim d'autrui pour vérifier, mode bypass OFF, que la casse y est refusée (validation manquante de #35).

## Reset / retour à l'état initial
Rien à réinitialiser : aucune donnée écrite. Pour revenir en arrière : rollback JAR et PlugAdmin (ci-dessous).

## Déploiement VeryGames
### À transférer
- JAR `c8e83f0a` transféré par `scripts/deploy-verygames.sh -y` : 3 942 414 octets, SHA-256
  `3916077365a3773b7638526ef0e1a9de88b9932c046ee14303aca71e1c678a5d` ; **retéléchargé par FTP et comparé : identique**,
  contient `ClaimAdminTeleport`.
- PlugAdmin `c8e83f0a` déployé par `scripts/plugadmin/deploy.sh` (validation = CI verte sur `c8e83f0`), service redémarré
  05:49, `/health` ONLINE, `ClaimPages` présent dans le JAR servi ; app précédente sous `/opt/plugadmin/releases/20261021-054945`.
### Ne PAS transférer/altérer
`config.yml`, `data.db`, contenus (quêtes/dialogues), claims : rien à changer.
### Redémarrage requis
Oui pour le plugin (le JAR n'est lu qu'au démarrage). **DEV est toujours arrêté** (RCON refusé à 04:3x, dernier heartbeat
agent 2026-10-20 23:02Z, depuis l'arrêt RCON de 01:03) : aucun redémarrage tenté (garde-fou auto-reboot VeryGames). Le
propriétaire démarre le serveur depuis le panel VeryGames ; le nouveau JAR sera chargé. Tant que DEV est arrêté, « Rafraîchir
les claims » restera en attente (aucun agent).
### Migration automatique
Aucune.

## Rollback
- Plugin : `scripts/rollback-verygames.sh --latest` → backup `rpgquest-20261021T034901Z-predeploy.jar` (3 924 540 octets,
  SHA `214e66d0…`, version `f3cbc72c` #183).
- PlugAdmin : restaurer `/opt/plugadmin/releases/20261021-054945` (version `03c66a0e`) à la place de `/opt/plugadmin/app`
  puis redémarrer `plugadmin.service`.

## Logs / diagnostic
Serveur : lignes `[claim-admin-tp] demande : …` et `[claim-admin-tp] résultat : … — <CODE>`. Panel : historique
`/actions` (type « Me téléporter sur un claim (diagnostic) »), audit `agent.action.create` (refus « aucun personnage
Minecraft lié », « téléportation déjà en attente »).

## Documentation mise à jour
Bible section 8 (« Page PlugAdmin Claims et téléportation de diagnostic »), `docs/CLAIMS.md`, fiche PlugAdmin
`docs/claims.md`, `docs-site/worlds.html`, `docs/current_state.md`, TC-385, registre, changelog serveur.

## Limitations / travail restant
- Le personnage déplacé est toujours le personnage lié à la session : choisir un autre administrateur connecté n'est
  pas proposé (choix délibéré, plus strict que le ticket — un compte sans liaison voit une explication).
- Le palier affiché est déduit de la géométrie ; le droit déclaratif `CLAIM_TIER_n` n'est pas relu pour chaque claim.
- Pas de suppression ni d'édition de claim (hors périmètre).

## Prochaine étape suggérée
Propriétaire : démarrer DEV depuis le panel VeryGames, puis TC-385 ; puis reprendre la validation #35 (casse refusée dans le claim d'autrui, bypass OFF).
