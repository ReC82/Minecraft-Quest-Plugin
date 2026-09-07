# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-07
* Heure : 13:15 (heure locale réelle de la machine)
* Sujet : Issue #40 — abstraire la couche de persistance RPGQuest et rendre le **moteur de base
  configurable** (socle de la future migration SQLite → MySQL/MariaDB). **Ne comprend pas** #41
  (backend MySQL réel) ni #42 (migration des données).
* Statut : **DONE** (socle livré, SQLite inchangé, tests + build verts). #40 **non fermée** : voir
  « Critères d'acceptation » et « Travail restant pour #41 ».
* Branche Git : `feat/40-persistence-abstraction` (créée depuis `feat/51-plugadmin-outbound-agent`
  — dernier tip de la pile de branches du dépôt ; #40 ne partage aucun fichier avec #51).
* Commit(s) : voir « Commit(s) / branche ».
* Début de la tâche : 2026-09-07 12:49:02
* Fin de la tâche : 2026-09-07 13:25:10
* Durée totale : 00:36:08

## Demande

Refactoriser la persistance pour que : le code métier ne dépende plus directement de SQLite ;
SQLite reste supporté et **fonctionne exactement comme aujourd'hui** ; MySQL/MariaDB puisse être
ajouté proprement (sans branches `if MYSQL / else SQLITE` dispersées) ; les évolutions de schéma
soient versionnées et migrables ; les règles async Paper soient respectées ; aucun secret DB
versionné. Auditer d'abord tous les accès SQLite/SQL existants. Documenter l'architecture et créer
le rapport. Tests + build verts, backend SQLite vérifié.

## Audit — cartographie des accès SQL existants (avant refactorisation)

### Où vit le SQL

**Tout** le SQL du plugin est déjà confiné au package `com.lodygames.rpgquest.database`. Aucun
service de gameplay (quêtes, stories, variables, claims, économie, progression, onboarding…) ne
contient de SQL : ils passent par des repositories qui passent par `DatabaseManager#execute`.

### Classes ouvrant une connexion / exécutant du SQL

| Élément | Rôle | Spécificités moteur |
|---|---|---|
| `DatabaseManager` | connexion unique + thread `RPGQuest-Database` + `execute(Connection→T)` async | `DriverManager.getConnection("jdbc:sqlite:" + file)` **en dur**, `PRAGMA foreign_keys = ON` |
| `SchemaMigrator` | 17 migrations V1..V17, boucle d'application | `PRAGMA user_version`, `PRAGMA table_info` (V14/V15), `INTEGER PRIMARY KEY AUTOINCREMENT`, `BLOB`, `TEXT` |
| 20 repositories (`*Repository`) | 1 par domaine, `CompletableFuture`, `PreparedStatement`, types 100 % JDK | voir ci-dessous |

### Constructions SQL spécifiques SQLite recensées

| Construction | Fichiers |
|---|---|
| `ON CONFLICT (…) DO UPDATE SET … excluded.*` (upsert) | `PlayerVariableRepository`, `QuestProgressRepository` (×2), `StoryProgressRepository`, `ItemTravelCooldownRepository`, `PortalCooldownRepository`, `ResourceNodeRepository`, `EntitlementRepository`, `NpcBindingRepository`, `BackpackRepository` |
| `INSERT OR IGNORE` | `WalletRepository`, `ProgressionRepository` (×2), `StoreDeliveryRepository`, `ClaimRepository`, `PlacedBlockRepository`, `WaystoneRepository` (×2) |
| `Statement.RETURN_GENERATED_KEYS` + `getGeneratedKeys()` | `MarketRepository`, `NpcIdRepository` (portable, à re-vérifier sous MySQL) |
| `id INTEGER PRIMARY KEY AUTOINCREMENT` | migrations V4, V5, V9 (×2), V11 |
| `BLOB` | migrations V5, V9 |
| `PRAGMA user_version` / `PRAGMA table_info` | `SchemaMigrator`, `DatabaseManager` |
| helper de transaction `inTransaction`/`setAutoCommit(false)` **dupliqué** | `WalletRepository`, `ClaimRepository`, `MarketRepository`, `ProgressionRepository`, `BackpackRepository` (portable, non spécifique SQLite) |

### Accès synchrones / asynchrones, transactions

- **Async** : 100 % des opérations de gameplay passent par `DatabaseManager#execute` (thread
  dédié). Aucun SQL sur le thread principal.
- **Sync (assumé)** : `DatabaseManager#shutdown()` attend ≤ 5 s le thread DB, uniquement depuis
  `onDisable()`.
- **Transactions** : explicites (`setAutoCommit(false)`/`commit`/`rollback`) dans un seul
  `execute`, là où plusieurs écritures doivent être atomiques (portefeuille, claims, marché,
  progression, backpacks).

### Config

- Une seule clé : `database.file` (nom de fichier SQLite), validée par `ConfigValidator`.

### Tests dépendant de SQLite

- ~15 tests de repositories : `new DatabaseManager(tempDir.resolve("data.db"))`.
- `SchemaMigratorTest` (21) : `DriverManager.getConnection("jdbc:sqlite:…")` + `SchemaMigrator.migrate`.
- Aucun outil/admin ne lit/modifie `data.db` directement (le Control Panel a sa propre base
  `control-panel.db`, jamais `data.db` — voir #37).

### Conclusion de l'audit

Le point de couplage n'est **pas** dans le code métier (déjà propre) mais dans : (1) l'URL JDBC et
le `PRAGMA` en dur de `DatabaseManager` ; (2) le suivi de version `PRAGMA user_version` de
`SchemaMigrator` ; (3) la syntaxe SQLite (`ON CONFLICT`, `INSERT OR IGNORE`, `AUTOINCREMENT`) dans
les repositories et migrations. #40 traite (1) et (2) entièrement et **outille** (3) sans
réécrire les 20 repositories (réservé à #41, où MySQL est réellement disponible pour tester).

## Travail effectué

### 1. Abstractions de persistance (`com.lodygames.rpgquest.database`, nouveau)

- **`DatabaseSettings`** (record + `SqliteSettings` / `MySqlSettings` / `PoolSettings`) +
  **`DatabaseType`** (`SQLITE` / `MYSQL`, `mariadb` = alias). `describe()` sans secret ;
  `MySqlSettings#resolvePassword(env)` (mot de passe **uniquement** depuis l'environnement).
- **`SqlDialect`** (interface) + **`SqliteDialect`** / **`MySqlDialect`** : encapsulent les
  différences SQL — `upsert(...)` (`ON CONFLICT … DO UPDATE` vs `ON DUPLICATE KEY UPDATE …
  VALUES(...)`), `insertOrIgnore(...)` (`INSERT OR IGNORE` vs `INSERT IGNORE`),
  `autoIncrementPrimaryKey(...)` (`INTEGER … AUTOINCREMENT` vs `BIGINT … AUTO_INCREMENT`),
  `columnExists(...)` (`PRAGMA table_info` vs `information_schema.columns`), `healthQuery()`.
- **`SchemaHistory`** (interface) + **`PragmaUserVersionHistory`** (SQLite natif — **inchangé**,
  aucune migration rejouée sur `data.db` existant) + **`MigrationTableHistory`** (table portable
  `rpgquest_schema_migrations`, pour MySQL/#41 et tout moteur sans registre natif).
- **`SchemaMigration`** (record : `version`, `name`, `Step.apply(Connection, SqlDialect)`) +
  **`SchemaMigrationRunner`** (application ordonnée, une fois ; rejeu = no-op ; échec →
  **`SchemaMigrationException`** nommant l'étape, version non avancée ; `targetVersion()`).
- **`SchemaMigrator`** refactoré : n'est plus qu'un **catalogue** `List<SchemaMigration> ALL`
  (V1..V17, **SQL inchangé** — seules V14/V15 passent par `dialect.columnExists`, générant le même
  `PRAGMA table_info` pour SQLite). `migrate(Connection)` (API statique) conservée pour les tests.
- **`DatabaseEngine`** (interface) + **`SqliteDatabaseEngine`** (fichier `data.db`,
  `PRAGMA foreign_keys = ON`, `PragmaUserVersionHistory` — comportement d'avant #40) +
  **`MySqlDatabaseEngine`** (reconnu ; `openConnection()` lève une `SQLException` explicite
  pointant #41 ; fournit déjà `MySqlDialect` + `MigrationTableHistory`) +
  **`DatabaseEngineFactory`** (**unique** `switch (type)`).
- **`DatabaseManager`** : constructeur `DatabaseManager(DatabaseEngine)` (nouveau, primaire) ;
  `DatabaseManager(Path)` conservé (sélectionne SQLite — **comportement inchangé**, utilisé par
  les tests de repositories). `initialize()` : `engine.openConnection()` +
  `engine.configureSession()` + `SchemaMigrationRunner`. Nouveaux : `healthCheck()` (non bloquant,
  ne lève **jamais**), `dialect()`, `engineType()`, `describeEngine()`. `shutdown()` appelle aussi
  `engine.close()`.
- **`DatabaseService`** : construit le moteur via `DatabaseEngineFactory.create(config.database(),
  dataFolder)` puis le `DatabaseManager`. Log : `moteur sqlite (data.db)` (sans secret).

### 2. Configuration

- **`PluginConfig`** : le champ `String databaseFile` devient `DatabaseSettings database` ;
  `databaseFile()` conservé comme raccourci (`database().sqlite().file()`) pour les appelants
  historiques.
- **`ConfigValidator#validateDatabase`** : lit `database.type` (défaut `sqlite`),
  `database.sqlite.file` (**alias rétrocompatible** : `database.file`), `database.mysql.*`.
  Refuse : type inconnu (`database.type`), `database.file`/`sqlite.file` explicitement vide,
  traversée de chemin, `mysql.password`/`mysql.pass` **en clair** (→ « utiliser password-env »),
  port hors [1..65535], `pool.max-size` ≤ 0.
- **`config.yml`** (bundled) + `RPGQUEST_BIBLE.md` : nouvelle section `database:` documentée
  (type, sqlite, mysql avec `password-env`, pool). `src/main/resources/backup-ftp/config.yml`
  (fixture « config historique VeryGames, 4 clés ») **non modifiée** — elle démarre via l'alias
  `database.file`.

### 3. Règles async / concurrence (revues pour un backend distant)

- **Aucune** opération DB synchrone ajoutée. Tout reste sur `DatabaseManager#execute`.
- Pool de connexions **modélisé** (`PoolSettings`) mais non imposé à SQLite (aucune dépendance
  ajoutée). #41 : emprunt/rendu d'une connexion par `execute` pour MySQL, contrat public inchangé.
- `healthCheck()` (nouveau) constate une base momentanément indisponible sans planter l'appelant.

## Fichiers créés

Plugin (`src/main/java/com/lodygames/rpgquest/database/`) :
`DatabaseType`, `DatabaseSettings`, `SqlDialect`, `SqliteDialect`, `MySqlDialect`,
`SchemaHistory`, `PragmaUserVersionHistory`, `MigrationTableHistory`, `SchemaMigration`,
`SchemaMigrationException`, `SchemaMigrationRunner`, `DatabaseEngine`, `SqliteDatabaseEngine`,
`MySqlDatabaseEngine`, `DatabaseEngineFactory`.

Tests (`src/test/java/com/lodygames/rpgquest/database/`) :
`DatabaseSettingsTest` (5), `SqlDialectTest` (6), `SchemaMigrationRunnerTest` (5),
`DatabaseEngineTest` (5), `DatabaseManagerEngineTest` (4).

Docs : `docs/PERSISTENCE.md` (nouveau),
`docs/claude-reports/2026-09-07_1315_persistence-abstraction-issue-40.md` (ce rapport).

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/database/{DatabaseManager,DatabaseService,SchemaMigrator}.java`
- `src/main/java/com/lodygames/rpgquest/config/{PluginConfig,ConfigValidator,ConfigService}.java`
- `src/main/resources/config.yml` (section `database:`)
- `src/test/java/com/lodygames/rpgquest/config/ConfigValidatorTest.java` (+8 tests `database:`)
- Docs : `docs/ARCHITECTURE.md` (section `database`), `docs/current_state.md`, `docs/INDEX.md`,
  `docs/RPGQUEST_BIBLE.md` (§17), `.ai/CONTEXT.md`, `docs/claude-reports/README.md`

**Aucun repository métier modifié.** Aucun service de gameplay modifié. `RPGQuestBootstrap`
inchangé (il ne référence que `DatabaseService`).

## Base de données / migrations

- **Aucune nouvelle migration `data.db`.** Version de schéma toujours **17**
  (`SchemaMigrator.CURRENT_VERSION`).
- Le SQL des 17 migrations est **identique** à avant #40.
- SQLite : suivi de version **toujours** via `PRAGMA user_version` — un `data.db` existant
  (prod : `PRAGMA user_version = 17`) est reconnu tel quel, **aucune migration rejouée**, aucun
  risque sur les données.
- La table portable `rpgquest_schema_migrations` n'est créée **que** par `MigrationTableHistory`,
  utilisé uniquement par le moteur MySQL (#41) et les tests du runner — **jamais** sur `data.db`.

## Configuration / données

- Nouvelle section `config.yml` `database:` (type + sqlite + mysql + pool). Défaut = SQLite
  `data.db` → comportement identique.
- Secret MySQL : `database.mysql.password-env` = **nom** d'une variable d'environnement (défaut
  `RPGQUEST_DB_PASSWORD`), jamais le mot de passe. Résolu à l'ouverture de connexion.
- **Aucune modification manuelle de données de production.** Aucun secret dans Git, logs ou ce
  rapport.

## Tests automatiques

- **Nouveaux** : 25 tests (`database.*` = `DatabaseSettingsTest` 5, `SqlDialectTest` 6,
  `SchemaMigrationRunnerTest` 5, `DatabaseEngineTest` 5, `DatabaseManagerEngineTest` 4) + 8 dans
  `ConfigValidatorTest`.
- **Inchangés et verts** : `SchemaMigratorTest` (21), tous les `*RepositoryTest` (non-régression
  SQLite bout-en-bout), `ConfigValidatorTest` (39 existants), `ConfigFileCompleterTest` (7).
- Ciblé : `./gradlew :test --tests 'com.lodygames.rpgquest.database.*' --tests
  'com.lodygames.rpgquest.config.*'` → **167 tests, 0 échec**.
- Complet : `./gradlew test` puis `./gradlew build` → **BUILD SUCCESSFUL** (voir « Commandes »).
- Backend SQLite vérifié : `DatabaseManagerEngineTest.legacyPathConstructorStillWorksExactlyAsBefore`
  applique les 17 migrations via le constructeur historique et lit `PRAGMA user_version = 17` ;
  `SchemaMigrationRunnerTest.realCatalogueTargetsTheDeclaredCurrentVersion` applique
  `SchemaMigrator.ALL` sur une base neuve et atteint `CURRENT_VERSION`.

### Commandes exécutées

```
./gradlew :test --tests 'com.lodygames.rpgquest.database.*' --tests 'com.lodygames.rpgquest.config.*'
   -> 167 tests, 0 échec
./gradlew test build      -> BUILD SUCCESSFUL   (suite complète : plugin + web-api + control-panel)
```

Machine DEV ~900 Mo RAM : suite plugin lente (~12 min) mais verte.

## Tests manuels à effectuer

Aucun test Minecraft requis pour #40 (refactor de persistance, couvert par JUnit pur).
Vérification manuelle facultative : démarrer un serveur local, constater le log
`Base de données RPGQuest : moteur sqlite (data.db).` et l'absence de régression ; mettre
`database.type: mysql` et constater le refus de démarrage explicite (message pointant #41).

## Résultat attendu

- `database.type: sqlite` (ou absent) : RPGQuest démarre **exactement comme avant #40**,
  `data.db` inchangé.
- `database.type: mysql` : refus de démarrage propre — log
  `Impossible d'initialiser la base de données RPGQuest (mysql …@…:…/…)` + `SQLException`
  « Le backend MySQL/MariaDB n'est pas encore disponible (issue #41). Utiliser
  « database.type: sqlite » pour démarrer. ». Le gameplay ne dépend jamais du moteur.
- Config DB invalide (type inconnu, mot de passe en clair, port hors plage) : refus de démarrage
  avec un message précis (`ConfigValidationException`).

## Reset / retour à l'état initial

`git checkout feat/51-plugadmin-outbound-agent` (ou supprimer la branche `feat/40-…`). Aucune
donnée modifiée. Aucun schéma `data.db` touché. `config.yml` d'un serveur existant reste valide
(alias `database.file`).

## Déploiement VeryGames

### À transférer
**Rien pour cette session.** #40 est un refactor interne : aucun changement de comportement pour
un serveur SQLite. Un futur déploiement du JAR n'a d'effet que si `config.yml` est explicitement
passé à `database.type: mysql` (ce que #40 ne fait pas et qui échouerait proprement).

### Ne PAS altérer
`data.db`, `config.yml` existant (l'alias `database.file` reste valide), mondes, Citizens.

### Redémarrage requis
Sans objet pour #40 (rien à déployer). Le JAR de la branche, déployé, ne change rien pour un
serveur SQLite.

### Migration automatique
Aucune (schéma toujours V17). La table `rpgquest_schema_migrations` n'est jamais créée sur
`data.db`.

## Rollback

`git revert` des commits #40 ; ou `git checkout feat/51-…`. Aucune donnée à défaire.

## Logs / diagnostic

- Démarrage : `Base de données RPGQuest : moteur sqlite (data.db).` puis (si config invalide ou
  moteur mysql) `Impossible d'initialiser la base de données RPGQuest (…)` + cause.
- `DatabaseManager#healthCheck()` : `true`/`false` non bloquant (utilisable par un futur
  diagnostic Control Panel — hors périmètre #40).

## Documentation mise à jour

`docs/PERSISTENCE.md` (nouveau — architecture, sélection SQLite/MySQL, migrations, async, secrets,
stratégie de tests, travail #41), `docs/ARCHITECTURE.md` (§ `database`), `docs/current_state.md`
(§ Persistance), `docs/INDEX.md`, `docs/RPGQUEST_BIBLE.md` (§17 : section moteur configurable +
table des migrations remise à jour V1..V17), `.ai/CONTEXT.md` (§ Persistance),
`docs/claude-reports/README.md`.

## Commit(s) / branche

Branche `feat/40-persistence-abstraction` (depuis `feat/51-plugadmin-outbound-agent`), poussée,
**non fusionnée** :

| Commit | Sujet |
|---|---|
| `8a6c18e` | `feat(database): moteur SQL configurable + migrations portables (issue #40)` |
| `ce23ceb` | `feat(config): section database: (moteur, secrets via env) (issue #40)` |
| `37b3a98` | `docs: couche de persistance abstraite et moteur configurable (issue #40)` (ce rapport ; hash final après amend : voir `git log`) |

## Critères d'acceptation (issue #40)

- [x] SQLite n'est plus une hypothèse structurelle du code métier — le SQL était déjà confiné au
  package `database` ; le moteur/dialecte/historique sont maintenant abstraits ; aucun service de
  gameplay ne touche au moteur.
- [x] Le backend DB peut être choisi par configuration (`database.type`, `DatabaseEngineFactory`).
- [x] SQLite continue de fonctionner — SQL des migrations inchangé, `PRAGMA user_version`
  conservé, constructeur/API historiques conservés, tous les tests existants verts.
- [x] La couche de persistance est **prête** pour MySQL/MariaDB sans branches conditionnelles
  dispersées — un seul `switch` (la factory) ; `SqlDialect` / `SchemaHistory` / `DatabaseEngine`
  couvrent les différences ; `MySqlDialect` + `MigrationTableHistory` déjà implémentés et testés.
- [x] Un mécanisme de migration/versionnement de schéma existe (`SchemaMigration` +
  `SchemaMigrationRunner` + `SchemaHistory`, version courante / cible / échec propre).
- [x] Les règles async Minecraft sont respectées — aucune opération DB synchrone ajoutée.
- [x] Aucun secret DB versionné — `password-env` uniquement, testé.
- [x] Tests et build verts.
- [x] Architecture et décisions documentées (`docs/PERSISTENCE.md`).

> #40 **reste ouverte** : les critères sont satisfaits au sens « socle », mais le backend MySQL
> réel (donc la démonstration « le même build démarre avec l'un ou l'autre ») est explicitement
> #41. Fermeture à l'appréciation de l'owner.

## Limitations / travail restant pour #41

1. **Dépendance driver** MySQL/MariaDB (`plugin.yml` `libraries:` + `build.gradle.kts`
   `testImplementation`).
2. **Pool de connexions** : corps réel de `MySqlDatabaseEngine.openConnection()` ;
   `DatabaseManager` empruntant/rendant une connexion par `execute` (contrat public inchangé).
3. **Dialecte dans les 20 repositories** : faire passer `ON CONFLICT` / `INSERT OR IGNORE` /
   `AUTOINCREMENT` par `SqlDialect` (liste exhaustive dans `docs/PERSISTENCE.md` §10). Non fait
   ici car non testable sans MySQL et à risque de régression sur du code gameplay critique.
4. **Types de colonnes des migrations** : `TEXT` → `VARCHAR(n)` pour les colonnes de PK/index,
   `BLOB` → `LONGBLOB`, migration par migration.
5. **Reconnexion / backoff** au niveau du pool (`healthCheck()` déjà en place).
6. **Dédup du helper de transaction** (`inTransaction` copié dans 5 repositories) — cleanup
   opportun à faire en même temps que le point 3.
7. **#42** : création de la base MySQL VeryGames + migration des données `data.db` → MySQL.

## Prochaine étape suggérée

1. Revue de `feat/40-persistence-abstraction`.
2. #41 : implémenter `MySqlDatabaseEngine` + pool + dialecte dans les repositories + types de
   colonnes, avec un serveur MySQL de test.
3. Commenter #40 avec le résultat ; fermeture par l'owner si le périmètre « socle » suffit.
