# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-18
* Heure : 03:10
* Sujet : Issue #532 — `required-level` des portails et des offres marchandes lit le niveau RPG (régression #183)
* Statut : DONE (livré et déployé sur DEV ; TC-357 PENDING MANUAL VALIDATION)
* Branche Git : `fix/532-required-level-rpg` (PR #536) → `chore/257-ci-github-actions` ; rapport : `docs/532-rapport-required-level`
* Commit actuel si disponible : merge `de191bd`
* Début de la tâche : 2026-10-18 02:40:15
* Fin de la tâche : 2026-10-18 03:09:44
* Durée totale : 00:29:29

## Demande
Session autonome lqorch, ticket #532 : depuis #183 (`progression.vanilla-xp-progression: false`,
défaut et actif sur DEV) le niveau vanilla ne progresse plus ; `required-level` des portails
(`PortalService`) et des offres marchandes (`MerchantTradeService`) comparait encore
`Player#getLevel()`, fermant définitivement ces conditions à un nouveau joueur. Attendu : niveau RPG
`GLOBAL` en mode par défaut, niveau vanilla si la clé vaut `true`, règle unique, lecture en
mémoire, messages de refus inchangés, affichage « niveau RPG », ni format ni migration.

## Analyse
- Constat confirmé dans le code : `PortalService.attemptActivate` et
  `MerchantTradeService.checkRequirementsAndTrade` lisaient `player.getLevel()`.
- `ProgressionService.level(uuid, GLOBAL)` lit un cache mémoire chargé à la connexion : aucun accès
  SQL sur le thread principal. Le niveau RPG commence à 1 (0 XP).
- Même patron que `ExperienceRewardSink` (#183) : interface fonctionnelle avec défaut `VANILLA`
  quand rien n'est branché (tests existants inchangés), implémentation configurée au démarrage.

## Travail effectué
- `progression/RequiredLevelRule` : `levelOf(Player)`, `meets(Player, Integer)` (null = aucune
  condition), `VANILLA`, `configured(ProgressionService, Supplier<ProgressionConfig>)` qui relit
  `keepVanillaXp()` à chaque appel (suit un rechargement de config).
- `PortalService` / `MerchantTradeService` : `setRequiredLevelRule(...)`, contrôle via `meets`.
  Une seule instance de la règle branchée dans `RPGQuestBootstrap` pour les deux services.
- Affichage : lore marchand « Requiert le niveau RPG N », `/rpgadmin portal info` « Niveau RPG requis »,
  PlugAdmin Marchands « niveau RPG N » (fiche), champ « Niveau RPG min. », message de validation
  « niveau RPG requis ». Messages de refus en jeu inchangés.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/progression/RequiredLevelRule.java`
- `src/test/java/com/lodygames/rpgquest/progression/RequiredLevelRuleTest.java`
- ce rapport

## Fichiers modifiés
- `travel/PortalService.java`, `economy/merchant/MerchantTradeService.java`,
  `bootstrap/RPGQuestBootstrap.java`, `admin/RpgAdminCommand.java`
- `control-panel/.../panel/web/AgentPages.java`, `control-panel/.../panel/agent/MerchantForm.java`
- Tests : `PortalServiceTest`, `MerchantTradeServiceTest` (offre GOLD_INGOT `required-level: 2`)
- Docs : `docs/TRAVEL.md`, `docs/ECONOMY.md`, `docs/PROGRESSION.md`, `MERCHANT_FORMAT.md`,
  `docs/RPGQUEST_BIBLE.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md` (TC-357),
  `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune clé ajoutée ; aucun changement de format YAML. Comportement piloté par la clé existante
`progression.vanilla-xp-progression`. Aucune page `docs-site/` ne couvre portails/marchands/progression :
rien à y modifier.

## Tests automatiques
- Ciblés locaux (RPGQUEST_TEST_MAX_HEAP=768m) : `RequiredLevelRuleTest` 4/4, `PortalServiceTest`
  15 (2 ignorés préexistants, 0 échec), `MerchantTradeServiceTest` 8/8, `MerchantCatalogTradeTest`,
  `:control-panel` `MerchantsPageTest` — verts.
- CI GitHub PR #536 (run 39084025284) verte ; CI du merge `de191bd` (run 39084681627) verte.

## Tests manuels à effectuer
**PENDING MANUAL VALIDATION** — TC-357 (`docs/MANUAL_TEST_PLAN.md`) : offre d'essai « Niveau RPG min. 2 »
refusée au niveau RPG 1, acceptée au niveau 2 avec niveau vanilla 0 ; idem portail
(`required-level: 2`, redémarrage DEV requis car les portails ne sont lus qu'au démarrage).
Aucun test Minecraft n'a été effectué par Claude.

## Résultat attendu
Un joueur au niveau RPG suffisant passe les conditions `required-level` même avec un niveau vanilla figé à 0.

## Reset / retour à l'état initial
Aucune donnée écrite. Pour TC-357, remettre l'XP `GLOBAL` du compte d'essai avec `/skills admin set`.

## Déploiement VeryGames
- `scripts/deploy-verygames.sh -y --wait-ci` sur `de191bd` : JAR 3 629 867 octets, SHA-256
  `58278f1c807eb6fd6f396b49f2c2c1acb4e70ec1e4363abe1318248a33710cc1`, **vérifié par retéléchargement FTP**.
- Sauvegarde : `rpgquest-20261018T010708Z-predeploy.jar` (SHA `9812280a…` = JAR #531).
- DEV redémarré (0 joueur) via `verygames-restart.sh`, de nouveau ONLINE, RPGQuest actif (`plugins`).
- PlugAdmin : `scripts/plugadmin/deploy.sh` → `DEPLOYED commit=de191bd`, /health ONLINE ; les classes
  servies contiennent « Niveau RPG min » et « niveau RPG requis ».
- À ne pas altérer : `config.yml`, `data.db`, mondes. Pas de migration.

## Rollback
Remettre `rpgquest-20261018T010708Z-predeploy.jar` (`scripts/rollback-verygames.sh`) et redémarrer :
`required-level` relit le niveau vanilla. PlugAdmin : release précédente sous `/opt/plugadmin/releases/`.

## Logs / diagnostic
Journal de déploiement : `~/.local/state/rpgquest-build-logs/deploy-532.log`.

## Documentation mise à jour
Voir « Fichiers modifiés ».

## Limitations / travail restant
- Les portails n'ont pas d'édition PlugAdmin du niveau requis (YAML seulement), comme avant.
- Joueur hors ligne / cache non chargé : niveau RPG lu = 1 (les contrôles ne s'exécutent qu'en ligne).

## Prochaine étape suggérée
Propriétaire : TC-357 depuis PlugAdmin → Marchands (offre d'essai niveau RPG 2).
