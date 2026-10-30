# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-30
* Heure : 03:38
* Sujet : #817 rév. 2 — ActionBar multi-quêtes : une quête par ligne, titres sans balise (suite au rejet TC-430 du 29/10)
* Statut : PARTIAL (code fusionné, non déployé : déploiement DEV seulement sur OK écrit du propriétaire ; TC-430 rév. 2 PENDING MANUAL VALIDATION)
* Branche Git : `fix/817-actionbar-lisible` (PR #933) → `chore/257-ci-github-actions` ; rapport : `docs/817-rapport-rev2`
* Commit actuel si disponible : `94afdfb2` (code), merge `63008541` (PR #933)
* Début de la tâche : 2026-10-30 03:02:36
* Fin de la tâche : 2026-10-30 03:40:00
* Durée totale : 00:37:24

## Demande
Session autonome lqorch sur #817. Retour propriétaire du 29/10 17:45 (TC-430 REJETÉ, « Bof ») : capture en jeu « `Oak Log : <gold>Bois... 2/16, <gold>La re... 2/20` ». À corriger : (1) aucune fuite de balises MiniMessage ; (2) politique ActionBar lisible à plusieurs quêtes, pas de concaténation tronquée ; (3) ne perdre aucun événement étape/récompense ; (4) tests 2–3 quêtes sur le même bloc, largeurs/GUI scale ; (5) rendus simulés avant de redemander une récolte. Pas de reset de quêtes/joueurs, déploiement DEV seulement sur OK écrit.

## Analyse
* Cause de la fuite : `QuestProgressEngine.showObjectiveProgress` passait `q.title().base()` — titre **MiniMessage brut** (`<gold>Bois du jour`) — à `ActionBarFeedback.shortTitle`, qui le coupait à 12 caractères, puis l'insérait par `Placeholder.unparsed("quest", …)` : la balise était affichée comme du texte, et la coupe pouvait tomber au milieu d'une balise.
* Cause de l'illisibilité : la rév. 1 regroupait sur **une** ligne jusqu'à 3 compteurs (« libellé : titre abrégé n/N, titre abrégé n/N »), abrégés jusqu'à 8 caractères — titres méconnaissables même sans balise.
* `TrackedQuestSummary` (bossbar, #71) savait déjà produire un titre en texte brut (`plainTitle` privé, `MM.stripTags` + jetons PNJ) et couper au pixel (`truncate`) : réutilisés.
* Le libellé d'objectif est un composant **traduit côté client** (« Oak Log » / « Bûche de chêne ») dont l'estimation serveur (`oak_log` → 60 px) est inférieure à la largeur réelle française (75 px) : marge ajoutée.

## Travail effectué
* `ActionBarFeedback` réécrit en **file par joueur** (rotation) :
  * une seule progression (ou une seule information) par ligne, jamais de concaténation ;
  * chaque ligne reste ≥ 1,5 s (`SLOT_MILLIS`) ; ce qui arrive entre-temps attend son tour dans l'ordre d'arrivée ;
  * forme seule `quest.objective-progress` (« Bûche de chêne 5/20 ») tant qu'une seule quête est en jeu ; dès que deux quêtes se partagent la série : `quest.objective-progress-named` (« Bois du jour · Bûche de chêne 2/16 »), titre **texte brut** coupé au pixel (budget 300 px − marge 20 px − reste de la ligne) ;
  * objectif en attente mis à jour sur place ; objectif à l'écran réaffiché aussitôt avec sa nouvelle valeur sans prolonger son tour (récolte continue : les quêtes alternent, aucune affamée) ;
  * aucune progression en attente perdue ; informations (XP, pièces, niveau) en file, dédoublonnées, 3 gardées ; une progression arrivée < 250 ms après une info (même kill, #69) passe devant, l'info suit ;
  * quête terminée/abandonnée : ses progressions en attente sortent de la file ; l'appel à la clôture d'**étape** est retiré (l'objectif de l'étape suivante a une autre clé ; le « 20/20 » final est montré une fois, c'est l'événement).
  * Cas limite couvert : un message arrivé après la fin d'un tour mais avant le tick planifié ne double pas la file.
* `TrackedQuestSummary.plainTitle(String, npcNames)` et `truncate` rendus publics (réutilisation, pas de duplication).
* `QuestProgressEngine` : titre brut converti en texte brut avant l'ActionBar ; forme nommée avec `Placeholder.unparsed` (jamais réinterprété) + composant d'objectif.
* `messages.yml` : clé `quest.objective-progress-entry` retirée du modèle embarqué (le serveur garde la sienne, inutilisée et sans effet) ; commentaires mis à jour.
* Rendu **simulé** (planche Playwright/Chromium, largeurs de glyphes de la police par défaut, écran minimal 320×240 = tout facteur GUI) : `docs/claude-reports/assets/817-actionbar/actionbar-817-simulation.png` (+ `sheet.html` source) — avant (capture 29/10) / après t=0, 1,5 s, 3 s, 4,5 s (XP), titre très long (300 px), quête seule, compteur à 3 chiffres. **Ce n'est pas une capture en jeu.**
* Docs : `RPGQUEST_BIBLE.md` (règle ActionBar), `current_state.md`, `docs-site/quests.html`, `MANUAL_TEST_PLAN.md` (TC-430 rév. 2), `SERVER_CHANGELOG.md` (entrée NON DÉPLOYÉ), registre `SUIVI_VALIDATIONS_DEV.md`.

## Fichiers créés
* `docs/claude-reports/assets/817-actionbar/actionbar-817-simulation.png`, `docs/claude-reports/assets/817-actionbar/sheet.html`
* ce rapport

## Fichiers modifiés
* `src/main/java/com/lodygames/rpgquest/feedback/ActionBarFeedback.java`
* `src/main/java/com/lodygames/rpgquest/quest/progress/QuestProgressEngine.java`
* `src/main/java/com/lodygames/rpgquest/ui/TrackedQuestSummary.java`
* `src/main/resources/messages.yml`
* `src/test/java/com/lodygames/rpgquest/feedback/ActionBarFeedbackTest.java` (réécrit, 17 cas)
* `src/test/java/com/lodygames/rpgquest/quest/progress/QuestMultiQuestNotificationTest.java` (titres colorés, planificateur horodaté)
* `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs-site/quests.html`, `docs/MANUAL_TEST_PLAN.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune configuration nouvelle. `messages.yml` du serveur : rien à faire (`objective-progress-named` y existe déjà ; `objective-progress-entry` devient inutilisée). Aucune donnée joueur touchée, aucun reset.

## Tests automatiques
* Local (`RPGQUEST_TEST_MAX_HEAP=768m`, `:test` filtré) : **115 tests, 0 échec** — `ActionBarFeedbackTest`, `QuestMultiQuestNotificationTest`, `TrackedQuestSummary*`, `QuestProgressEngineTest`, `QuestTitleQueueTest`, `PlayerHud*`, `ProgressionService*`.
* Un premier passage filtré `*Messages*` a fait échouer `WorldPortalAccessListenerTest.customPreparingSuccessAndWelcomeMessagesAreSentInOrder` (message « Rune de rappel » en plus) ; relancé seul avec les tests #817 : **vert** — instabilité liée à l'ordre d'exécution, sans rapport avec ce changement (non corrigé ici).
* CI GitHub PR #933 : **verte** (2 runs : Tests plugin, control-panel, web-api, Scripts, Artefacts, CI OK), fusionnée `63008541`.

## Tests manuels à effectuer
TC-430 rév. 2 — **PENDING MANUAL VALIDATION** (aucun test en jeu par Claude). Après OK écrit et déploiement : casser une bûche avec 2–3 quêtes de bûches actives → une quête par ligne, l'une après l'autre (≈ 1,5 s), aucune balise `<…>`, aucune virgule entre compteurs ; récolte continue → alternance ; fin d'une quête → titre + bilan chat, XP/pièces seules ensuite ; plus grande taille d'interface.

## Résultat attendu
« Bois du jour · Bûche de chêne 2/16 », puis « La requête du bûcheron · Bûche de chêne 2/20 », puis « Le stock de Tania · Bûche de chêne 2/20 », puis « +20 XP RPG (Quêtes) » le cas échéant — voir la planche simulée.

## Reset / retour à l'état initial
Revenir au JAR précédent (`c5f1c323`). Aucune donnée à restaurer.

## Déploiement VeryGames
**Non effectué** (le propriétaire a demandé un OK écrit préalable). À transférer : JAR RPGQuest seul (scripts officiels `deploy-verygames.sh` avec `RPGQUEST_TEST_MAX_HEAP=768m -y`, après CI verte du merge). Ne pas altérer : `data.db`, `messages.yml`, `config.yml`, quêtes. Redémarrage DEV requis. Aucune migration. Rollback : JAR sauvegardé par le script (`rpgquest-<date>-predeploy.jar`, actuellement `c5f1c323`).

## Risques / points d'attention
* Avec 3 quêtes, un cycle complet dure ≈ 4,5 s : le compteur d'une quête peut avoir 1–2 bûches de retard pendant qu'une autre est affichée (chacune réaffiche sa dernière valeur à son tour).
* Les largeurs sont des **estimations** (police par défaut, libellés traduits) : la validation visuelle réelle reste au propriétaire.
* `WorldPortalAccessListenerTest` instable selon l'ordre (préexistant).

## Prochaine étape
Propriétaire : regarder la planche simulée ; si elle convient, écrire « OK déploiement #817 » sur le ticket → la session suivante déploie le JAR sur DEV, puis TC-430 rév. 2 en jeu.
