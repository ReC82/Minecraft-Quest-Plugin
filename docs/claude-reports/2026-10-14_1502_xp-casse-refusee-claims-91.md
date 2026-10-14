# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 15:02
* Sujet : Issue #91 — aucune XP ni progrès de quête sur une casse refusée hors claim
* Statut : DONE
* Branche Git : `fix/91-xp-casse-refusee` (PR #372, fusionnée dans `chore/257-ci-github-actions`)
* Commit actuel si disponible : `dd56cc1` (correctif), merge `8094690`
* Début de la tâche : 2026-10-14 14:34:48
* Fin de la tâche : 2026-10-14 15:02:21
* Durée totale : 00:27:33

## Demande
Session autonome lqorch, ticket #91 : dans le monde Claims, une casse refusée hors de son claim
affichait « +9 XP (Global) ». Aucune XP, aucun overlay, aucun progrès `BREAK_BLOCK` ne doit
résulter d'une action annulée ; auditer les autres protections (Hub, claim d'autrui, zones).

## Analyse
Audit de tous les écouteurs `BlockBreakEvent` / `BlockPlaceEvent` :

| Rôle | Classe | Priorité avant |
|---|---|---|
| Protection | `ClaimProtectionListener`, `ClaimsWorldRulesListener`, `HubWorldProtectionListener`, `ZoneProtectionListener` | `NORMAL` |
| Protection | `TravelBeaconProtectionListener`, `WaypointProtectionListener` | `HIGH` |
| Effet de jeu | `MiningXpListener`, `FarmingXpListener`, `QuestBlockBreakListener`, `QuestBlockPlaceListener`, `PlacedBlockTracker.onPlace` | `NORMAL` |
| Les deux | `ResourceNodeBreakListener` (annule lui-même pour prendre la main) | `NORMAL` |

Tous avaient `ignoreCancelled = true`, mais à priorité égale Bukkit suit l'ordre
d'enregistrement : `RPGQuestBootstrap` enregistre l'XP (l. 562) **avant** les claims (l. 637). L'XP
était donc réellement créditée (pas seulement affichée) avant l'annulation du claim — farm
possible. Même défaut potentiel avec les balises/waypoints (`HIGH`, après l'XP) et pour
`PlacedBlockTracker` (une pose refusée marquait la position « posée par un joueur »).

## Travail effectué
- Protections de casse/pose (claims, monde Claims, Hub, zones) : `LOW`. Elles passent avant
  `ResourceNodeBreakListener` (`NORMAL`), qui ne consomme donc plus un nœud sur une casse refusée.
- XP minage/agriculture : `HIGHEST` (ils modifient `expToDrop`, donc pas `MONITOR`).
- Objectifs `BREAK_BLOCK`/`PLACE_BLOCK` et `PlacedBlockTracker.onPlace` : `MONITOR` (observateurs purs).
- Balises/waypoints laissés en `HIGH` (toujours avant `HIGHEST`/`MONITOR`).
- Comportement inchangé pour un nœud de ressource : il annule lui-même en `NORMAL`, donc ni XP ni
  quête sur sa récolte, exactement comme avant (consommateurs déjà enregistrés après lui).

## Fichiers créés
- `src/test/java/com/lodygames/rpgquest/BlockEventPriorityContractTest.java`
- ce rapport

## Fichiers modifiés
- `claim/ClaimProtectionListener.java`, `claim/ClaimsWorldRulesListener.java`,
  `hub/HubWorldProtectionListener.java`, `zone/ZoneProtectionListener.java`
- `progression/listener/MiningXpListener.java`, `progression/listener/FarmingXpListener.java`,
  `progression/PlacedBlockTracker.java`
- `quest/progress/QuestBlockBreakListener.java`, `quest/progress/QuestBlockPlaceListener.java`
- `src/test/.../progression/listener/MiningXpListenerTest.java`
- `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md` (TC-309),
  `docs/claude-reports/README.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune.

## Tests automatiques
- `BlockEventPriorityContractTest` : toute protection de casse/pose a une priorité strictement
  inférieure à tout effet de jeu, et chaque effet de jeu a `ignoreCancelled` (échoue sur l'état
  d'avant : `NORMAL` = `NORMAL`).
- `MiningXpListenerTest#aBreakCancelledByAProtectionRegisteredLaterGrantsNoMiningXp` : dispatch
  MockBukkit réel, protection `LOW` enregistrée **après** l'XP → événement annulé, 0 XP.
- Local ciblé (contrat, MiningXp, `claim.*`, Hub, Zone, PlacedBlock, QuestProgressEngine) : 263
  tests, 0 échec. CI GitHub de la PR #372 et du merge `8094690` : verte (suite complète).

## Tests manuels à effectuer
`docs/MANUAL_TEST_PLAN.md` TC-309 — **PENDING MANUAL VALIDATION** (aucun test en jeu effectué).

## Résultat attendu
Casse/pose refusée (hors claim, claim d'autrui, Hub, zone) : bloc intact, aucun « +XP », aucune XP
créditée, aucun progrès de quête. Dans son claim : XP et quêtes inchangées.

## Reset / retour à l'état initial
Rien à réinitialiser.

## Déploiement VeryGames
Effectué sur DEV : `deploy-verygames.sh -y --wait-ci` sur `8094690` (CI verte → assemble seul),
JAR 2 821 347 o, SHA-256 `5a07f113…cfb`, retéléchargé et identique ; backup
`rpgquest-20261014T130025Z-predeploy.jar` (2 821 117 o) ; `verygames-restart.sh` → ONLINE,
RPGQuest 0.1.0-SNAPSHOT actif. Le propriétaire (LoDyMcFly) était connecté au redémarrage.
### À transférer
Le JAR RPGQuest uniquement.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml`, mondes, autres plugins.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh` ; aucune donnée à reprendre.

## Logs / diagnostic
Aucun nouveau log ajouté.

## Documentation mise à jour
`docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md` (TC-309).

## Limitations / travail restant
- Un plugin externe qui annulerait une casse en `HIGHEST` après l'XP n'est pas couvert (aucun sur DEV).
- Ticket #91 laissé ouvert jusqu'à la validation TC-309.

## Prochaine étape suggérée
Propriétaire : dérouler TC-309 sur DEV puis fermer #91.
