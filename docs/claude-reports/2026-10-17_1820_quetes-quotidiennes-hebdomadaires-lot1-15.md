# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 18:20
* Sujet : Issue #15 — quêtes quotidiennes et hebdomadaires, lot 1 (plugin + préservation du champ dans PlugAdmin)
* Statut : PARTIAL (lot 1 livré et déployé sur DEV ; onglets PlugAdmin dédiés = lot 2 ; validation en jeu en attente)
* Branche Git : `feature/15-quetes-periodiques` → fusionnée dans `chore/257-ci-github-actions` (PR #514, merge `c6a45c9`)
* Commit actuel si disponible : `c6a45c9`
* Début de la tâche : 2026-10-17 16:18:19
* Fin de la tâche : 2026-10-17 18:24:00
* Durée totale : 02:05:41

## Demande
Ticket #15 (session autonome lqorch) : quêtes quotidiennes et hebdomadaires — entrées dédiées du
journal après obtention du journal, attribution par joueur depuis des modèles administrables,
persistance (attribution, progrès, fin, récompense, renouvellement), resets à heures définies
(fuseau configurable), pas de réattribution à la reconnexion, onglets PlugAdmin, tests de frontières
temporelles et de non-duplication. Consigne superviseur : auditer d'abord, découper en lots
testables, valeurs économiques configurables et marquées « démonstration », ticket laissé ouvert.

## Analyse
- Audit : aucune PR, branche, rapport ni code existant pour #15 (seules les quêtes répétables avec
  `cooldown` de #13 existaient). Rien n'était déjà livré.
- Le moteur de quêtes gère déjà répétition, persistance, remise et récompenses idempotentes (un
  jeton de paiement par complétion). Choix : un modèle périodique = une quête ordinaire marquée
  `period: daily|weekly`, attribuée et démarrée par un service dédié — aucun second moteur.
- Défaut latent découvert : au redémarrage d'une quête répétable (ou abandonnée), les compteurs
  d'objectifs de la fois précédente restaient en base et étaient rechargés après un redémarrage
  serveur. Indispensable à corriger pour des quêtes renouvelées chaque jour.
- « Seulement après journal » : le journal n'est pas tracé en base (objet soulbound). Choix : la
  première ouverture du journal (clic droit ou `/quests`) débloque les quêtes périodiques du joueur
  (variable `__periodic_quests_unlocked__`).
- Découpage : lot 1 = plugin complet + préservation du champ dans l'éditeur PlugAdmin existant
  (sinon une édition effaçait `period:`) ; lot 2 = onglets PlugAdmin « Quotidiennes /
  Hebdomadaires » avec aperçu.

## Travail effectué
- Modèle : `QuestPeriod`, composant `period` de `QuestDefinition` (+ `isPeriodic()`), parseur :
  `period:` implique `repeatable`, refuse `repeatable: false`, `cooldown`, `prerequisites`.
- Moteur : statut `NOT_ASSIGNED` (accept/availability hors attribution, y compris
  `/rpgadmin quest start … force`), `startAssignedPeriodic` réservé au service ; `forceComplete`
  d'une quête périodique non active → `NOT_ACTIVE`, rien accordé (dialogue `TURN_IN_QUEST`, admin,
  agent, Story) ; démarrage via `QuestProgressRepository#startQuest` (compteurs effacés + ACTIVE en
  une transaction). Messages : `quest.periodic-not-assigned`, cas ajoutés dans `QuestCommand`,
  `RpgAdminCommand`, `BukkitAgentActions`, `StoryService`.
- `PeriodCalendar` (clé de période = date locale de début, heure de reset, jour hebdo, changements
  d'heure), `PeriodicQuestSelector` (déterministe par joueur/période, évite la période précédente),
  `PeriodicQuestService` (déblocage, attribution idempotente, démarrage, expiration au reset sans
  récompense — `READY_TO_TURN_IN` conservée —, relance à la connexion et ≤ 1 min après un reset,
  une passe par joueur à la fois).
- Persistance : table `periodic_quest_assignments` (V42), `PeriodicQuestAssignmentRepository`
  (`INSERT OR IGNORE`, transitions conditionnelles ASSIGNED → STARTED → CLOSED).
- Config : `periodic-quests` (enabled, timezone, reset-time, weekly-reset-day, daily-count,
  weekly-count), validée, ajoutée par le complément de configuration.
- Journal : onglets « Quêtes quotidiennes » (horloge) / « Quêtes hebdomadaires » (boussole),
  barre du haut réorganisée, statut « À faire / ✔ Terminée aujourd'hui », échéance « Nouvelles
  quêtes dans … », onglet vide explicite ; quêtes périodiques retirées de « en cours / terminées ».
  Deux courses d'affichage corrigées (vues en CI) : jeton de rendu par joueur et onglet périodique
  en attente.
- 4 modèles de démonstration seedés (`BUNDLED_EXAMPLES` + copie du panel).
- PlugAdmin : `QuestDraft.period`, lecture/écriture YAML (aller-retour fidèle), sélecteur
  « Quête quotidienne ou hebdomadaire » dans l'éditeur guidé, validation alignée sur le moteur,
  schéma de content pack.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/quest/model/QuestPeriod.java`
- `src/main/java/com/lodygames/rpgquest/quest/periodic/{PeriodCalendar,PeriodicQuestSelector,PeriodicQuestService}.java`
- `src/main/java/com/lodygames/rpgquest/database/PeriodicQuestAssignmentRepository.java`
- `src/main/java/com/lodygames/rpgquest/config/PeriodicQuestConfig.java`
- `src/main/java/com/lodygames/rpgquest/ui/PeriodicJournalEntries.java`
- `src/main/resources/quests/{daily_demo_bucheron,daily_demo_mineur,daily_demo_chasseur,weekly_demo_expedition}.yml`
- Tests : `PeriodCalendarTest`, `PeriodicQuestSelectorTest`, `PeriodicQuestAssignmentRepositoryTest`,
  `PeriodicQuestServiceTest`, `control-panel/.../QuestPeriodTest`

## Fichiers modifiés
`QuestDefinition`, `QuestDefinitionParser`, `YamlQuestEngine`, `QuestProgressEngine`,
`AcceptOutcome`, `CompleteOutcome`, `QuestProgressRepository`, `SchemaMigrator`, `MigrationReport`,
`PluginConfig`, `ConfigValidator`, `RPGQuestBootstrap`, `QuestJournalService`, `JournalTab`,
`JournalReplayText`, `QuestCommand`, `RpgAdminCommand`, `BukkitAgentActions`, `StoryService`,
`config.yml`, `messages.yml` ; panel : `QuestDraft`, `QuestYaml`, `QuestValidator`,
`ContentEditorPages`, `ContentPackSchema`, `ContentDeletionAnalyzer` ; tests existants adaptés :
`QuestDefinitionParserTest`, `ConfigValidatorTest`, `MySqlDialectTranslationTest`,
`YamlQuestEngineTest`, `QuestJournalServiceTest`. Docs : voir plus bas.

## Base de données / migrations
Migration **V42** `periodic_quest_assignments` (création de table + index, `IF NOT EXISTS`,
automatique au démarrage). Aucune donnée existante modifiée. Appliquée sur DEV au redémarrage.

## Configuration / données
Section `periodic-quests` ajoutée automatiquement au `config.yml` DEV (vérifiée par
re-téléchargement FTP : enabled true, Europe/Paris, 04:00, MONDAY, 2, 1). Aucun `config.yml`
téléversé. 4 modèles seedés dans `quests/` (fichiers absents auparavant). Valeurs (nombres,
heures, récompenses 5/20 pièces + XP) = **démonstration**, non définitives.

## Tests automatiques
- Local (ciblés, `RPGQUEST_TEST_MAX_HEAP=768m`) : nouveaux tests + `QuestDefinitionParserTest`,
  `ConfigValidatorTest`, `BundledQuestsValidityTest`, `MySqlDialectTranslationTest`,
  `SchemaMigratorTest`, `QuestJournalServiceTest` (×3 avec `--rerun`), `QuestJournalKitQuestTest`,
  `JournalReplayTextTest`, `QuestProgressEngineTest`, `StoryServiceTest`,
  `QuestStoryProgressPersistenceIntegrationTest`, `DialogueSessionEngineTest`,
  `ConfigFileCompleterTest`, `BukkitAgentActionsQuestStatusTest`, `RpgAdminTestShortcutsCommandTest`,
  `PlayerResetServiceTest`, `CatalogOfferSelectorTest`, `YamlQuestEngineTest` ; panel :
  `QuestPeriodTest`, `QuestCooldownTest`, `ContentEditorPagesTest`, `BundledExamplesDriftTest`,
  `ContentDeletion*` — tous verts.
- CI GitHub PR #514 : deux premiers passages rouges (dérive de la liste des exemples embarqués du
  panel ; course d'affichage du journal dans le nouveau test), corrigés ; dernier passage entièrement
  vert (plugin, control-panel, web-api, scripts, artefacts), CI du commit de fusion `c6a45c9` verte.
- Le script de déploiement a relancé la suite du plugin en garde-fou : verte.

## Tests manuels à effectuer
TC-350 (`docs/MANUAL_TEST_PLAN.md`) — PENDING MANUAL VALIDATION. Aucun test Minecraft n'a été fait
dans cette session.

## Résultat attendu
Après ouverture du journal : 2 quotidiennes + 1 hebdomadaire démarrées, visibles seulement dans
leurs onglets, stables aux reconnexions, une récompense par période, expiration au reset 04:00.

## Reset / retour à l'état initial
`periodic-quests.enabled: false` masque les onglets et arrête toute attribution. Supprimer les 4
fichiers `*_demo_*` de `quests/` retire les modèles. Aucune donnée joueur à nettoyer.

## Déploiement VeryGames
- JAR : `scripts/deploy-verygames.sh -y` depuis `/srv/rpgquest/wt-orch` au commit `c6a45c9` ;
  sauvegarde `rpgquest-20261017T161529Z-predeploy.jar` (+ `.meta`) ; JAR distant re-téléchargé,
  SHA-256 `b351ec66a543e30ed3174fa9e42b194b9db700b119dda7b4d06104e39d597ab2` = local.
- Redémarrage : `scripts/verygames-restart.sh` (seul LoDyMcFly connecté), DEV de nouveau ONLINE.
- Vérifié : `config.yml` complété ; `quest.list` (agent, lecture seule) → 33 quêtes dont les 4
  modèles, aucun rejet.
- PlugAdmin : `scripts/plugadmin/deploy.sh` → `DEPLOYED commit=c6a45c9` ; la sonde `/health` du
  script a répondu KO (course connue), revérifiée ONLINE ; classes nouvelles présentes dans le JAR
  servi.
- À ne pas altérer : `data.db`, quêtes existantes, `config.yml` (complété automatiquement).

## Rollback
Remettre `rpgquest-20261017T161529Z-predeploy.jar` et redémarrer (la table V42 reste, ignorée par
l'ancien JAR). PlugAdmin : `scripts/plugadmin/rollback.sh app`.

## Logs / diagnostic
Avertissement serveur « Quête périodique … non démarrée » si un modèle attribué a été retiré depuis.
`quest.list` de l'agent n'expose pas encore le champ `period` (à ajouter au lot 2).

## Documentation mise à jour
`docs/RPGQUEST_BIBLE.md` (section « Quêtes quotidiennes et hebdomadaires », journal),
`docs-site/quests.html`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-350),
`docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 21, correctifs livrés,
décision 11 — le report de `main` était déjà intégré).

## Limitations / travail restant
- Lot 2 : onglets PlugAdmin « Quotidiennes / Hebdomadaires » (liste des modèles, activation,
  aperçu), `period` dans `quest.list`/catalogue, éventuel outil admin d'inspection des attributions.
- « Préparer sans implémenter » (difficulté selon niveau, nombre variable, monde/ville/réputation) :
  non traité au-delà d'un modèle extensible.
- Décisions propriétaire : valeurs de démonstration, expiration sans récompense, heure/jour du reset.
- Un reset joueur (`resetnew`) n'efface pas les attributions (choix : pas de regain de récompense).

## Prochaine étape suggérée
TC-350 en jeu par le propriétaire, arbitrage des valeurs, puis lot 2 PlugAdmin.
