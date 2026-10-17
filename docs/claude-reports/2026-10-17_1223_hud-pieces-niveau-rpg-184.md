# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 12:23
* Sujet : Issue #184 — tester tôt l'affichage des pièces et du niveau RPG en jeu (prototype `/hud`)
* Statut : DONE (livré et déployé sur DEV ; validation visuelle en jeu en attente → ticket WAITING_OWNER, laissé ouvert)
* Branche Git : `feature/184-hud-pieces-niveau` (PR #502, fusionnée dans `chore/257-ci-github-actions`), rapport sur `docs/184-rapport`
* Commit actuel si disponible : `ca1de6b` (merge PR #502)
* Début de la tâche : 2026-10-17 11:05:15
* Fin de la tâche : 2026-10-17 12:23:52
* Durée totale : 01:18:37

## Demande
Session autonome lqorch, ticket #184 : proposer rapidement un essai en jeu d'un affichage lisible des pièces
(source économique réelle) et, si disponible, du niveau RPG, sans encombrer le suivi de quête, les bossbars ni
les informations de voyage ; retour temporaire lors des gains/dépenses ; choix final après retour visuel.

## Analyse
Audit préalable : aucun HUD de pièces n'existait (aucun scoreboard dans le plugin, `/money` seul en chat).
Existant réutilisé :
- portefeuille `wallets` via `EconomyService` : **toutes** les écritures (marchands, artisans, marché,
  portails, `/money`, PlugAdmin, récompenses de quête) y passent → point unique de notification ;
- niveau RPG : piste `SkillType.GLOBAL` de `ProgressionService` (cache mémoire chargé à la connexion) ;
- action bar partagée `ActionBarFeedback` (#69/#209) qui ajoute une info sans effacer une progression d'objectif ;
- bossbar de quête suivie (`TrackedQuestDisplay`) : surface déjà occupée → pas utilisée.
#138 (pièces physiques) non activé : aucun comptage d'items, pas de second solde.

## Travail effectué
- `WalletChangeListener` + `EconomyService.setChangeListener` : prévenu après chaque mouvement **réussi**
  (credit, debit accepté, pay des deux côtés, adminSet avec delta 0, récompense de quête réellement créditée) ;
  jamais sur un refus ; une exception du listener n'affecte jamais la transaction.
- `ProgressionService.setXpListener` : prévenu au chargement, à chaque octroi accepté et au réglage admin.
- `PlayerHudService` (`ui`) : panneau latéral natif **propre au joueur** (scoreboard neuf, objectif
  `rpgquest_hud`, scores masqués par `NumberFormat.blank()`, lignes Adventure via `Score#customName`) :
  « Bourse : N LodyCoins » (solde relu, « … » avant lecture, jamais un faux 0) et « Niveau RPG : N ». Posé
  seulement si le joueur est sur le scoreboard principal (n'écrase jamais un autre plugin) ; retiré au `/hud off`
  et à l'arrêt du plugin. Retour action bar « +25 LodyCoins · solde 150 » / « -N … » / « Solde ajusté : … ».
- `/hud [sidebar|off]` (`rpgquest.hud`, défaut tous) : sans argument = bascule ; choix rangé dans le PDC du
  joueur (`rpgquest:hud_mode`).
- `config.yml` → `hud.default-mode: "off"`, `hud.wallet-feedback: true`. Piège corrigé pendant les tests :
  YAML 1.1 lit `off` non guillemeté comme `false` → valeur guillemetée et `false` accepté comme OFF.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/economy/WalletChangeListener.java`
- `src/main/java/com/lodygames/rpgquest/config/HudMode.java`, `HudConfig.java`
- `src/main/java/com/lodygames/rpgquest/ui/PlayerHudService.java`, `HudText.java`
- `src/main/java/com/lodygames/rpgquest/command/HudCommand.java`
- Tests : `config/HudConfigTest`, `ui/HudTextTest`, `ui/PlayerHudServiceIntegrationTest`,
  `economy/EconomyServiceChangeListenerTest`

## Fichiers modifiés
`EconomyService.java`, `ProgressionService.java`, `ConfigValidator.java`, `PluginConfig.java`,
`RPGQuestBootstrap.java`, `config.yml`, `plugin.yml` ; docs listées plus bas.

## Base de données / migrations
Aucune. Aucun solde, objet ni donnée joueur modifié. Le choix d'affichage vit dans le PDC du joueur.

## Configuration / données
Nouvelle section `hud:` ajoutée automatiquement au `config.yml` serveur par `ConfigFileCompleter` —
vérifié sur DEV après redémarrage (`default-mode: 'off'`, `wallet-feedback: true`).

## Tests automatiques
- Ciblés en local (`RPGQUEST_TEST_MAX_HEAP=768m`) : HudConfig/HudText/EconomyServiceChangeListener/
  ConfigValidator/ConfigFileCompleter/WalletRepository/Progression*/PortalService/Merchant*/Market* →
  245 tests, 0 échec ; PlayerHudServiceIntegration + RPGQuestPluginTest + RpgPermissionsMatrix + QuestJournal* +
  ActionBarFeedback → verts.
- CI GitHub PR #502 : tous les jobs verts (plugin, control-panel, web-api, scripts, artefacts).
- Suite complète relancée par `deploy-verygames.sh` (pas 3/8) : BUILD SUCCESSFUL (50 min).
- Limite : MockBukkit n'implémente ni `Objective#numberFormat` ni `Score#customName` → le rendu du panneau
  n'est pas testable automatiquement.

## Tests manuels à effectuer
TC-346 (`docs/MANUAL_TEST_PLAN.md`) — **PENDING MANUAL VALIDATION** : panneau, mise à jour après achat/vente,
pas de « -N » sur refus, cohabitation avec la progression d'objectif et la bossbar, mémorisation après
reconnexion, niveau RPG mis à jour, avis sur le choix d'interface.

## Résultat attendu
`/hud` affiche à droite « LodyQuests / Bourse : N LodyCoins / Niveau RPG : N » ; tout gain/dépense réussi
apparaît dans l'action bar avec le solde.

## Reset / retour à l'état initial
`/hud off`. Pour couper aussi les retours : `hud.wallet-feedback: false` + reload/redémarrage.

## Déploiement VeryGames
### À transférer
JAR seul (`ca1de6b`) — **fait** : 3 520 199 o, SHA-256 `880aac0dea2f6ca43743dd87dd8f21fb3fbc47f4f412c077a38896341ce4eab1`
vérifié par retéléchargement du JAR en ligne.
### Ne PAS transférer/altérer
`config.yml` (complété automatiquement), `data.db`, contenus. PlugAdmin inchangé (non redéployé).
### Redémarrage requis
Oui — fait via `verygames-restart.sh` (LoDyMcFly, le propriétaire, était connecté ; redémarré selon la règle
habituelle) ; DEV de nouveau ONLINE, `/hud` enregistré (réponse RCON « doit être exécutée par un joueur »).
### Migration automatique
Aucune migration de base ; seule la complétion de `config.yml`.

## Rollback
`scripts/rollback-verygames.sh` vers `rpgquest-20261017T102130Z-predeploy.jar` (JAR #18, SHA `1f63f1d6…`).
La section `hud:` et la clé PDC restent inertes pour l'ancien JAR.

## Logs / diagnostic
Journal de déploiement : `~/.cache/rpgquest-logs/deploy-184.log` (hors dépôt).

## Documentation mise à jour
`docs/RPGQUEST_BIBLE.md` §12 (aucune page docs-site économie à ce jour), `docs/ECONOMY.md`,
`docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-346), `docs/deployment/SERVER_CHANGELOG.md`.

## Limitations / travail restant
- Choix d'interface **non définitif** (exigence du ticket) : panneau désactivé par défaut, à décider après TC-346.
- Pendant que le panneau est affiché, les équipes du scoreboard principal (`/team`) ne s'appliquent plus à ce
  joueur (scoreboard propre) — sans effet aujourd'hui, le plugin n'en utilise aucune.
- Le niveau RPG affiché est la piste GLOBAL actuelle ; la refonte XP de #183 pourra changer sa source.
- Pas de réglage PlugAdmin du HUD (relève de #335 si souhaité).

## Prochaine étape suggérée
Propriétaire : TC-346 sur DEV, puis choisir panneau par défaut / autre surface ; ajuster `hud.default-mode`.
