# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-19
* Heure : 14:52
* Sujet : #542 — retours TC-361 du 19/10 : poursuite absente après créatif → survie, explosion qui détruit des blocs
* Statut : PARTIAL (correctif livré et déployé sur DEV ; revalidation en jeu TC-361-bis en attente — PENDING MANUAL VALIDATION)
* Branche Git : `fix/542-poursuite-explosion-sans-blocs` (PR #587, fusionnée dans `chore/257-ci-github-actions`, merge `8e4f8f6`) ; rapport : `docs/542-rapport-correctif-tc361`
* Commit actuel si disponible : `8e4f8f6`
* Début de la tâche : 2026-10-19 13:36:43
* Fin de la tâche : 2026-10-19 14:52:36
* Durée totale : 01:15:53

## Demande
Session autonome lqorch. Corriger ensemble les deux défauts signalés par le propriétaire sur TC-361 (#542) :
1. `zz542_cochon` ne poursuit pas le joueur après passage créatif → survie sur la même instance (il n'explose
   que si le joueur s'approche) ;
2. son explosion (power 1.0, set-fire false) a détruit 4 blocs de sable.

Acquis à préserver : passif en créatif, explosion au contact en survie, division bornée, rage. Interdits :
restaurer le terrain, faire apparaître un mob, toucher `zz542_cochon` ou un profil DEV.

## Analyse
- **Défaut 2 (blocs)** : `ExplosiveOnAttackExecutor` appelait
  `World#createExplosion(entity, loc, power, setFire, true)` — le dernier argument est **breakBlocks**.
  `set-fire: false` ne coupait que le feu. Le descripteur annonçait pourtant `WorldMutation.NONE` (Q11).
  `STRONGER_EXPLOSION` agrandit une explosion vanilla de creeper, qui casse des blocs selon `mobGriefing` :
  même problème potentiel. Aucune option de destruction n'existait ailleurs dans les profils.
- **Défaut 1 (poursuite)** : le code passait à `Pathfinder#moveTo(target, speed)` la **valeur de l'attribut**
  `MOVEMENT_SPEED` (0,25 pour un cochon). Or ce paramètre est un **multiplicateur** de la vitesse de base :
  le cochon avançait à un quart de sa vitesse (≈ immobile à l'œil, la navigation abandonne vite), d'où
  l'impression qu'il ne poursuit pas et n'explose que si le joueur vient à lui. L'acquisition de cible elle-même
  était correcte : le joueur est réévalué chaque seconde (créatif → survie pris en compte au tick suivant),
  ce que confirment les nouveaux tests.
- Le test historique de poursuite était **ignoré** (MockBukkit lève `UnimplementedOperationException` sur
  `getPathfinder()`), ce qui explique que le défaut ait passé les tests.

## Travail effectué
- `ExplosiveOnAttackExecutor` : explosion avec `breakBlocks = false` ; poursuite à multiplicateur fixe
  `CHASE_SPEED_MULTIPLIER = 1.25` (vitesse de panique d'un cochon vanilla) ; effets Paper isolés derrière
  une petite interface `WorldActions` (implémentation Paper par défaut) pour pouvoir les vérifier en test.
- `AbilityEngine#onEntityExplode` (priorité HIGHEST, `ignoreCancelled`) : garde centrale — l'`EntityExplodeEvent`
  d'un mob spécial (identité PDC) portant une capacité explosive **active** (`EXPLOSIVE_ON_ATTACK`,
  `STRONGER_EXPLOSION`) a sa liste de blocs vidée. Couvre aussi le chemin creeper vanilla renforcé. Les
  protections Hub/claims/zones ne font que retirer des blocs : elles restent compatibles.
- Javadoc `AbilityWorldPolicy` corrigée (terrain jamais modifié par une explosion de capacité).
- Aucune restauration de terrain : les 4 blocs de sable restent détruits (positions et état antérieur inconnus).
- Aucun profil modifié, aucun mob apparu, `zz542_*` non touchés.

## Fichiers créés
- `docs/claude-reports/2026-10-19_1452_542-correctif-poursuite-explosion.md` (ce rapport)

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/mob/ability/ExplosiveOnAttackExecutor.java`
- `src/main/java/com/lodygames/rpgquest/mob/ability/AbilityEngine.java`
- `src/main/java/com/lodygames/rpgquest/mob/ability/AbilityWorldPolicy.java` (javadoc)
- `src/test/java/com/lodygames/rpgquest/mob/ability/ExplosiveOnAttackExecutorTest.java`
- `SPECIAL_MOB_FORMAT.md`, `docs/RPGQUEST_BIBLE.md`, `docs/MANUAL_TEST_PLAN.md` (TC-361-bis),
  `docs/deployment/SERVER_CHANGELOG.md`
- `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 6c-bis → « Partiel », section #542, décision n° 10),
  `docs/claude-reports/README.md` (index)

## Base de données / migrations
Aucune. Schéma inchangé.

## Configuration / données
Aucune clé de configuration ni format modifiés ; aucun profil réécrit.

## Tests automatiques
- Nouveaux tests (`ExplosiveOnAttackExecutorTest`) : appel d'explosion avec `breakBlocks=false` ; garde
  `EntityExplodeEvent` (mob spécial explosif → liste vide ; creeper ordinaire → inchangé) ; poursuite d'un joueur
  en survie avec multiplicateur ≥ 1 (apparition directe en survie) ; même instance créatif (aucune poursuite)
  puis survie (poursuite). Le test de poursuite auparavant ignoré s'exécute désormais (10/10, 0 ignoré).
- Local ciblé `com.lodygames.rpgquest.mob.*` + `*BukkitAgentActionsMob*` : 219 tests, 0 échec, 9 ignorés
  (MockBukkit, préexistants) ; puis `mob.ability.*` : 44 tests, 0 échec.
- CI GitHub PR #587 : verte (Tests plugin, control-panel, web-api, Scripts, Artefacts).
- `scripts/deploy-verygames.sh` a relancé ses propres validations avant transfert : OK.

## Tests manuels à effectuer
**TC-361-bis** (`docs/MANUAL_TEST_PLAN.md`) — PENDING MANUAL VALIDATION :
1. Wild, sur du sable, loin des constructions, en créatif : « Tester » `zz542_cochon`, s'éloigner de 6–10 blocs → il ne suit pas.
2. Même instance, passer en survie → en 1–2 s il marche vers vous puis explose à 2 blocs.
3. **Aucun bloc détruit**, dégâts subis, pas de feu.
4. « Tester » directement en survie → poursuite immédiate puis explosion sans casse.
Puis reprendre TC-361 (étapes 1–2 et 5).

## Résultat attendu
Le cochon poursuit à une vitesse visible (≈ zombie) tout joueur en survie/aventure à ≤ 16 blocs, réévalué
chaque seconde ; aucune explosion de capacité ne casse de bloc.

## Reset / retour à l'état initial
Rien à réinitialiser (aucune donnée écrite).

## Déploiement VeryGames
Effectué le 19/10 à 14:50 sur DEV : `scripts/deploy-verygames.sh -y` depuis le worktree propre au commit
`8e4f8f6` ; backup daté `rpgquest-20261019T125012Z-predeploy.jar` (3 736 477 o, SHA-256 `e1bbbb68…`) ; nouveau
JAR 3 738 617 o, SHA-256 `3a59378dd3d23ba954dccecc1af2e76e60f3e453a5157cdb528dbe78265a4e20`, **re-téléchargé :
SHA identique** ; un seul redémarrage (`verygames-restart.sh`, seul LoDyMcFly connecté) → ONLINE à 14:51 ;
battement d'agent ONLINE 12:51:33Z ; `mob.list` (lecture) : 10 profils, 0 problème, 0 instance de test.
### À transférer
Le JAR RPGQuest seul.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `mobs/*.yml` (dont `zz542_*`), PlugAdmin (inchangé).
### Redémarrage requis
Oui (fait, une fois).
### Migration automatique
Aucune.

## Rollback
Remettre `rpgquest-20261019T125012Z-predeploy.jar` (`scripts/rollback-verygames.sh`) puis redémarrer ; aucune
donnée n'est écrite par ce changement.

## Logs / diagnostic
Journal de déploiement local `/tmp/deploy542b.log` (éphémère). Diagnostic : `mob.list` dans PlugAdmin.

## Documentation mise à jour
Bible (table des capacités + socle composable), `SPECIAL_MOB_FORMAT.md`, `MANUAL_TEST_PLAN.md` (TC-361-bis),
`SERVER_CHANGELOG.md`, registre `SUIVI_VALIDATIONS_DEV.md`.

## Limitations / travail restant
- Validation en jeu non faite par Claude (impossible) : TC-361-bis.
- Le cochon conserve ses goals vanilla (errance) : la poursuite est réimposée chaque seconde ; un léger
  écart de trajectoire entre deux recalculs reste possible.
- 4 blocs de sable détruits au premier essai : non restaurés (documenté).
- #543 reste en attente de l'accord du propriétaire.

## Prochaine étape suggérée
Le propriétaire joue TC-361-bis ; si OK, TC-361 restant puis décision sur #543.
