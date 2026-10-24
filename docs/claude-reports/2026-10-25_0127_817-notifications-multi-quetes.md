# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-25
* Heure : 01:27
* Sujet : #817 — notifications simultanées de plusieurs quêtes lisibles (ActionBar, titres)
* Statut : DONE (livré et déployé sur DEV ; recette en jeu TC-430 PENDING MANUAL VALIDATION, ticket laissé ouvert)
* Branche Git : `fix/817-notifications-multi-quetes` (PR #845) → `chore/257-ci-github-actions` (merge `e17afa22`) ; rapport : `docs/817-rapport`
* Commit actuel si disponible : `e17afa22`
* Début de la tâche : 2026-10-25 00:25:38
* Fin de la tâche : 2026-10-25 01:27:40
* Durée totale : 01:02:02

## Demande
Session autonome lqorch, ticket #817 : pendant la récolte de chêne, les notifications de plusieurs quêtes
(Bois du jour, La requête du bûcheron, Le stock de Tania) se superposent et deviennent illisibles. Inventorier les
canaux, prioriser (quête terminée/récompense > étape suivante > compteur), ne perdre aucune récompense, pas de
compteur périmé, compteurs attribuables, courts, en français, petites résolutions ; ne pas modifier le parcours de Bob.

## Analyse
Canaux inventoriés dans `QuestProgressEngine`, `ProgressionService`, `PlayerHudService` :

| Canal | Contenu | Collision réelle |
|---|---|---|
| ActionBar (`ActionBarFeedback`) | compteurs, XP RPG, pièces | **oui** : une bûche → 3 segments « Quête · Bûche de chêne x/20 » (≈ 700 px estimés pour 320 px d'écran minimal) ; « +20 XP RPG » / « +5 LodyCoins » d'une quête terminée **collés** au compteur d'une autre (attribution trompeuse) ; « 20/20 » d'une étape close réaffiché sur les lignes suivantes (fenêtre de regroupement de 2 s) |
| Title/Subtitle | Quête commencée / terminée | **oui** : deux fins sur le même événement → la seconde efface la première |
| Chat | étape suivante, bilans de récompenses | non : chaque bloc nomme sa quête ; inchangé |
| BossBar de suivi (#71) | quête suivie | non (hors périmètre, #71) |

## Travail effectué
- `feedback.ActionBarFeedback` (refonte) : progression structurée (`Progress` : quête, objectif, libellé, forme seule,
  forme d'entrée) ; ligne mono-quête inchangée (`quest.objective-progress`) ; multi-quêtes : libellé une fois puis
  entrée courte par quête (nouvelle clé `quest.objective-progress-entry`, titre abrégé 12 car.) ; budget 300 px
  estimés (même estimateur que la bossbar #71, `TrackedQuestSummary.estimatedWidthOf`, rendu public) : titres
  12→10→8, puis libellé commun retiré (si un seul objectif), puis anciens compteurs → « +N » ; infos (XP, pièces)
  **mises en file** pendant la fenêtre et affichées seules dès qu'elle se libère, au plus tard 2 s, 2 dernières sans
  doublon ; `forgetQuest` appelé à la clôture d'étape, à la remise et à l'abandon.
- `feedback.QuestTitleQueue` (nouveau) : fin de quête affichée ou mise en file (≤ 3, au-delà titre abandonné, bilan chat
  toujours envoyé) ; « Quête commencée » jamais par-dessus une fin.
- Câblage dans `RPGQuestBootstrap` (planification Bukkit `runTaskLater`, ms → ticks).
- Estimation de largeur : « · » compté 2 px (glyphe fin) — plus juste aussi pour la bossbar.
- Parcours de Bob, quêtes, dialogues : non modifiés.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/feedback/QuestTitleQueue.java`
- `src/test/java/com/lodygames/rpgquest/feedback/QuestTitleQueueTest.java`
- `src/test/java/com/lodygames/rpgquest/quest/progress/QuestMultiQuestNotificationTest.java`
- ce rapport

## Fichiers modifiés
- `feedback/ActionBarFeedback.java`, `quest/progress/QuestProgressEngine.java`, `bootstrap/RPGQuestBootstrap.java`,
  `ui/TrackedQuestSummary.java`, `src/main/resources/messages.yml`, `ActionBarFeedbackTest.java`
- docs : `RPGQUEST_BIBLE.md`, `docs-site/quests.html`, `current_state.md`, `MANUAL_TEST_PLAN.md` (TC-430),
  `deployment/SERVER_CHANGELOG.md`, `SUIVI_VALIDATIONS_DEV.md`, `claude-reports/README.md`

## Base de données / migrations
Aucune.

## Configuration / données
Nouvelle clé `quest.objective-progress-entry` ajoutée automatiquement au `messages.yml` du serveur au démarrage
(fusion des clés manquantes, aucune clé existante touchée). Aucune progression, quête ni joueur modifiés.

## Tests automatiques
- Locaux ciblés (RPGQUEST_TEST_MAX_HEAP=768m) : `feedback.*`, `QuestMultiQuestNotificationTest`,
  `QuestProgressEngineTest`, `ui.*`, messages, progression, HUD — **235 tests verts**.
- CI GitHub PR #845 (2 runs) verte : plugin, control-panel, web-api, scripts, artefacts ; CI du commit de fusion
  `e17afa22` verte.
- Nouveaux cas : trois quêtes sur une bûche ≤ 300 px et attribuées ; libellé retiré avant tout masquage ; « +N » en
  dernier recours, le plus récent toujours visible ; étape close oubliée sans toucher aux autres ; info en file au lieu
  d'être collée, une seule fois, délai borné malgré une récolte continue ; deux fins = deux titres successifs ; file
  bornée sans boucle ; début jamais sur une fin ; joueur déconnecté ; vrais `BlockBreakEvent` (fin de quête annoncée +
  bilan chat attribué, compteur retiré ensuite, dix bûches sans message chat).

## Tests manuels à effectuer
TC-430 (`docs/MANUAL_TEST_PLAN.md`) — **PENDING MANUAL VALIDATION**. Aucun test en jeu n'a été fait par Claude.

## Résultat attendu
Une ligne d'ActionBar courte où chaque compteur porte le nom (abrégé) de sa quête, jamais hors de l'écran ; XP/pièces
seules juste après ; « Bois du jour 20/20 » disparaît après la fin ; deux fins simultanées → deux titres successifs et
deux bilans chat.

## Reset / retour à l'état initial
Rien à réinitialiser (aucune donnée modifiée).

## Déploiement VeryGames
Effectué le 25/10 à 01:25 : `scripts/deploy-verygames.sh -y` (validation = CI verte sur `e17afa2`), JAR 4 404 838 octets,
SHA-256 retéléchargé = local (`bc505c88453af584…`, contient `QuestTitleQueue`), puis `scripts/verygames-restart.sh`
(0 joueur connecté avant et après).
### À transférer
JAR RPGQuest uniquement (fait).
### Ne PAS transférer/altérer
`messages.yml` (fusion automatique), `data.db`, quêtes, dialogues, mondes, `config.yml`.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune ; seule la clé `messages.yml` est ajoutée automatiquement.

## Rollback
`scripts/rollback-verygames.sh` vers `rpgquest-20261024T232538Z-predeploy.jar` (SHA-256 `6503f2375134…`, JAR `9d0cd6c5`
de #54), puis redémarrage. L'ancien JAR ignore la clé ajoutée.

## Logs / diagnostic
`~/build-logs/817-t1…t6.log` (tests locaux), `~/build-logs/817-deploy.log`, `~/build-logs/817-restart.log`.
Au premier essai, le test d'intégration lisait « +8 XP RPG (Global) » envoyé par le **plugin chargé par MockBukkit**
(son propre canal) : artefact de test, lecture filtrée sur les lignes de compteurs.

## Documentation mise à jour
Bible (canal ActionBar + file des titres), docs-site `quests.html`, `current_state.md`, TC-430, changelog serveur, registre.

## Limitations / travail restant
- Largeurs estimées (police vanilla, noms traduits majorés) : la recette en grand facteur GUI reste à faire (TC-430 étape 6).
- Au-delà de 3 fins de quête simultanées, le 4e titre n'est pas montré (bilan chat toujours envoyé).
- Les messages `quest.objective-progress` déjà personnalisés sur le serveur restent ceux du serveur.

## Prochaine étape suggérée
Recette TC-430 par le propriétaire lors d'une récolte de chêne avec plusieurs quêtes actives.
