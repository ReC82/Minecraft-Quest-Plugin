# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-20
* Heure : 19:16
* Sujet : #15 lot 2 — quêtes quotidiennes / hebdomadaires : suivi joueur lisible sans survol, onglets PlugAdmin, `period` dans `quest.list`
* Statut : PARTIAL (livré et déployé sur DEV ; revalidation en jeu TC-377 et décisions propriétaire en attente)
* Branche Git : `feature/15-quetes-periodiques-lot2` (PR #643, fusionnée dans `chore/257-ci-github-actions`, merge `118de98e`) ; rapport : `docs/15-rapport-lot2`
* Commit actuel si disponible : `118de98e`
* Début de la tâche : 2026-10-20 18:28:36
* Fin de la tâche : 2026-10-20 19:16:59
* Durée totale : 00:48:23

## Demande
Session autonome `lqorch`, ticket #15. Retour de validation manuelle du propriétaire (TC-350, 20/10) :
acquis à préserver (« Bois du jour » 16/16, fin automatique, 20 XP, +5 LodyCoins, solde 105, « Terminée »).
À faire : (A) suivi joueur — résumé visible « Quotidiennes : 1/2 terminée — 1 restante » et équivalent
hebdomadaire, distinction en cours / terminées **sans survol**, terminées consultables pendant la période et
non mélangées aux quêtes classiques, compteurs calculés sur les **attributions réelles** ; (B) lot 2
PlugAdmin — onglets Quêtes « Quotidiennes » / « Hebdomadaires » (liste, création/édition via l'éditeur
guidé, activation, aperçu), `period` dans `quest.list`. Ne pas changer les valeurs de démonstration ni
l'expiration sans récompense (les afficher seulement). Interdit : reset, réattribution, modification des
récompenses, progrès, modèles `*_demo_*` ou de `periodic-quests` sur DEV.

## Analyse
- Lot 1 (`c6a45c9`) : onglets périodiques du journal = quêtes attribuées, statut uniquement dans
  l'infobulle de chaque quête ; pas de résumé ; état lu directement dans la progression (une attribution
  `ASSIGNED` non démarrée pouvait montrer l'état `COMPLETED` d'une période précédente).
- Dans un menu coffre Minecraft, seuls le **titre de la fenêtre**, la **matière**, la **brillance** et
  la **position** des objets se voient sans survol. La présentation repose donc sur ces quatre leviers.
- Max 9 quêtes par période (`PeriodicQuestConfig.MAX_COUNT`) : un onglet périodique tient toujours sur une
  page, l'indicateur de page y est inutile → réutilisé pour le résumé.
- PlugAdmin : `/quests` affiche un catalogue fusionné (source éditable + `quest.list`) ; l'éditeur guidé
  gère déjà `period` ; aucun champ « activé » par quête n'existe dans le format → « activation » =
  publication (modèle chargé par le serveur ou non). Un champ d'activation par modèle serait un
  changement de format : non fait, laissé à décision.

## Travail effectué
**Plugin — journal**
- `PeriodicProgress` (nouveau) : à partir des attributions de la période + états de progression ; une
  attribution non `STARTED` = « pas encore commencée » ; listes « à faire » / « terminées » dans l'ordre
  d'attribution ; textes « Quotidiennes : 1/2 terminée » et « … — 1 restante » (accords, « aucune restante »).
- `PeriodicJournalEntries#layout` : rangée « À faire » ouverte par une vitre **jaune** (colonne 0), puis
  rangée « Terminées » ouverte par une vitre **verte** ; quêtes terminées **brillantes**
  (`setEnchantmentGlintOverride`). Les cases d'en-tête sont inertes au clic.
- `QuestJournalService` : titre de la fenêtre = résumé court sur un onglet périodique ; slot 5 = résumé
  complet + temps avant renouvellement ; les onglets horloge / boussole portent le résumé en infobulle
  (lecture des deux périodes à chaque rendu, sans attribuer ni démarrer quoi que ce soit). Les quêtes
  périodiques restent exclues de « Quêtes en cours » / « Quêtes terminées » (inchangé).

**Agent** : `quest.list` ajoute `period` (si périodique) et un bloc `periodic` (`enabled`, `timezone`,
`resetTime`, `weeklyResetDay`, `dailyCount`, `weeklyCount`, `expiredReward:false`) via
`AgentActions#periodicQuestSettings()` (défaut vide, branché sur `configService.current().periodicQuests()`).

**PlugAdmin**
- `QuestPeriodicView` (nouveau) : onglets « Toutes les quêtes / Quotidiennes (n) / Hebdomadaires (n) »
  (`/quests?tab=daily|weekly`, liens sans JavaScript, retour à la ligne sur téléphone) ; en-tête d'onglet :
  réglages du serveur en lecture seule, mention « Valeurs de démonstration », avertissements (aucun modèle
  actif, moins de modèles que de quêtes par joueur, autant de modèles que de quêtes, modèle enregistré mais
  non publié), bouton « Créer une quête quotidienne / hebdomadaire ».
- `AgentPages#quests` : filtrage par onglet, badge « Quotidienne / Hebdomadaire » sur les fiches,
  catégorie de filtre `daily`/`weekly` (plus classés « Principales »), puces de type masquées sur un onglet
  périodique ; période reprise de la source si le serveur est antérieur au lot 2.
- `ContentEditorPages` / `PanelApp` : `/quests/new?period=daily|weekly` préréglé (période, catégorie,
  répétable).
- Rendu vérifié en local (panel `installDist` + relevé `quest.list` simulé, Playwright Chromium) à 1280 et
  390 px : aucun débordement horizontal ; correction apportée après la 1re capture (onglet « Hebdomadaires »
  coupé à 390 px → `pl-tabs-wrap`).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/quest/periodic/PeriodicProgress.java`
- `src/test/java/com/lodygames/rpgquest/quest/periodic/PeriodicProgressTest.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/QuestPeriodicView.java`
- ce rapport

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/ui/PeriodicJournalEntries.java`, `QuestJournalService.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`, `AgentActionExecutor.java`, `BukkitAgentActions.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`, `ContentEditorPages.java`, `PanelApp.java`
- `control-panel/src/main/resources/assets/plugadmin.css`
- Tests : `QuestJournalServiceTest`, `QuestListPayloadTest`, `QuestsCatalogTest`
- Docs : `docs/RPGQUEST_BIBLE.md`, `docs-site/quests.html`, `docs/MANUAL_TEST_PLAN.md` (TC-377),
  `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 21 +
  décision #15), `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Lecture seule de `periodic_quest_assignments` (V42) ; aucune écriture nouvelle.

## Configuration / données
Aucune. `periodic-quests` inchangé sur DEV (relu par `quest.list` : 2 / 1 / 04:00 / lundi / Europe/Paris).
Modèles `*_demo_*`, attributions, progressions et soldes non touchés.

## Tests automatiques
- Local ciblé, verts : `PeriodicProgressTest` (3), `QuestJournalServiceTest` (dont nouveau
  `periodicTabShowsItsProgressWithoutHoverAndKeepsCompletedQuestsApart` : titre, résumé, vitres, brillance,
  clic sur en-tête inerte, non-mélange avec « Quêtes terminées »), `QuestListPayloadTest` (+2),
  `AgentActionExecutorTest`, `PeriodicQuestServiceTest`, `QuestJournalKitQuestTest` ; panel
  `QuestsCatalogTest` (+3 : onglets, réglages affichés, absence de formulaire, création préréglée),
  `ContentEditorPagesTest`, `QuestPeriodTest`, `BundledExamplesDriftTest`, `PanelHardeningMalformedAgentDataTest`.
- Un 1er passage du nouveau test journal a échoué (bug réel : longueur de la grille calculée en rangées au
  lieu de cases → la rangée « Terminées » n'était pas remplie), corrigé.
- CI PR #643 : tous les jobs verts (plugin, control-panel, web-api, scripts, artefacts), en `push` et
  `pull_request`. Le push de fusion `118de98e` n'a déclenché **aucune** CI (fusion faite pendant une erreur
  502 de l'API GitHub) ; l'arbre fusionné est celui testé par la CI `pull_request` (base `df8f7bb0`
  inchangée). Les scripts de déploiement ont validé sur la CI verte de l'ancêtre `7b8b4b1`.

## Tests manuels à effectuer
TC-377 (`docs/MANUAL_TEST_PLAN.md`) — **PENDING MANUAL VALIDATION**. Aucun test Minecraft fait par Claude.

## Résultat attendu
Voir TC-377 : titre « Quotidiennes : N/2 terminée », vitre jaune « À faire », vitre verte « Terminées »
(quêtes brillantes), « Bois du jour » visible jusqu'au reset et absent de « Quêtes terminées » ; PlugAdmin :
onglets Quotidiennes (3) / Hebdomadaires (1), réglages en lecture seule.

## Reset / retour à l'état initial
Rien à réinitialiser (aucune donnée écrite).

## Déploiement VeryGames
Effectué (autorisé par les règles de session) : JAR `118de98e` (3 896 272 o, SHA-256 `5cd84c38…3219f`,
retéléchargé par FTP et identique), backup `rpgquest-20261020T170253Z-predeploy.jar` (3 889 567 o,
`0694d0c4…`, = `c92687f`), redémarrage RCON (seul LoDyMcFly en ligne), DEV ONLINE. PlugAdmin `118de98e`
déployé (`/opt/plugadmin/app/DEPLOYED`), classe `QuestPeriodicView` présente dans le JAR servi,
`/quests?tab=daily` et `/quests/new?period=daily` répondent 303 → /login sur le port réel.
### À transférer
Le JAR RPGQuest seul (fait).
### Ne PAS transférer/altérer
`config.yml` (`periodic-quests`), `data.db`, `quests/*_demo_*.yml`.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` (JAR `rpgquest-20261020T170253Z-predeploy.jar`) puis redémarrage ;
PlugAdmin : release précédente sous `/opt/plugadmin/releases/`. Aucune donnée à restaurer.

## Logs / diagnostic
`~/.local/state/rpgquest-logs/deploy-15-lot2.log`, `restart-15-lot2.log`, `plugadmin-15-lot2.log` ;
`quest.list` relu via l'agent (action `created_by = claude-15`) : 33 quêtes, 4 avec `period`, bloc `periodic`.

## Documentation mise à jour
Bible (suivi joueur + PlugAdmin lot 2), page docs-site Quêtes, TC-377, `current_state.md`, changelog serveur,
registre DEV.

## Limitations / travail restant
- Décisions propriétaire : valeurs de démonstration (2/1, 04:00, lundi, récompenses), expiration sans
  récompense ; réglage de `periodic-quests` depuis PlugAdmin et activation par modèle (changement de format)
  non faits.
- Les formulaires d'action de `/quests` reviennent sur l'onglet « Toutes » (le paramètre `tab` n'est pas
  propagé dans `return`).

## Prochaine étape suggérée
Retour du propriétaire sur TC-377 ; puis, selon ses décisions, réglages périodiques administrables depuis
PlugAdmin (via `ConfigFileCompleter` / paramètres généraux #335) et activation par modèle.
