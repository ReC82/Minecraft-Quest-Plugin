# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 09:46
* Sujet : #354 — textes libres `NOT NULL` traduits en `VARCHAR(191)` par `MySqlDialect` (bloquant de la migration #42)
* Statut : DONE
* Branche Git : `fix/354-mysql-free-text` (PR #358, fusionnée dans `chore/257-ci-github-actions`, merge `048e0f2`) ; rapport sur `docs/354-rapport`
* Commit actuel si disponible : `048e0f2` (code `a0a46b0`, `9f3f435`, `6f0ce5a`)
* Début de la tâche : 2026-10-14 07:55:22
* Fin de la tâche : 2026-10-14 09:45:45
* Durée totale : 01:50:23

## Demande
Session autonome lancée par le superviseur `lqorch` pour le ticket #354 : décider de la règle de
traduction des textes libres côté MariaDB, ne rien changer pour SQLite, migrer les bases MariaDB
déjà créées, tester (balayage du catalogue réel + test d'intégration MariaDB avec une description
de 2 000 caractères) et rejouer le `dry-run` de l'outil #42 sur une copie DEV. Bascule réelle hors
périmètre.

## Analyse
- `MySqlDialect#translateColumn` passait en `VARCHAR(191)` toute colonne `TEXT` portant `NOT NULL`,
  `PRIMARY KEY` ou une clé de la table. Le dialecte ne voit qu'une instruction à la fois : les index
  créés plus tard (`CREATE INDEX` séparés) lui sont invisibles. Une règle générale « `NOT NULL`
  non indexé → `TEXT` » aurait donc fait échouer les index posés ensuite sur des colonnes `TEXT`.
- **Décision : liste explicite** `MySqlDialect.FREE_TEXT_COLUMNS` (les 8 colonnes de l'issue), plus
  un garde-fou de test qui oblige à classer toute nouvelle colonne `TEXT NOT NULL` hors clé. Le
  choix est réversible et sans effet sur SQLite.
- Bases MariaDB déjà créées (base de test #41) : il faut une migration. Elle passe par une méthode
  de dialecte (`SqlDialect#freeTextWidenings`, vide pour SQLite), sans conditionnelle de moteur
  dans `SchemaMigrator`.
- Conséquence : `CURRENT_VERSION` passe à 35. L'outil #42 exige une source au même schéma, donc la
  copie de `data.db` doit venir d'un plugin V35+. D'où le déploiement DEV : la base DEV passe en V35.
- Le runner ne refuse pas une base plus récente que le JAR, donc un rollback reste sûr.

## Travail effectué
- `MySqlDialect` : `FREE_TEXT_COLUMNS` (record `FreeTextColumn(table, column, defaultLiteral)`).
  Une colonne libre et non clé sort en `TEXT NOT NULL [DEFAULT '']`. `freeTextWidenings()` produit
  `ALTER TABLE … MODIFY COLUMN … TEXT NOT NULL [DEFAULT …]`.
- `SqlDialect#freeTextWidenings()` : méthode par défaut qui renvoie une liste vide (SQLite).
- `SchemaMigrator` : V35 « textes libres en TEXT sur MariaDB (issue #354) », `CURRENT_VERSION = 35`.
- Tests :
  - `MySqlDialectTranslationTest` : capture du catalogue factorisée (`realMigrationDdl()`), plus 3 tests.
  - Le nouveau test `everyNotNullTextColumnOfTheCatalogueIsClassified` vérifie trois choses :
    chaque texte libre existe et sort en `TEXT` avec son `DEFAULT` canonique ; aucune colonne
    indexée ou clé n'est `TEXT` ; les 72 autres `VARCHAR(191)` hors clé sont toutes listées dans
    `SHORT_TEXT_COLUMNS` (horodatages, énumérations, identifiants, noms bornés : bâtiment ≤ 64,
    ville ≤ 40).
  - Nouveau `MariaDbFreeTextIntegrationTest` : description de 2 000 caractères, dont 1 000 « é »
    multi-octets, relue intacte. Une base pré-V35 (colonnes rétrécies, ligne V35 effacée) est
    élargie au démarrage suivant.
  - `MariaDbMigrationIntegrationTest` : l'ancien test exigeait qu'une description de 400
    caractères fasse échouer l'outil, ce qui revenait à figer le bug. Il vise désormais
    `building_sites.name`, une vraie colonne courte. Un nouveau test vérifie qu'une description de
    2 000 caractères passe le dry-run.
- Panel : l'étape « 6. Bloquants connus » de l'assistant `/database` passe à « fait ». La fiche
  `base-de-donnees.md` gagne une entrée de dépannage « schéma source V34 ≠ outil V35 ».
- **Correctif hors ticket, nécessaire pour la CI** : `DbTargetStoreTest.secretBoxRejectsOtherKeyOtherPurposeAndTampering`
  (code #42) était instable. Il échangeait le caractère Base64 placé juste avant `==`, qui porte des
  bits ignorés au décodage : environ une fois sur quatre, rien n'était altéré. Le test altère
  maintenant un octet décodé.

## Fichiers créés
- `src/test/java/com/lodygames/rpgquest/database/MariaDbFreeTextIntegrationTest.java`
- `docs/claude-reports/2026-10-14_0946_textes-libres-mariadb-v35-354.md`

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/database/MySqlDialect.java`, `SqlDialect.java`, `SchemaMigrator.java`
- `src/test/java/com/lodygames/rpgquest/database/MySqlDialectTranslationTest.java`, `MariaDbMigrationIntegrationTest.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/dbtarget/DbTargetPages.java`
- `control-panel/src/main/resources/docs/base-de-donnees.md`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/dbtarget/DbTargetStoreTest.java`
- `docs/PERSISTENCE.md`, `docs/ARCHITECTURE.md`, `docs/current_state.md`, `docs/RPGQUEST_BIBLE.md`,
  `docs/deployment/MIGRATION_SQLITE_MARIADB.md`, `docs/deployment/SERVER_CHANGELOG.md`,
  `docs/claude-reports/README.md`

## Base de données / migrations
- **V35**, automatique au démarrage. MariaDB : 8 instructions `ALTER TABLE … MODIFY COLUMN … TEXT NOT NULL`,
  idempotentes, sans réécriture de données. SQLite : aucune instruction, seul `user_version` passe à 35.
- Aucune base MariaDB réelle touchée. Aucune bascule.

## Configuration / données
Aucune clé de configuration. Aucune donnée de joueur modifiée.

## Tests automatiques
- Local, ciblé :
  - `MySqlDialectTranslationTest` 15/15, `SqlDialectTest` 6/6, `SchemaMigratorTest` 42/42 ;
  - `SchemaMigrationRunnerTest` 5/5, `DatabaseManagerEngineTest` 6/6 ;
  - `SqliteMigrationToolTest` 16/16, `MigrationCliTest` 6/6, `LogicalChecksumTest` 4/4.
- **Contre une MariaDB 10.11.14 locale jetable** (espace utilisateur, port 33306 en boucle locale,
  mode `STRICT_TRANS_TABLES`) :
  - `MariaDbFreeTextIntegrationTest` 2/2, `MariaDbMigrationIntegrationTest` 4/4 ;
  - `MariaDbSchemaIntegrationTest` 4/4, `MariaDbRepositoryIntegrationTest` 8/8.
  - Le premier passage a révélé une dépendance d'ordre entre mes deux tests : `wipe()` ne connaît
    pas les tables bâtiment. C'est corrigé dans le test. Instance arrêtée et données supprimées
    ensuite.
- Panel : `DatabaseTargetPageTest` 9/9, `DbTargetStoreTest` 6/6, `DocsPagesTest` 8/8, `DiagnosticHelpTest` 7/7.
- **Outil #42 sur une copie** de la sauvegarde DEV `20261013-predeploy-325` (portée en V35 par
  `SchemaMigrator.migrate` sur la copie) :
  - `audit` SUCCESS ;
  - `dry-run` → MariaDB jetable : **SUCCESS**, 39/39 tables identiques, 8 séquences OK ;
  - la description de 490 caractères passe sans troncature ;
  - empreinte de la sauvegarde inchangée (`f65a4456…`).
- CI GitHub :
  - premier passage de `9f3f435` : rouge sur le test SecretBox instable (voir plus haut) ;
  - `6f0ce5a` : vert sur les deux runs ;
  - merge `048e0f2` : `QuestProgressEngineTest.everyObjectiveOfTheFirstStepIsAnnounced` a échoué
    une fois. L'arbre est identique à `6f0ce5a`, déjà vert deux fois ; le test fait une course
    entre l'`accept` asynchrone et `performTicks(2)`. Le job relancé est passé au vert, et le panel
    n'a été déployé qu'ensuite.
- `deploy-verygames.sh` a relancé les tests du plugin en local : verts.

## Tests manuels à effectuer
- `PENDING MANUAL VALIDATION` — PlugAdmin DEV > Base de données : l'étape 6 « Bloquants connus »
  affiche « fait » avec la mention V35 ; le backend affiche SQLite, schéma V35.
- Aucun test Minecraft n'a été effectué. Aucun n'est requis : pas de changement de gameplay.

## Résultat attendu
Le plugin peut écrire des descriptions, messages ou chemins de plus de 191 caractères sur MariaDB.
Le dry-run de l'outil #42 réussit sur une copie DEV non tronquée.

## Reset / retour à l'état initial
Rien à réinitialiser : V35 n'a rien modifié sur SQLite.

## Déploiement VeryGames
### À transférer
JAR `048e0f2` (2 808 014 o, SHA-256 `d8eca3ef83f47dffbe8ec50b81244fc10099f559eb1887f7b0d8cb98b9aaa9cc`).
**Effectué** par `deploy-verygames.sh -y`. JAR distant retéléchargé, SHA identique. Panel redéployé
(`DEPLOYED` = `048e0f2`).
### Ne PAS transférer/altérer
`config.yml`, `data.db`, `database.type`, contenus YAML, mondes.
### Redémarrage requis
Oui. Fait par `verygames-restart.sh` avec 0 joueur en ligne, retour ONLINE, RPGQuest vert dans
`/plugins`.
### Migration automatique
V35 au démarrage (sans effet sur SQLite).

## Rollback
- JAR : `scripts/rollback-verygames.sh --latest`, qui restaure
  `rpgquest-20261014T073059Z-predeploy.jar` (= `747ae18`, #42), puis redémarrage.
- Panel : `scripts/plugadmin/rollback.sh app`, qui restaure `/opt/plugadmin/releases/20261014-094510`.
- Un JAR V34 démarre sans erreur sur une base V35.

## Logs / diagnostic
- `~/.local/state/rpgquest-logs/deploy-354.log`, `panel-354.log`.
- Le « /health KO » de `deploy.sh` est la course connue au redémarrage. La vérification relancée
  donne 200, et `/database` répond 303 → `/login`.

## Documentation mise à jour
PERSISTENCE (tableau du dialecte, ligne « Textes libres NOT NULL »), ARCHITECTURE, current_state,
BIBLE, runbook `MIGRATION_SQLITE_MARIADB.md` (constat corrigé, essais du 14/10, case go/no-go
cochée), fiche panel `base-de-donnees.md`, SERVER_CHANGELOG.

## Limitations / travail restant
- Les colonnes ajoutées par `ALTER TABLE … ADD COLUMN … TEXT` restent `TEXT` sur MariaDB, comme
  avant. Si l'une d'elles était un jour indexée, MariaDB refuserait l'index. Le balayage du
  catalogue ne couvre que les `CREATE TABLE`.
- `MariaDbTestSupport.RPGQUEST_TABLES`, utilisée par `wipe()`, ne liste pas les tables ajoutées
  depuis #41 (bâtiments, rencontres…). Ce n'est pas bloquant, mais l'isolation des tests MariaDB
  reste partielle.
- Test instable restant, hors ticket : `QuestProgressEngineTest.everyObjectiveOfTheFirstStepIsAnnounced`
  (course entre l'accept asynchrone et 2 ticks).

## Prochaine étape suggérée
- #43 (base propre au panel), suivant dans la file.
- Corriger le test instable `QuestProgressEngineTest`.
