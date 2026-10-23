# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-23
* Heure : 17:20
* Sujet : #15 — attribution automatique des quêtes quotidiennes/hebdomadaires annoncée en une ligne, sans titre (retour propriétaire du 23/10)
* Statut : DONE (livré et déployé DEV ; validation en jeu TC-422 PENDING MANUAL VALIDATION)
* Branche Git : `feature/15-annonces-periodiques` (PR #793, fusionnée dans `chore/257-ci-github-actions`) ; rapport : `docs/15-rapport-annonces-periodiques`
* Commit actuel si disponible : merge `a2b68cde` (code `45845c90`, docs `89d9280f`)
* Début de la tâche : 2026-10-23 16:38:38
* Fin de la tâche : 2026-10-23 17:17:20
* Durée totale : 00:38:42

## Demande
Commentaires propriétaire du 23/10 (09:02 et 09:05 UTC) sur #15 : le renouvellement quotidien est validé ; corriger
uniquement les notifications d'attribution automatique des quotidiennes ET hebdomadaires — aucun titre plein écran
« Quête commencée », aucun message individuel ni détail d'objectifs, un seul message synthétique par catégorie
réellement renouvelée, pas de répétition à la reconnexion dans la même période (mémoire persistante par joueur et
catégorie, sans migration destructive). Préserver les quêtes ordinaires (#636 compris), fins, récompenses, échéances,
message d'expiration.

## Analyse
- `PeriodicQuestService#startAssigned` démarre chaque quête via `QuestProgressEngine#startAssignedPeriodic`, qui passe
  par `accept` → `showQuestStarted` : titre « Quête commencée » + en-tête + une ligne par objectif, **pour chaque quête**.
- Le message synthétique existait (« ✦ Nouvelles quêtes quotidiennes : ouvre ton journal de quêtes. ») mais seulement
  quand l'attribution venait d'être créée dans la passe ; une attribution restée `ASSIGNED` (joueur parti avant le
  démarrage) était démarrée plus tard sans annonce, et rien n'était mémorisé.

## Travail effectué
- `QuestProgressEngine#accept` : pour `startAssignedPeriodic` (`periodicAssigned = true`), `showQuestStarted` n'est
  plus appelé (ni titre, ni objectifs). Le reste (persistance, `refreshCumulativeDiscoveryObjectives`, notification de
  changement, suivi) est inchangé. Les quêtes ordinaires gardent exactement leur annonce.
- `PeriodicQuestService` : `startAssigned` rend le nombre de quêtes réellement démarrées ; nouvelle étape
  `announceOnce` par catégorie : si la période courante n'est pas celle mémorisée dans la variable joueur
  `__periodic_announced_daily__` / `__periodic_announced_weekly__`, elle est enregistrée puis — seulement si quelque
  chose est nouveau pour le joueur (attribution créée ou quête démarrée dans cette passe) — une ligne est envoyée :
  « ✦ Nouvelles quêtes quotidiennes disponibles : ouvre ton journal. » (idem hebdomadaires). Joueur hors ligne : rien
  n'est enregistré (l'annonce aura lieu au démarrage, à la connexion). Joueurs déjà servis par l'ancienne version
  (tout démarré, rien mémorisé) : période enregistrée sans message, donc aucune réannonce au déploiement.
- La chaîne par joueur existante sérialise connexion et cadence de reset : un seul message même s'ils se croisent.

## Fichiers créés
- `docs/claude-reports/2026-10-23_1720_quetes-periodiques-annonce-unique-15.md`

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/quest/progress/QuestProgressEngine.java`
- `src/main/java/com/lodygames/rpgquest/quest/periodic/PeriodicQuestService.java`
- `src/test/java/com/lodygames/rpgquest/quest/progress/PeriodicQuestServiceTest.java` (+6 tests)
- `docs/RPGQUEST_BIBLE.md`, `docs-site/quests.html`, `docs/MANUAL_TEST_PLAN.md` (TC-422)
- `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 21 #15, Partiel), `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune migration. Deux variables joueur (`player_variables`, table existante) par joueur ayant débloqué les quêtes
périodiques, écrites à sa prochaine connexion / au prochain renouvellement.

## Configuration / données
Aucune. `periodic-quests` DEV inchangé (2/1, 04:00 Europe/Paris, lundi). Aucune attribution, progression ni
récompense modifiée ; aucun renouvellement forcé.

## Tests automatiques
- Local : `PeriodicQuestServiceTest` (13, dont 6 nouveaux : une ligne par catégorie sans titre ni objectifs ;
  reconnexion et redémarrage dans la même période → rien ; renouvellement quotidien avec deux rafraîchissements
  croisés → une seule ligne quotidienne, aucune hebdomadaire ; message d'expiration conservé ; joueurs servis avant la
  mémoire non réannoncés ; quête ordinaire → titre + objectifs conservés), `QuestJournalServiceTest`,
  `DialogueSessionEngineTest` (#636) : verts.
- CI GitHub PR #793 (push + pull_request) verte ; CI du commit de fusion `a2b68cde` verte.

## Tests manuels à effectuer
TC-422 — PENDING MANUAL VALIDATION. Procédure courte :
1. Se connecter après le prochain renouvellement (04:00) : aucun titre « Quête commencée », une ligne
   « ✦ Nouvelles quêtes quotidiennes disponibles : ouvre ton journal. » (hebdomadaire seulement un lundi) ;
   les « ⌛ Quête expirée » restent.
2. Se reconnecter le même jour : aucune annonce.
3. Accepter une quête ordinaire chez un PNJ : annonce habituelle.
4. (Facultatif) rester connecté à 04:00 : une seule ligne dans la minute.

## Résultat attendu
Voir TC-422 ; progression, récompenses, échéances et expiration identiques à avant.

## Reset / retour à l'état initial
Rien à réinitialiser. Les variables `__periodic_announced_*__` peuvent rester en base.

## Déploiement VeryGames
Effectué le 23/10/2026 17:15 : `scripts/deploy-verygames.sh -y` (validation « CI verte sur a2b68cd »), JAR
4 378 962 octets, SHA-256 `ba668be841fcc04c…` retéléchargé par FTP et identique ; redémarrage
`scripts/verygames-restart.sh` à 17:16 (seul LoDyMcFly en ligne), RPGQuest actif (`plugins`).
### À transférer
Le JAR RPGQuest seul (fait).
### Ne PAS transférer/altérer
`data.db`, `config.yml`, quêtes, dialogues ; PlugAdmin inchangé (aucun changement panel).
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh` avec `rpgquest-20261023T151453Z-predeploy.jar` (JAR `04afc09f`, SHA `b80c13a6…`),
puis redémarrage.

## Logs / diagnostic
Journaux locaux : `~/deploy-15-l3.log`, `~/restart-15-l3.log`.

## Documentation mise à jour
Bible (quêtes périodiques), docs-site `quests.html` (tableau des annonces), TC-422, registre, changelog serveur.

## Limitations / travail restant
- TC-422 et TC-377 à valider en jeu par le propriétaire ; aucun test Minecraft n'a été fait par Claude.
- Décisions toujours en attente : valeurs de démonstration, expiration sans récompense, réglages `periodic-quests`
  modifiables depuis PlugAdmin / activation par modèle. Petit reste connu : les formulaires d'action de /quests ne
  conservent pas le paramètre `tab`.

## Prochaine étape suggérée
Après retour TC-422/TC-377 : corriger les remarques ; puis, selon décision, réglages périodiques administrables (#335).
