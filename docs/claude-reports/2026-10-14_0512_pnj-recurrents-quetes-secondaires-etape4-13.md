# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 05:12
* Sujet : Issue #13 — PNJ récurrents, étape 4 : quêtes secondaires renouvelables (cooldown, QUEST_AVAILABLE, filtre PlugAdmin)
* Statut : PARTIAL (étape 4 livrée et déployée ; journal joueur des rencontres et validation en jeu restants)
* Branche Git : `feature/13-encounter-side-quests` (PR #350, fusionnée dans `chore/257-ci-github-actions`)
* Commit actuel si disponible : `b28df29` (fusion #350, déployé JAR + panel)
* Début de la tâche : 2026-10-14 04:38:18
* Fin de la tâche : 2026-10-14 05:13:23
* Durée totale : 00:35:05

## Demande
Session autonome lqorch, ticket #13, étape 4 : auditer le support repeatable/cooldown/abandon du
moteur de quêtes ; livrer des quêtes secondaires modestes et renouvelables données par
`encounter_bandit` / `encounter_merchant` / `encounter_smith`, reliées à leurs dialogues
(Accepter / Refuser / Plus tard, aucune quête sans acceptation), avec cooldown, anti-farm et
progression par joueur ; onglet Quêtes « Secondaires / Rencontres » dans PlugAdmin.

## Analyse
- Moteur : `repeatable` (booléen) et `abandon` existaient ; **aucun délai**. Les récompenses
  répétées sont déjà idempotentes par complétion (`quest_reward_grants.occurrence`, V25/V26).
- `quest_progress.updated_at` est écrit à chaque `upsertState`, donc la ligne `COMPLETED` porte la
  date de complétion → délai calculable **sans migration**.
- `QuestProgressEngine#availability` est la source unique des règles de refus (#12) : le délai y a
  été ajouté, la condition de dialogue la réutilise telle quelle.
- `START_QUEST` d'un dialogue ignorait le résultat de l'acceptation : un refus était silencieux.
- Les PNJ de rencontre sont reconnus par `TALK_TO_NPC` (identité `NpcIdentityService.currentId`,
  étape 1) et la remise #123 se fait par l'id du dialogue : rien à changer côté identité.
- Convention #238 (`BundledDialoguesValidityTest`) : un bouton qui démarre une quête doit commencer
  par « Commencer » → le bouton d'acceptation s'appelle « Commencer (j'accepte) ».

## Travail effectué
1. `QuestDefinition.cooldown` (`Duration`, ZERO par défaut, constructeur historique conservé) ;
   `QuestDefinitionParser` : `cooldown:` en secondes ou `s/m/h/d`, ≤ 30 j, erreur si la quête n'est
   pas répétable.
2. `availability` → `ON_COOLDOWN` (+ `retryAfter`) quand la quête est COMPLETED et que le délai court ;
   `AcceptOutcome.ON_COOLDOWN` ; `QuestProgressEngine#showCooldown` (message `quest.on-cooldown`,
   `CooldownText` « 3 h 20 min ») appelé par le dialogue et `/quest accept` ; messages dédiés pour
   `/rpgadmin quest start` et l'action agent `quest.start`. Un abandon ne déclenche pas de délai.
3. Condition de dialogue `QUEST_AVAILABLE` (`quest`) : modèle, parseur, writer, moteur de session,
   catalogue (`QUEST_REF_UNKNOWN`), pack de contenu, descripteurs + libellé panel.
4. `quest.list` expose `cooldownSeconds`.
5. Contenu : 3 quêtes `encounter` (cooldown 20 h, récompenses modestes, ajoutées à
   `BUNDLED_EXAMPLES` plugin + liste miroir du panel) ; 3 dialogues réécrits (offre sous
   `QUEST_AVAILABLE`, Commencer (j'accepte) / Plus tard / Refuser, suivi, remise d'objets, nœud de
   recharge).
6. Panel `/quests` : 2e groupe de puces « Tous types / Principales / Secondaires / Rencontres »,
   badges « rencontre » / « recharge 20 h », ligne « Délai » dans la fiche ; donneur `encounter_*`
   non signalé « inconnu » ; éditeur : champ « Délai avant de pouvoir la refaire » (écrit, relu,
   validé comme le moteur).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/quest/progress/CooldownText.java`
- `src/main/java/com/lodygames/rpgquest/dialogue/model/QuestAvailableCondition.java`
- `src/main/resources/quests/encounter_bandit_spiders.yml`, `encounter_merchant_cactus.yml`, `encounter_smith_ore.yml`
- Tests : `CooldownTextTest`, panel `QuestCooldownTest`, `EncounterQuestFilterTest`

## Fichiers modifiés
Plugin : `QuestDefinition`, `QuestDefinitionParser`, `YamlQuestEngine`, `AcceptOutcome`,
`QuestProgressEngine`, `QuestCommand`, `RpgAdminCommand`, `ConditionType`, `DialogueCondition`,
`DialogueDefinitionParser`, `DialogueDefinitionWriter`, `DialogueSessionEngine`, `DialogueCatalog`,
`ContentPackMapper`, `DialoguePackEntry`, `AgentActions`, `AgentActionExecutor`, `BukkitAgentActions`,
`messages.yml`, `dialogues/encounter_{bandit,merchant,smith}.yml`.
Panel : `Descriptors`, `QuestDraft`, `QuestYaml`, `QuestValidator`, `ContentEditorPages`,
`AgentPages`, `ContentDeletionAnalyzer`.
Tests : `QuestDefinitionParserTest`, `QuestProgressEngineTest`, `YamlQuestEngineTest` (13→16),
`BundledQuestsValidityTest`, `DialogueDefinitionParserTest`, panel `DialogueDescriptorsTest`.

## Base de données / migrations
Aucune. Le délai lit `quest_progress.updated_at` (déjà présent).

## Configuration / données
Nouvelle clé `quest.on-cooldown` dans `messages.yml` (fusionnée automatiquement dans le fichier du
serveur). Aucune clé `config.yml`.

## Tests automatiques
- Locaux ciblés (RPGQUEST_TEST_MAX_HEAP=768m) : `quest.*`, `dialogue.*`, `content.*` (plugin) +
  panel Quest/Dialogue/ContentPack/ContentDeletion/BundledExamplesDrift. Premier passage : 3 échecs
  réels corrigés (libellé « Commencer » imposé par #238, compteur de quêtes seedées 13→16, liste
  miroir du panel) ; ensuite tout vert (387 + 274 tests sur les deux derniers lots).
- CI GitHub PR #350 : Tests plugin, control-panel, web-api, Scripts, Artefacts — tous verts ;
  CI verte aussi sur la fusion `b28df29` (vérifiée par `deploy-verygames.sh --wait-ci`).

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` TC-306 (et TC-303/304/305 toujours en
attente). Aucun test en jeu ni clic panel n'a été fait.

## Résultat attendu
Chaque PNJ de rencontre propose sa quête seulement quand elle est acceptable ; rien ne démarre sans
« Commencer (j'accepte) » ; après complétion, 20 h d'attente (message avec temps restant) ; un autre
joueur reste libre ; `/quests` filtre les quêtes de rencontre.

## Reset / retour à l'état initial
`/rpgadmin quest reset <joueur> rpgquest:encounter_*` rend une quête immédiatement reprenable.

## Déploiement VeryGames
Effectué le 2026-10-14 ~05:09 : `scripts/deploy-verygames.sh -y --wait-ci --allow-no-backup` + 6
`--also` (3 dialogues remplacés, 3 quêtes nouvelles), `DEPLOY_EXIT=0`, validation = CI verte sur
`b28df29`, `./gradlew assemble`. JAR 2 743 233 o, SHA-256 `60b9fdd6…c888f`, **retéléchargé : SHA
identique**. Backup `rpgquest-20261014T030849Z-predeploy.jar` = JAR de l'étape 3 (`0b97a599…`,
2 731 611 o, plus petit que le nouveau). Backups `--also` :
`verygames-backups/extra-20261014T030849Z/`. Redémarrage `verygames-restart.sh` à 0 joueur
(`RESTART_EXIT=0`) ; `/plugins` : Citizens, LuckPerms, Multiverse-Core, RPGQuest, WorldEdit actifs ;
`quest admin validate` : 23 quêtes chargées, 0 erreur.
Panel : `scripts/plugadmin/deploy.sh --wait-ci` → `DEPLOYED commit=b28df29`, `/health` ONLINE,
`/quests` → 303 `/login`, classes servies contenant `QUEST_AVAILABLE` et « Rencontres ».
### À transférer
JAR + `RPGQuest/dialogues/encounter_*.yml` + `RPGQuest/quests/encounter_*.yml` (faits) ; panel (fait).
### Ne PAS transférer/altérer
`config.yml`, `messages.yml` (complété automatiquement), `data.db`, `encounters/`, `Citizens/`.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` (JAR étape 3) puis
`scripts/rollback-verygames.sh --also <backup>/RPGQuest/dialogues/encounter_X.yml:RPGQuest/dialogues/encounter_X.yml`
pour les 3 dialogues (l'ancien JAR ne connaît pas `QUEST_AVAILABLE`), puis redémarrage. Les 3
quêtes restent chargeables par l'ancien JAR (clé `cooldown:` ignorée) ; les retirer si l'anti-farm
doit être garanti. Panel : release précédente dans `/opt/plugadmin/releases/`.

## Logs / diagnostic
`~/rpgquest-build-logs/deploy-13-step4.log`, `restart-13-step4.log`, `panel-13-step4.log`.
Refus en recharge : message joueur `quest.on-cooldown` ; agent `quest.start` → code `ON_COOLDOWN`.

## Documentation mise à jour
`docs/ENCOUNTERS.md` (section « Quêtes secondaires renouvelables »), Bible (condition
`QUEST_AVAILABLE`, étape 4), `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` (`cooldown`), `docs-site/quests.html`,
`docs-site/dialogues.html`, `docs/current_state.md`, `SERVER_CHANGELOG.md`, `MANUAL_TEST_PLAN.md` TC-306.

## Limitations / travail restant
- Le pack de contenu (export/import IA) ne transporte pas encore `cooldown:` des quêtes.
- Le PNJ en recharge dit « repasse d'ici un jour » sans le temps exact (le temps exact est donné par
  `/quest accept`) — pas de placeholder de délai dans les dialogues.
- `cooldown` > 30 j refusé par le moteur mais pas pré-validé par l'éditeur du panel.
- Le premier commit de la série (`1afb964`) ne compile pas isolément (switch de `BukkitAgentActions`
  complété au commit suivant) ; la fusion est verte.
- Reste de #13 : journal joueur des rencontres, validation en jeu TC-303 à TC-306.

## Prochaine étape suggérée
Étape 5 de #13 depuis `chore/257-ci-github-actions` (≥ `b28df29`) : journal joueur des rencontres
(PNJ rencontrés / quêtes de rencontre faites, recharge restante), éventuellement placeholder
`%quest_cooldown:<id>%` dans les dialogues et `cooldown` dans le pack de contenu ; sinon passer le
ticket en WAITING_OWNER pour la validation en jeu TC-303..306.
