# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 04:23
* Sujet : #183 (retour propriétaire du 20/10) — consulter sa progression RPG sans commande depuis le journal de quêtes
* Statut : PARTIAL (livré et transféré sur DEV ; DEV arrêté, validation en jeu TC-384 en attente)
* Branche Git : feature/183-progression-journal (PR #680) → chore/257-ci-github-actions ; rapport : docs/183-rapport-progression-journal
* Commit actuel si disponible : f3cbc72c (merge PR #680)
* Début de la tâche : 2026-10-21 03:02:24
* Fin de la tâche : 2026-10-21 04:23:45
* Durée totale : 01:21:21

## Demande
Session autonome lqorch. Ajouter une consultation de la progression RPG **sans commande**, depuis le journal / menu
existant (pas de nouvel écran concurrent) : par piste, niveau, XP courante / requise, jauge lisible, pourcentage, niveau
maximal géré, en français, sans identifiant technique, dans les limites du client 1.21.11 (#475). Réutiliser les services de
progression (lecture seule). Interdit : modifier XP, niveau, récompense ; migration destructive.

## Analyse
- Progression : `ProgressionService` (cache en mémoire par UUID, chargé à la connexion) + `ProgressionCurve`
  (`levelForTotalXp`, `xpIntoCurrentLevel`, `xpToNextLevel`, 0 au niveau max). Pistes = `SkillType` : GLOBAL, COMBAT,
  MINING, FARMING, FISHING, EXPLORATION. `/skills` faisait déjà ce calcul en chat.
- Journal (`QuestJournalService`) : la barre du haut de la liste (9 cases) est **pleine** (4 onglets, page préc./indicateur/
  suiv., bourse, fermer). La bourse (slot 7) était inerte (#16).
- Décision : la bourse de la **liste** porte le niveau RPG global + jauge et devient le point d'entrée (clic) vers une
  page « Ma progression RPG » de 27 cases (même taille que la vue détail). La bourse de la vue détail reste inerte. Pas de
  pagination nécessaire : 6 objets, infobulles de 6 lignes (< 10, limite #475).

## Travail effectué
- `SkillType#label()` : libellé joueur unique ; `ProfileCommand.label` et `ProgressionService.label` délèguent (refactor,
  commit séparé 578617e9).
- `ui.ProgressionGauge` (record pur) : niveau, niveau max, XP dans le niveau / requise, pourcentage arrondi vers le bas
  (99 % max tant que le niveau n'est pas atteint, 100 % seulement au niveau max), barre de 10 cases « ■ » (vertes, dorées au
  max, grises vides), titre, résumé une ligne, infobulle.
- `ui.ProgressionJournalView` : un objet par piste (bouteille d'XP, épée, pioche, blé, canne, boussole ; attributs masqués) +
  une ligne « d'où vient l'XP ».
- `QuestJournalService` : `setProgression`, résumé sous la bourse (« Niveau RPG N ■■■■□□□□□□ 45 % », « Clic : voir ma
  progression »), `showProgression` (rendu synchrone sur le cache, jeton de rendu), `handleProgressionClick` (Retour slot 18
  → même onglet/page, Fermer slot 26 au tick suivant), rafraîchissement sur changement de progression de quête.
  `JournalKind.PROGRESSION`, `JournalSession.progression(...)`, routage dans `QuestJournalListener` (tous les clics
  restent annulés). Branchement dans `RPGQuestBootstrap`.
- Sans service de progression branché, comportement d'avant (bourse inerte).

## Fichiers créés
- src/main/java/com/lodygames/rpgquest/ui/ProgressionGauge.java
- src/main/java/com/lodygames/rpgquest/ui/ProgressionJournalView.java
- src/test/java/com/lodygames/rpgquest/ui/ProgressionGaugeTest.java
- docs/claude-reports/2026-10-21_0423_183-progression-journal.md

## Fichiers modifiés
- progression/model/SkillType.java, command/ProfileCommand.java, progression/ProgressionService.java
- ui/QuestJournalService.java, ui/JournalKind.java, ui/JournalSession.java, ui/QuestJournalListener.java
- bootstrap/RPGQuestBootstrap.java
- test ui/QuestJournalServiceTest.java (+5 tests)
- docs/PROGRESSION.md, docs/RPGQUEST_BIBLE.md, docs-site/quests.html, docs/current_state.md, docs/MANUAL_TEST_PLAN.md (TC-384),
  docs/deployment/SERVER_CHANGELOG.md, docs/SUIVI_VALIDATIONS_DEV.md (ligne 11e), docs/claude-reports/README.md

## Base de données / migrations
Aucune. Lecture seule du cache en mémoire ; aucune écriture SQL.

## Configuration / données
Aucune clé de configuration, aucun contenu, aucune donnée joueur modifiée.

## Tests automatiques
- Local ciblé (`RPGQUEST_TEST_MAX_HEAP=768m ./gradlew :test --tests 'ui.*' 'progression.*' 'command.*'`) : 1er passage 7 échecs
  (nom de placeholder MiniMessage `nextLevel` invalide → `next_level`), 2e passage **vert**.
- `ProgressionGaugeTest` : 0 %, intermédiaire (45 %), 99 % jamais arrondi à 100, passage exact de niveau, niveau maximal,
  largeur de barre constante, aucun identifiant technique.
- `QuestJournalServiceTest` : résumé dans la bourse, 6 pistes dans l'ordre avec XP x / y et niveau max, joueur sans
  progression (niveau 1, 0 %, infobulle ≤ 10 lignes), page en lecture seule (clics annulés, XP inchangée) + Retour vers le même
  onglet, Fermer au tick suivant. Test « bourse inerte » existant conservé (sans service de progression).
- CI PR #680 : toute verte (plugin, control-panel, web-api, scripts, artefacts).
- `deploy-verygames.sh -y` : suite plugin verte en local puis assemble.

## Tests manuels à effectuer
TC-384 — PENDING MANUAL VALIDATION (docs/MANUAL_TEST_PLAN.md). Aucun test Minecraft n'a été effectué.
Pour Lloyd : démarrer DEV depuis le panel VeryGames ; en jeu, clic droit avec le Journal des quêtes → survoler le lingot d'or
« Bourse » (niveau RPG + jauge) → cliquer → vérifier les 6 jauges, Retour, Fermer. TC-347 toujours en attente.

## Résultat attendu
Le joueur voit son niveau RPG global sur la bourse du journal et, en un clic, toutes ses pistes avec niveau, XP courante /
requise, jauge et pourcentage, identiques à `/skills`.

## Reset / retour à l'état initial
Rien à réinitialiser (lecture seule). Retour arrière = rollback du JAR.

## Déploiement VeryGames
### À transférer
JAR `f3cbc72c` — transféré (SHA-256 `214e66d06996c8afa05f97f55b700095efb670d1282c5c44bd9d0816ea6b8bbc`, 3 924 540 octets),
retéléchargé et comparé : identique.
### Ne PAS transférer/altérer
config.yml, data.db, contenus, PlugAdmin (inchangé, `03c66a0e`).
### Redémarrage requis
Oui (le JAR n'est lu qu'au démarrage). **DEV est arrêté** depuis l'arrêt RCON de 01:03 (session #81) : RCON refusé à 03:2x et
04:2x. Aucun redémarrage tenté (garde-fou auto-reboot VeryGames) : le propriétaire démarre le serveur depuis le panel VeryGames,
le nouveau JAR sera chargé.
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` → backup `rpgquest-20261021T022210Z-predeploy.jar` (SHA `186f064d…`, version
`03c66a0e` #655).

## Logs / diagnostic
Journal de déploiement local : ~/buildlogs/183-deploy.log (« JAR en ligne : 3924540 octets (== local) », « Déploiement terminé »).

## Documentation mise à jour
PROGRESSION.md, RPGQUEST_BIBLE.md, docs-site/quests.html, current_state.md, MANUAL_TEST_PLAN.md (TC-384), SERVER_CHANGELOG.md,
SUIVI_VALIDATIONS_DEV.md (ligne #183 : TC-384 « À tester », prérequis démarrage DEV).

## Limitations / travail restant
- La page ne se rafraîchit pas en direct sur un gain d'XP hors quête (combat, minage) : rouvrir la page. Elle se recompose sur
  un changement de progression de quête.
- Décisions #183 toujours ouvertes : anciens niveaux vanilla, enchantement/enclume ; `xp-reward` des mobs sans effet.
- Ticket #183 laissé ouvert.

## Prochaine étape suggérée
Démarrage DEV par le propriétaire puis TC-384 et TC-347 ; ensuite décisions #183.
