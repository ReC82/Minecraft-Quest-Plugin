# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 14:21
* Sujet : #566 (#228-P3) — portails : destinations (point fixe et regard, stratégies existantes, monde absent ou arrivée dangereuse, portail retour, téléportation asynchrone, vérifier sans appliquer)
* Statut : PARTIAL (livré, déployé et relu sur DEV en lecture seule ; validation en jeu soumise à un feu vert du propriétaire)
* Branche Git : `feature/566-portails-destinations` (PR #699, fusionnée dans `chore/257-ci-github-actions` → `839489b3`) ; rapport : `docs/566-rapport`
* Commit actuel si disponible : `839489b3`
* Début de la tâche : 2026-10-21 13:24:57
* Fin de la tâche : 2026-10-21 14:21:57
* Durée totale : 00:57:00

## Demande
Ticket #566 lancé par le superviseur lqorch. Destination d'un portail entre mondes : monde réel + stratégies existantes
(`WORLD_SPAWN`, `RANDOM_SAFE`, `CLAIM_HOME`) + **point fixe** (x, y, z, regard) ; refus si monde absent/déchargé ou
arrivée dangereuse (recherche locale bornée sinon refus) ; aller-retour = second portail distinct en brouillon ;
`teleportAsync` + chunks asynchrones sans changer Hub → Wild / Hub → Claims ; « Vérifier sans appliquer ».
Autorisation propriétaire du 20/10 : **tests simulés uniquement** — aucune destination/portail réel modifié, aucune
téléportation, aucun monde ni chunk chargé pour vérifier.

## Analyse
- Moteur unique conservé : `WorldPortalRegistry` / `world-portals/<id>.yml` (décision Q2), étendu de façon
  rétrocompatible. `Destination` (portails de zone) n'est pas réutilisé tel quel : elle porte un id et vit dans un autre
  dossier ; le point fixe est un sous-record `WorldPortalDefinition.FixedPoint` de même forme (x, y, z, yaw, pitch).
- Rollback : un JAR antérieur lit `destination-strategy: FIXED_POINT` comme invalide → fichier ignoré, il ne téléporte
  personne ailleurs que prévu.
- « Monde absent » : choix **refuser avec message** (jamais charger) — conforme à l'interdiction de charger un monde.
- Asynchronisme limité au point fixe : les trois stratégies historiques gardent leur chemin synchrone (aucun risque pour
  `hub_to_wild` / `hub_to_claims`, comme exigé).
- MockBukkit n'implémente ni `getChunkAtAsync` ni `teleportAsync` : découvert pendant les tests (trois tests
  d'abord « skipped », puis une exception avalée dans la chaîne asynchrone). Correction : adaptateur
  `AsyncWorldAccess` (Paper par défaut, simulé en test) **et** journalisation + message si le chemin asynchrone lève.

## Travail effectué
Plugin :
- `DestinationStrategy.FIXED_POINT` ; `WorldPortalDefinition.fixedPoint` / `returnOf` (+ `withDestination`) ;
  YAML `destination-point` (x, y, z obligatoires) et `return-of`, écrits et comptés dans l'empreinte **seulement s'ils
  sont renseignés** (fichiers et empreintes historiques identiques — test dédié).
- `ArrivalSafety` : verdict d'une position (mêmes règles que `RandomSafeLocationFinder`), recherche bornée (±2 en X/Z,
  ±3 en Y, 175 positions, la plus proche d'abord), copie de blocs seulement si les chunks sont déjà chargés.
- `WorldPortalTeleportListener` : point fixe → `getChunkAtAsync` puis contrôle, `teleportAsync`, regard appliqué ;
  refus (message, joueur sur place) si aucune position sûre ou arrivée dans un portail actif.
- `WorldPortalAdminService` : arrivée à la création et en modification (`dest_mode=set`) pour un portail non actif,
  refus `POINT_REQUIRED`, `POINT_OUT_OF_WORLD`, `POINT_INSIDE_PORTAL`, `DESTINATION_NOT_LOADED`,
  `CLAIM_HOME_OUTSIDE_CLAIMS`, `ACTIVE_DESTINATION_LOCKED` ; mêmes règles revérifiées à l'activation ; portail retour
  (`return_of`) : `RETURN_MISMATCH`, `RETURN_EXISTS`, `RETURN_TARGET_NOT_FOUND` ; audit avec le point.
- `WorldPortalVerifier` + action `portal.verify` (lecture seule ; état relevé sur le thread principal, contrôles hors
  thread principal).
- `portal.list` : `destinationPoint`, `pointX…pointPitch`, `returnOf`, `returnedBy`, diagnostic
  `PORTAL_RETURN_TARGET_MISSING`. `/rpgadmin worldportal create … fixed_point` refusé.
PlugAdmin :
- Catalogue : `portal.verify` (`PORTAL_READ`), `FIXED_POINT`, champs point/regard, `dest_mode`, `return_of`.
- Fiche : « Point d'arrivée », « Retour de » / « Portail retour », bloc **Arrivée** (verrouillé si actif), bouton
  « Préparer le portail retour » (création préremplie), section **Vérifier sans appliquer** (dernier résultat, statuts,
  alerte si le portail a changé depuis).
- Rendu vérifié localement (instance isolée port 8097, dernier `portal.list` DEV copié en lecture seule + un brouillon
  fictif et un résultat de vérification fictif dans la base locale seulement), Chromium 1280 et 390 px : aucun
  débordement horizontal. Deux défauts vus à l'écran et corrigés : monde d'arrivée actuel absent du sélecteur (aurait
  été remplacé en silence), titre « Arrivée » en double.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/travel/ArrivalSafety.java`, `WorldPortalVerifier.java`
- `src/test/java/com/lodygames/rpgquest/travel/ArrivalSafetyTest.java`, `WorldPortalDestinationTest.java`

## Fichiers modifiés
`DestinationStrategy`, `WorldPortalDefinition`, `WorldPortalRegistry`, `WorldPortalTeleportListener`,
`WorldPortalAdminService`, `PortalOverview`, `RandomSafeLocationFinder` (`isDangerous` partagé), `RpgAdminCommand`,
`AgentActionType`, `AgentActions`, `AgentActionExecutor`, `BukkitAgentActions`, `PortalAdminParams`,
`PortalListDetails` ; tests `WorldPortalTeleportListenerTest`, `PortalAdminParamsTest` ; panel `AgentActionCatalog`,
`AgentPages`, `PortalPages`, `PortalForms`, `DiagnosticHelp`, `docs/portails.md`, `PortalsPageTest` ; docs
`RPGQUEST_BIBLE.md`, `current_state.md`, `docs-site/worlds.html`, `MANUAL_TEST_PLAN.md` (TC-391),
`deployment/SERVER_CHANGELOG.md` ; registre `docs/SUIVI_VALIDATIONS_DEV.md` (PR de rapport, avec report de la
section « Confirmation propriétaire #625 » de `main`).

## Base de données / migrations
Aucune.

## Configuration / données
Aucune clé de configuration. Champs YAML facultatifs `destination-point`, `return-of` — aucun sur DEV. Aucun portail,
fichier `world-portals/`, monde, chunk, joueur ni donnée DEV touché ; seules actions envoyées : `portal.list` et deux
`portal.verify` (lecture seule).

## Tests automatiques
- Locaux ciblés : `travel.*` + `web.agent.*` + `ClaimWorldAccessGuardTest` (52 classes, 0 échec, 0 ignoré après
  l'adaptateur) ; panel `PortalsPageTest`, `DiagnosticHelpTest`, `AgentActionCatalogTest`, `RolePermissionMatrixTest`,
  `DocsPagesTest` (0 échec).
- CI GitHub PR #699 (run 39595635886) : plugin, control-panel, web-api, scripts, artefacts — **verts** ; CI de la fusion
  `839489b3` (run 39597141098) **verte**.

## Tests manuels à effectuer
TC-391 — **PENDING MANUAL VALIDATION**. Étapes 1-2 en lecture seule ; étapes 3-7 (brouillon avec point fixe, activation,
arrivée dangereuse, portail retour) **seulement sur feu vert du propriétaire**. Aucun test en jeu fait par Claude.

## Résultat attendu
`hub_to_wild` / `hub_to_claims` inchangés (vérifié : empreintes identiques à la formule d'avant, `portal.verify` OK) ;
un portail point fixe amène au point avec son regard, ou refuse proprement ; un portail retour est un second brouillon.

## Reset / retour à l'état initial
Supprimer un brouillon de test depuis sa fiche. Aucune donnée joueur concernée.

## Déploiement VeryGames
**Effectué le 21/10 vers 14:19.**
- JAR `839489b3` par `scripts/deploy-verygames.sh -y` (CI verte sur la fusion, aucun test rejoué) : « JAR en ligne :
  4084363 octets (== local) », SHA-256 `c4ecdb34…`, **retéléchargé et identique** ; backup
  `rpgquest-20261021T121806Z-predeploy.jar` (`db5fb794…` = JAR #625, 4045101 o, plus petit que le nouveau).
- Seul LoDyMcFly connecté (RCON `list`) → `scripts/verygames-restart.sh --timeout 300` : arrêt puis retour ONLINE.
- PlugAdmin `839489b3` par `scripts/plugadmin/deploy.sh` : `/health` ONLINE, `DEPLOYED` = `839489b3`, jar servi
  contenant `portal.verify` et l'aide « Vérifier sans appliquer ».
- Relu sur DEV : `portal.list` (2 actifs, zones/stratégies inchangées), `portal.verify` OK × 2 (6 contrôles chacun).
### À transférer
JAR RPGQuest `839489b3` ; PlugAdmin `839489b3` (AWS).
### Ne PAS transférer/altérer
`world-portals/*.yml`, `config.yml`, `data.db`, emplacements, mondes.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` puis redémarrage ; PlugAdmin : `scripts/plugadmin/rollback.sh` (release
précédente). Un portail `FIXED_POINT` éventuel serait ignoré par l'ancien JAR.

## Logs / diagnostic
Refus au passage journalisés « Portail simple <id> (FIXED_POINT) : arrivée … non sûre … téléportation refusée » ;
erreur inattendue du chemin asynchrone : ERROR avec la pile, joueur prévenu. Traces `[TP-TRACE]` inchangées.

## Documentation mise à jour
Bible (section #566 + stratégie dans la table worldportal), `current_state.md`, `docs-site/worlds.html`, aide panel
`portails.md`, TC-391, `SERVER_CHANGELOG.md`, registre de validation.

## Limitations / travail restant
- Asynchronisme seulement pour le point fixe (les stratégies historiques restent synchrones, par choix de non-régression).
- Pas d'option « charger le monde s'il est déchargé » : refus seulement.
- La vérification ne lit la sécurité du point que si ses chunks sont déjà chargés (« Non vérifiable maintenant » sinon).
- Point fixe saisi à la main (pas encore de capture « ma position » depuis le jeu).
- Conditions d'accès et test de téléportation contrôlé : #567.

## Prochaine étape suggérée
Propriétaire : TC-391 étapes 1-2 (lecture seule), puis feu vert éventuel pour un brouillon de test ; superviseur : #567.
