# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 18:12
* Sujet : Issue #166 — récompense de quête « Cœur supplémentaire » (HEART)
* Statut : DONE (validation en jeu PENDING MANUAL VALIDATION, TC-311)
* Branche Git : `feature/166-reward-max-health` → PR #379 fusionnée dans `chore/257-ci-github-actions` (`26f039b`)
* Commit actuel si disponible : `26f039b` (merge) ; commits `811e7c1`, `5472246`, `1bbd684`
* Début de la tâche : 2026-10-14 17:00:14
* Fin de la tâche : 2026-10-14 18:12:43
* Durée totale : 01:12:29

## Demande
Session autonome lancée par le superviseur `lqorch` sur l'issue #166 : un type de récompense de
quête accordant un cœur de vie maximum permanent (+2 PV), persistant par UUID, accordé exactement
une fois par remise, sans écraser les autres sources de vie maximum, exposé dans le moteur, le
panel, le YAML et les content packs. Décision du propriétaire (04/10) : un cœur par ville via un
PNJ « Stan », une seule fois par joueur et par ville.

## Analyse
- Aucun `RewardType` existant ne touchait la santé. Le modèle le plus proche est `MONEY` (#16) :
  dette durable écrite dans la transaction de complétion.
- Pour un cœur, une « dette » n'est pas nécessaire : si le **droit** est écrit avec la complétion
  et que le bonus en jeu est toujours **recalculé** depuis la table, un arrêt brutal après commit
  ne perd rien (la connexion suivante applique le cœur) — seul le message est perdu.
- Points « à décider » du ticket, tranchés selon la proposition sûre du ticket (choix réversibles,
  documentés) :
  - répétable : **une fois par joueur et par clé de droit** (clé par défaut `quest:<id>`) ;
  - guérison : **seuls les PV ajoutés** sont remplis, jamais toute la barre ;
  - plafond : `progression.max-bonus-hearts`, **0 = aucun** (aucune valeur imposée) ; au-delà, le
    droit reste enregistré et devient actif si le plafond monte ;
  - reset joueur : les cœurs sont **conservés** (comme le portefeuille), pour qu'un reset ne
    rouvre jamais un droit consommé.
- Décision « Stan par ville » : la clé de droit partagée (`key: city:<id>`) en est la brique
  (renommer/remplacer Stan ne rouvre pas le droit). L'association ville → Stan dans le panel (#178)
  et la quête/épreuve de Stan ne sont **pas** livrées ici.

## Travail effectué
- `HeartReward` (`hearts` 1–512, `grantKey` facultative, motif `[a-z0-9_.:-]{1,128}`) et
  `RewardType.HEART` ; lecture YAML (`amount` facultatif → 1, `key` facultative) avec refus
  explicite des valeurs invalides.
- Schéma **V36** : `player_heart_grants (player_uuid, grant_key, hearts, quest_id, granted_at,
  PK (player_uuid, grant_key))`, sans clé étrangère (même raison que `quest_reward_grants`).
- `HeartGrantRepository` (total, liste, insertion `INSERT OR IGNORE` dans la transaction de
  l'appelant) ; `QuestProgressRepository#completeQuest` écrit complétion + dettes MONEY + droits
  HEART en **une transaction** (`completeQuestWithMoneyDebts` délègue, inchangé pour ses appelants).
- `health.HeartBonusService` (listener) : modificateur unique `rpgquest:quest_heart_bonus`
  (`ADD_NUMBER`, persistant pour ne pas rogner la vie à la reconnexion) sur `max_health`, retiré
  puis reposé → jamais cumulé ; recalcul depuis la base à `PlayerJoinEvent`, réapplication à
  `PlayerPostRespawnEvent` ; base 20 jamais réécrite ; messages `quest.reward-heart-granted /
  already / capped / failed` envoyés après le commit.
- `QuestProgressEngine#turnIn` : collecte des droits HEART, application après commit ; message
  d'échec si la complétion n'a pas pu être persistée.
- Journal de quêtes (infobulle « +N ❤ vie maximale »), relevés agent (`rewardDetails` kind `HEART`,
  `target` = clé partagée), content packs (`amount` + `key`, format public inchangé).
- Config `progression.max-bonus-hearts: 0` (ajoutée aux serveurs existants par
  `ConfigFileCompleter`).
- Panel : descripteur « Cœur supplémentaire » (icône `bi-heart-fill`), validation backend
  (> 512 ou clé invalide = erreur, > 5 = avertissement), libellé catalogue `RewardText`.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/quest/model/HeartReward.java`
- `src/main/java/com/lodygames/rpgquest/database/HeartGrantRepository.java`
- `src/main/java/com/lodygames/rpgquest/health/HeartBonusService.java`
- `src/test/java/com/lodygames/rpgquest/quest/progress/QuestHeartRewardIntegrationTest.java`
- `docs/manual-tests/rewards/test_heart_repeatable.yml`, `test_heart_city.yml`, `test_heart_city_bis.yml`

## Fichiers modifiés
- Plugin : `RewardType`, `QuestReward`, `QuestDefinitionParser`, `SchemaMigrator`,
  `QuestProgressRepository`, `QuestProgressEngine`, `RPGQuestBootstrap`, `ProgressionConfig`,
  `ConfigValidator`, `ContentPackMapper`, `QuestPackEntry`, `QuestJournalService`,
  `BukkitAgentActions`, `PlayerResetService` (Javadoc), `config.yml`, `messages.yml`.
- Panel : `Descriptors`, `QuestValidator`, `Icons`, `RewardText`, `docs/quetes.md`.
- Tests : `QuestDefinitionParserTest`, `SchemaMigratorTest`, `MySqlDialectTranslationTest`,
  `ProgressionServiceTest`, `CombatXpListenerTest`, `MiningXpListenerTest`,
  `ContentYamlRoundTripTest`, `EditorDescriptorsTest`, `RewardTextTest`.
- Docs : `QUEST_FORMAT.md`, `docs/RPGQUEST_BIBLE.md`, `docs-site/quests.html`,
  `docs/NPC_DIALOGUES_QUESTS_GUIDE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-311),
  `docs/deployment/SERVER_CHANGELOG.md`.

## Base de données / migrations
V36, additive : création de `player_heart_grants`. Aucune donnée existante réécrite. Colonnes
`quest_id`/`granted_at` classées « texte court » (VARCHAR(191) sur MariaDB).

## Configuration / données
`progression.max-bonus-hearts: 0` (nouvelle clé, complétée automatiquement). 4 nouvelles clés
`messages.yml` (fusionnées automatiquement). Aucune quête réelle ne porte de récompense HEART :
aucun joueur ne gagne de cœur tant qu'un auteur n'en ajoute pas.

## Tests automatiques
- Ciblés locaux : `QuestHeartRewardIntegrationTest` 8/8 (10→11→12 cœurs, répétable une fois,
  clé partagée une fois, autre modificateur préservé + réapplication sans cumul, restauration
  depuis la base, seuls les PV ajoutés remplis, bonus personnel, plafond puis relèvement) ;
  série de 24 classes voisines (parseur, migrateur, MariaDB, config, progression, packs, wallet,
  moteur, journal, messages, pack manuel) : 364 tests, 2 échecs trouvés puis corrigés
  (auto-unboxing `null` dans le parseur, classement MariaDB des 2 nouvelles colonnes) et relancés
  verts ; panel `ContentYamlRoundTripTest`, `EditorDescriptorsTest`, `RewardTextTest` verts.
- CI GitHub PR #379 : plugin, control-panel, web-api, scripts, artefacts — tout vert.
- `scripts/deploy-verygames.sh -y` a relancé la suite complète du plugin en local avant transfert : verte.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` TC-311 : rendu HUD (11e cœur visible),
réapparition réelle Paper, reconnexion, redémarrage, autre joueur, cumul avec un objet
`max_health`, édition panel, plafond. MockBukkit ne prouve **pas** l'affichage.

## Résultat attendu
Une quête portant `- type: HEART` fait passer un joueur de 10 à 11 cœurs, une seule fois ; une
seconde quête différente → 12 ; la même quête répétée ou une autre quête de même `key` → aucun
cœur, message « déjà reçu ».

## Reset / retour à l'état initial
Le reset joueur ne retire pas les cœurs (choix documenté). Retrait manuel d'un droit : aucune
commande dans ce lot (ligne `player_heart_grants` à supprimer à la main, puis reconnexion).

## Déploiement VeryGames
### À transférer
**Effectué sur DEV le 2026-10-14** (merge `26f039b`) :
- JAR plugin via `scripts/deploy-verygames.sh -y` : 2 846 976 octets, SHA-256 `07c5165a…41d686` ;
  JAR distant **retéléchargé**, SHA-256 identique ; backup
  `rpgquest-20261014T161015Z-predeploy.jar` (2 829 741 o, plus petit que le nouveau : cohérent).
- Redémarrage `scripts/verygames-restart.sh` (0 joueur en ligne) → ONLINE ; `/plugins` RPGQuest
  vert ; `/quest admin validate` : 24 quêtes, 0 erreur. Le journal serveur `logs/latest.log` n'a
  pas pu être relu par FTP : la trace explicite de la migration V36 n'a pas été vue (le plugin
  activé implique un démarrage de base réussi).
- Panel via `scripts/plugadmin/deploy.sh` (CI verte sur `26f039b`, aucun test local) :
  `/health` ONLINE, `DEPLOYED` = `26f039b`, classe `Descriptors` servie contenant `HEART`.
### Ne PAS transférer/altérer
`config.yml`, `messages.yml` (complétés automatiquement), `data.db`, quêtes du serveur.
### Redémarrage requis
Oui (JAR).
### Migration automatique
Oui, V36 au démarrage.

## Rollback
`scripts/rollback-verygames.sh` (JAR sauvegardé) + redémarrage ; `scripts/plugadmin/rollback.sh`
pour le panel. La table V36 reste, ignorée par l'ancien JAR.

## Logs / diagnostic
Logs de déploiement : `~/.local/share/rpgquest/claude-logs/2026-10-14-166-heart/ (deploy.log, panel-deploy.log)`.

## Documentation mise à jour
Voir « Fichiers modifiés — Docs ».

## Limitations / travail restant
- Association ville → Stan administrable dans le panel (#178) et quête/épreuve de Stan : non
  livrées ; la clé `city:<id>` est prête pour elles.
- Pas de vue/commande admin des droits HEART d'un joueur (le dépôt `grants()` existe).
- Une reconnexion avec un ancien JAR (rollback) garderait le modificateur sauvegardé.

## Prochaine étape suggérée
Propriétaire : TC-311 en jeu sur DEV ; puis décider la quête de Stan à Central City (PNJ existant à
identifier et renommer) et l'association ville → Stan (#178).
