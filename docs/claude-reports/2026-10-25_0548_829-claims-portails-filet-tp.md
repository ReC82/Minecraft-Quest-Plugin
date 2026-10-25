# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-25
* Heure : 05:48
* Sujet : #829 — durcir l'accès au monde Claims : portails historiques, échecs du filet d'arrivée, `/tp` internes (points 1 à 3)
* Statut : DONE (livré et déployé sur DEV) — validation en jeu PENDING MANUAL VALIDATION (TC-436), deux décisions propriétaire ouvertes
* Branche Git : fix/829-claims-travel-hardening (PR #862, fusion `36fc6693` dans chore/257-ci-github-actions) ; rapport : docs/829-rapport
* Commit actuel si disponible : 36fc6693
* Début de la tâche : 2026-10-25 05:03:20
* Fin de la tâche : 2026-10-25 05:49:30
* Durée totale : 00:46:10

## Demande
Ticket #829 (lqorch), issu de l'audit #321 (PR #825). Seulement les points 1 à 3 :
1. les portails historiques (`PortalService`) ne passaient pas par `ClaimWorldAccessGuard` avant la téléportation ;
2. si la lecture en base échouait ou si aucun point du Hub ne se résolvait, le filet d'arrivée laissait le joueur dans Claims avec une Pierre de retour ;
3. un `/tp` à l'intérieur de Claims ne déclenchait aucun contrôle.

Interdits : modifier `/rpgadmin world tp claims` ou `/claim admin tp` (décision propriétaire en attente) ; reset ou mutation du claim de LoDyMcFly ; passer devant le portail Web.

## Analyse
- `PortalService.completeChanneling` cherchait la destination et la position sûre, débitait, puis téléportait. Aucun contrôle de monde. Seul `ClaimWorldSafetyListener` agissait à l'arrivée.
- `ClaimWorldAccessGuard.allowEntry` est synchrone : il refuse d'abord, puis relance lui-même le portail simple après la lecture asynchrone. Ce modèle ne convient pas aux portails à canalisation et à coût.
- Filet d'arrivée : en cas d'erreur de lecture, il donnait seulement une Pierre de retour. Si `hubReturnTarget` était vide (pas de spawn du village **et** pas de monde Hub), même chose. Dans les deux cas, le joueur restait sur place sans vérification.
- Le claim d'un propriétaire se lit en mémoire (`mainClaimOf`) : une panne de base ne le concerne pas. Seuls les joueurs sans claim passent par la lecture de `CLAIM_TIER_1`.
- La protection des blocs (`ClaimProtectionListener`, `Claim#isTrusted`) est indépendante de l'accès au monde. Être présent dans le monde n'a jamais donné de droit sur un claim, et cela n'a pas changé.

## Travail effectué
- **Point 1** : nouvelle interface `travel.PortalDestinationGuard` (asynchrone, `onAllowed` exécuté au plus une fois sur le thread principal). `ClaimWorldAccessGuard` l'implémente. Sa règle est factorisée dans `evaluate(...)`, commune aux portails simples et historiques, sans aucun changement de comportement pour les portails simples. `PortalService` consulte le garde **après** avoir résolu une destination sûre, et **avant** le débit et la téléportation (`chargeThenTeleport`). Le branchement est fait dans le bootstrap (`portalService.setDestinationGuard(claimWorldAccessGuard)`). Par défaut, `ALLOW_ALL` conserve le comportement historique.
- **Point 2** : `ClaimWorldSafetyListener`.
  - Lecture en échec : nouvel essai après 5 s, puis après 15 s, avec la Pierre de retour garantie pendant l'attente. Si la lecture échoue encore, le joueur est **ramené au Hub par précaution**, avec un message dédié. C'est le même choix que le portail, qui refuse l'entrée quand la base ne répond pas.
  - Pas de point de retour au Hub, point situé dans Claims, ou `teleport` refusé : mêmes essais. Si tout échoue : `ERROR` `[claims-safety] ÉCHEC du renvoi au Hub`, un message au joueur et une alerte aux administrateurs en ligne (`rpgquest.admin.world`).
  - Pas de boucle possible : un jeton est créé à chaque arrivée. La chaîne d'essais s'arrête dès que le joueur quitte le monde, se déconnecte ou arrive à nouveau. Le jeton est nettoyé à la déconnexion.
- **Point 3** : nouveau handler `PlayerTeleportEvent` (MONITOR, `ignoreCancelled`). Pour un déplacement qui reste dans Claims avec la cause `COMMAND`, `PLUGIN`, `SPECTATE` ou `UNKNOWN`, il rejoue le contrôle d'arrivée au tick suivant. Les déplacements de gameplay (perle de l'Ender, fruit de chorus…) ne sont pas contrôlés. L'arrivée dans le claim d'un autre joueur dont on n'est pas membre est seulement tracée (`[claims-safety] … sans en être membre`). Aucun droit n'est accordé ni retiré.
- **Non modifié**, conformément à la consigne : `/rpgadmin world tp claims`, `/claim admin tp`, les permissions, `plugin.yml`.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/travel/PortalDestinationGuard.java`
- `docs/claude-reports/2026-10-25_0548_829-claims-portails-filet-tp.md`

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/claim/ClaimWorldAccessGuard.java`
- `src/main/java/com/lodygames/rpgquest/claim/ClaimWorldSafetyListener.java`
- `src/main/java/com/lodygames/rpgquest/travel/PortalService.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/test/java/com/lodygames/rpgquest/claim/ClaimWorldAccessGuardTest.java` (+5)
- `src/test/java/com/lodygames/rpgquest/claim/ClaimWorldSafetyListenerTest.java` (+8)
- `src/test/java/com/lodygames/rpgquest/travel/PortalServiceTest.java` (+2)
- `docs/CLAIMS.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-436), `docs/deployment/SERVER_CHANGELOG.md`
- (PR de rapport) `docs/SUIVI_VALIDATIONS_DEV.md` (ligne #829 « À tester » et décisions ouvertes), `docs/claude-reports/README.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. Aucune donnée de joueur lue ou modifiée sur DEV ; claim et variables de LoDyMcFly non touchés.

## Tests automatiques
- Local, tests ciblés (`RPGQUEST_TEST_MAX_HEAP=768m`) : `ClaimWorldAccessGuardTest` **65/65**, `ClaimWorldSafetyListenerTest` **37/37**, `PortalServiceTest` **15 réussis + 2 sautés**, `BypassOnlyThroughAdminModeTest` OK. Les 2 tests sautés existaient déjà : `cooldownPreventsImmediateReuse` et `requiredLevelReadsTheSharedLevelRuleNotTheFrozenVanillaLevel`, parce que MockBukkit ne sait pas exécuter `teleportAsync`. Le nouveau test « garde favorable » se sert de cette exception pour constater que la téléportation est bien lancée après le débit.
- CI GitHub, suite complète (plugin, control-panel, web-api, scripts, artefacts) : **verte** sur la PR #862 et sur le commit de fusion `36fc6693`.

## Tests manuels à effectuer
**PENDING MANUAL VALIDATION**, TC-436 (`docs/MANUAL_TEST_PLAN.md`). Avec LoDyMcFly tel quel :
- `/tp` vers son propre claim : rien ne change ;
- `/tp` dans le claim d'autrui : il reste sur place, mais ne peut pas construire ;
- perle de l'Ender : aucune trace ;
- portail historique vers Claims, s'il en existe un.

Les refus ne se testent qu'avec un futur compte sans déblocage. Les pannes de base ou de Hub ne sont pas rejouables sur DEV sans l'altérer. Claude n'a fait aucun test en jeu.

## Résultat attendu
- Un joueur non éligible ne passe plus par un portail historique vers Claims. Il n'est pas déplacé et rien ne lui est débité.
- Un joueur ne reste plus dans Claims sans vérification quand la base ne répond pas.
- Un échec du renvoi au Hub est visible dans la console et signalé aux administrateurs.
- Un `/tp` interne applique la même règle qu'une arrivée.
- Les accès légitimes (propriétaire, `CLAIM_TIER_1 = true`, bypass explicite) sont inchangés.

## Reset / retour à l'état initial
Rien à annuler côté données. Retour au comportement précédent : rollback du JAR (ci-dessous).

## Déploiement VeryGames
### À transférer
JAR plugin seul, transféré par `scripts/deploy-verygames.sh -y` (CI verte reconnue sur `36fc669`). En ligne : 4 419 285 octets. JAR distant retéléchargé : SHA-256 `f55dec92…`, identique au JAR local, et la classe `PortalDestinationGuard` est présente.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, compte et claim de LoDyMcFly, contenus (quêtes, dialogues), PlugAdmin (non concerné).
### Redémarrage requis
Oui. Fait par `scripts/verygames-restart.sh` le 25/10 vers 05:45, avec 0 joueur connecté. DEV est de nouveau ONLINE.
### Migration automatique
Non.

## Rollback
`scripts/rollback-verygames.sh` vers `rpgquest-20261025T044511Z-predeploy.jar` (= JAR #852 `e24e0c75`), puis redémarrage.

## Logs / diagnostic
- `[claims-access] … via le portail historique <id> autorisée/REFUSÉE`
- `[claims-safety] contrôle d'éligibilité impossible … (essai n/3)`, puis `… renvoi au Hub par précaution`
- `[claims-safety] renvoi au Hub de … impossible (essai n/3 …)`, puis `ÉCHEC du renvoi au Hub`
- `[claims-safety] … téléporté (COMMAND) dans le claim « … » … sans en être membre`

## Documentation mise à jour
`docs/CLAIMS.md` (défaillances bornées, téléportations internes, portails historiques), `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-436), `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`, index des rapports. `docs-site/` n'est pas concerné : aucune nouvelle commande ni syntaxe.

## Limitations / travail restant
- **Décisions propriétaire ouvertes**, sans aucun changement :
  1. Faut-il une session bypass pour `/rpgadmin world tp claims` et `/claim admin tp` ?
  2. Faut-il refuser un `/tp` d'un joueur éligible vers le claim d'autrui ? Aujourd'hui, la présence est autorisée et tracée, sans aucun droit.
- Effet de bord assumé : un administrateur entré en bypass qui l'éteint, puis fait un `/tp` interne sans être éligible, est renvoyé au Hub, comme pour toute arrivée.
- Une lecture d'accès déjà en cours, lancée par un portail simple, n'est pas dupliquée pour un portail historique franchi au même instant. Le joueur devra alors refranchir ce dernier. C'est un cas théorique.
- Après une panne de base, un joueur avec `CLAIM_TIER_1 = true` mais **sans** claim est ramené au Hub par précaution. Il peut revenir par le portail une fois la base revenue.

## Prochaine étape suggérée
Le propriétaire déroule TC-436, étapes 1 à 3, avec LoDyMcFly, puis tranche les deux décisions ci-dessus. Le ticket reste ouvert.
