# Persistance RPGQuest — architecture, moteur configurable, migrations

> Livré par l'**issue #40** (« abstraire la persistance et rendre le moteur de base configurable »).
> #40 pose le **socle** : le moteur SQL devient un détail d'infrastructure choisi par
> configuration. Le **backend MySQL/MariaDB réel** (driver, pool) est l'**issue #41** ; la
> **migration des données** VeryGames est l'**issue #42**.

---

## 1. Principe

Le moteur de base de données est un **détail d'infrastructure**, pas une règle métier. Le code de
gameplay (quêtes, stories, variables joueur, claims, économie, progression, onboarding…) ne
contient **aucun SQL** et ne teste **jamais** le type de moteur. Il manipule des repositories, qui
manipulent une connexion via `DatabaseManager#execute`.

Le seul endroit qui connaît le moteur concret est **`DatabaseEngineFactory`** — un unique
`switch (type)`, jamais dupliqué ailleurs.

```
Services gameplay  ──▶  Repositories (database.*)  ──▶  DatabaseManager#execute(Connection→T)
                                                          │
                                                          ▼
                                              DatabaseEngine (abstraction #40)
                                              ├── SqliteDatabaseEngine   (câblé)
                                              └── MySqlDatabaseEngine    (reconnu ; corps réel = #41)
                                                    ├── SqlDialect        (upsert / insert-ignore / identité / colonne)
                                                    └── SchemaHistory     (version appliquée)
```

---

## 2. Sélection du moteur (`config.yml`)

```yaml
database:
  type: sqlite            # sqlite (défaut) | mysql (alias : mariadb)

  sqlite:
    file: data.db         # simple nom de fichier, dans plugins/RPGQuest/

  mysql:
    host: localhost
    port: 3306
    database: rpgquest
    username: rpgquest
    password-env: RPGQUEST_DB_PASSWORD   # NOM d'une variable d'environnement, jamais le mot de passe
    pool:
      max-size: 10
      connection-timeout-ms: 10000
      max-lifetime-ms: 1800000
```

- **Défaut** : `sqlite`, fichier `data.db` — comportement identique à avant #40. Un `config.yml`
  existant sans section `database:` complète, ou avec seulement l'ancienne clé `database.file`,
  démarre sans changement (voir §7).
- **`type: mysql`** est **reconnu et validé** par la configuration mais le moteur refuse
  actuellement d'ouvrir une connexion, avec un message explicite renvoyant à #41. Le gameplay ne
  dépend jamais du moteur : basculer aujourd'hui échoue proprement au démarrage, il ne « casse »
  rien de subtil.
- Validation stricte de la section (`ConfigValidator#validateDatabase`) : type inconnu, port hors
  plage, mot de passe en clair (`mysql.password` / `mysql.pass`) → refus de démarrage avec un
  message précis.

---

## 3. Gestion des secrets

- Le mot de passe MySQL n'est **jamais** dans `config.yml`, le dépôt, les logs ou les rapports.
- `config.yml` ne contient que **`password-env`** : le *nom* d'une variable d'environnement.
- Le mot de passe est résolu à l'ouverture de la connexion
  (`DatabaseSettings.MySqlSettings#resolvePassword`), depuis `System.getenv`.
- `DatabaseSettings#describe()` et les messages d'erreur du moteur sont conçus pour ne jamais
  contenir de secret (`mysql <user>@<host>:<port>/<db>`, sans mot de passe). Testé
  (`DatabaseSettingsTest`, `DatabaseEngineTest`).
- Même discipline que `RPGQUEST_WEB_API_TOKEN` / `RPGQUEST_WEB_ADMIN_TOKEN` ailleurs dans le
  projet.

---

## 4. Abstractions (`com.lodygames.rpgquest.database`)

| Type | Rôle |
|---|---|
| **`DatabaseSettings`** (record) | configuration validée : `type`, `sqlite.file`, `mysql.{host,port,database,username,password-env,pool}` |
| **`DatabaseType`** (enum) | `SQLITE` / `MYSQL` (`mariadb` = alias) |
| **`DatabaseEngine`** (interface) | ouvre une connexion, applique les réglages de session, expose le `SqlDialect` et le `SchemaHistory` du moteur, `describe()` sans secret |
| **`SqliteDatabaseEngine`** | fichier `data.db`, `PRAGMA foreign_keys = ON`, `PRAGMA user_version` — **comportement inchangé** |
| **`MySqlDatabaseEngine`** | reconnu ; `openConnection()` lève une `SQLException` explicite (#41) ; fournit déjà `MySqlDialect` + `MigrationTableHistory` |
| **`DatabaseEngineFactory`** | **unique** point de choix du moteur (`create(settings, dataFolder)`) |
| **`SqlDialect`** (interface) | différences SQL réelles : `upsert(...)`, `insertOrIgnore(...)`, `autoIncrementPrimaryKey(...)`, `columnExists(...)`, `healthQuery()` |
| **`SqliteDialect`** / **`MySqlDialect`** | `ON CONFLICT … DO UPDATE SET … excluded.*` vs `ON DUPLICATE KEY UPDATE … VALUES(...)` ; `INSERT OR IGNORE` vs `INSERT IGNORE` ; `INTEGER … AUTOINCREMENT` vs `BIGINT … AUTO_INCREMENT` ; `PRAGMA table_info` vs `information_schema.columns` |
| **`SchemaHistory`** (interface) | version de schéma appliquée : `currentVersion` / `recordApplied` |
| **`PragmaUserVersionHistory`** | SQLite natif (`PRAGMA user_version`) — **inchangé**, aucune table, aucune migration rejouée sur `data.db` existant |
| **`MigrationTableHistory`** | table portable `rpgquest_schema_migrations(version, name, applied_at)` — pour MySQL (#41) et tout moteur sans registre natif |
| **`SchemaMigration`** (record) | une étape numérotée : `version`, `name`, `Step.apply(Connection, SqlDialect)` |
| **`SchemaMigrationRunner`** | applique les étapes en attente **dans l'ordre**, une fois ; rejeu = no-op ; échec → `SchemaMigrationException` identifiant l'étape ; `targetVersion()` |
| **`SchemaMigrator`** | **catalogue** des migrations V1..V17 (SQL inchangé) + `migrate(Connection)` historique (SQLite) |
| **`DatabaseManager`** | possède la connexion, sérialise tout sur le thread `RPGQuest-Database`, `execute(Connection→T)` async, `healthCheck()`, `dialect()` |
| **`DatabaseService`** (`PluginService`) | construit le moteur depuis la config puis `DatabaseManager` |

---

## 5. Contrat repositories / services

Inchangé par #40 :

- un repository prend un `DatabaseManager` et n'expose que des méthodes **asynchrones**
  (`CompletableFuture<T>`), en types 100 % JDK (`UUID`, `Instant`, `Optional`, `byte[]`…), sans
  aucun type Bukkit/Paper — testables en JUnit pur ;
- toute opération passe par `database.execute(connection -> …)` : le `connection` fourni est celui
  du thread base de données, jamais partagé, jamais fermé par l'appelant ;
- une opération multi-écritures atomique utilise une transaction JDBC explicite
  (`setAutoCommit(false)` / `commit` / `rollback`) **dans un seul `execute`** (voir
  `WalletRepository`, `ClaimRepository`, `MarketRepository`, `ProgressionRepository`,
  `BackpackRepository`) ;
- les services de gameplay appellent les repositories et **remettent sur le thread principal**
  tout callback qui touche l'API Paper.

`DatabaseManager#dialect()` est disponible pour qu'un repository construise du SQL portable via
`SqlDialect` — les repositories existants ne l'utilisent pas encore (voir §8).

---

## 6. Migrations de schéma

- Chaque évolution de schéma est une **`SchemaMigration`** numérotée (V1, V2, …), listée dans
  l'ordre dans `SchemaMigrator.ALL`. Version attendue par ce build : `SchemaMigrator.CURRENT_VERSION`.
- `SchemaMigrationRunner` :
  - lit la version appliquée via `SchemaHistory` ;
  - applique **uniquement** les migrations de version supérieure, **dans l'ordre**, **une fois** ;
  - enregistre chaque étape réussie (`recordApplied`) — reprise possible après un échec partiel ;
  - sur échec : `SchemaMigrationException` nommant l'étape ; la version enregistrée ne dépasse pas
    la dernière migration réussie ; à traiter comme un **échec de démarrage**.
- Les étapes restent **idempotentes** (`CREATE TABLE IF NOT EXISTS`, `ALTER TABLE` gardé par
  `dialect.columnExists`) : le DDL n'est pas transactionnel sur tous les moteurs.
- **Choix du registre de version, documenté** :
  - **SQLite conserve `PRAGMA user_version`** (`PragmaUserVersionHistory`). C'est un compteur de
    version natif, sans table, et **c'est ce qu'utilisent les bases `data.db` existantes** : aucune
    migration n'est rejouée, aucun risque sur les données de production.
  - **MySQL/MariaDB utilisera la table portable `rpgquest_schema_migrations`**
    (`MigrationTableHistory`) : `PRAGMA` n'existe pas, et une table donne en prime l'historique
    horodaté demandé pour les évolutions futures.
  - Aucune librairie de migration externe (Flyway/Liquibase) : le besoin est couvert par ~200
    lignes maison, cohérent avec la règle « pas de dépendance externe si une intégration simple
    suffit ».

---

## 7. Compatibilité SQLite (obligation #40)

- Le SQL des migrations V1..V17 est **identique** à avant #40 (seules V14/V15 passent par
  `dialect.columnExists`, qui génère le même `PRAGMA table_info` pour SQLite).
- `DatabaseManager(Path)` (constructeur historique) fonctionne toujours et sélectionne SQLite —
  utilisé tel quel par ~15 tests de repositories.
- `SchemaMigrator.migrate(Connection)` (API statique) est conservée — `SchemaMigratorTest`
  (21 tests) passe sans modification.
- `config.yml` : `database.file: data.db` reste accepté comme **alias** de `database.sqlite.file`.
  Le fichier historique VeryGames (`src/main/resources/backup-ftp/config.yml`, 4 clés) démarre
  sans changement.
- `PluginConfig#databaseFile()` est conservé (raccourci vers `database().sqlite().file()`).

---

## 8. Règles async / concurrence (revues pour un backend distant)

- **Aucune requête SQL bloquante sur le thread principal** : tout passe par
  `DatabaseManager#execute`, exécuté sur le thread `RPGQuest-Database`. #40 n'ajoute **aucune**
  opération DB synchrone.
- **Pas d'ouverture de connexion par requête** : SQLite garde sa connexion unique ; MySQL (#41)
  utilisera un **pool** (`DatabaseSettings.PoolSettings` déjà modélisé), jamais un
  `DriverManager.getConnection` par appel.
- **Transactions** : déjà explicites là où c'est nécessaire (§5).
- **Timeouts / indisponibilité** : `PoolSettings.connectionTimeoutMs` borne l'attente ;
  `DatabaseManager#healthCheck()` (nouveau, non bloquant, ne lève jamais) permet de constater une
  base momentanément indisponible sans planter l'appelant. La stratégie de reconnexion/backoff
  côté pool est détaillée dans #41.
- `DatabaseManager#shutdown()` reste volontairement bloquant (≤ 5 s), uniquement depuis
  `onDisable()`.

---

## 9. Stratégie de tests

Tous en **JUnit pur** (aucun MockBukkit) — la couche `database` ne dépend d'aucun type Bukkit.

| Test | Vérifie |
|---|---|
| `DatabaseSettingsTest` | défauts ; `describe()` sans mot de passe ; résolution du mot de passe depuis l'environnement uniquement |
| `SqlDialectTest` | SQL généré par `SqliteDialect` / `MySqlDialect` (upsert, insert-ignore, identité) ; `columnExists` réel sur SQLite |
| `MigrationTableHistory` (via runner) | `currentVersion` 0 → N, `recordApplied`, `ensureInitialised` |
| `SchemaMigrationRunnerTest` | ordre d'application ; reprise depuis la version enregistrée ; rejeu = no-op ; migration fautive → `SchemaMigrationException` + version non avancée ; versions dupliquées rejetées ; catalogue réel V1..V17 atteint `CURRENT_VERSION` |
| `DatabaseEngineTest` | la factory choisit le bon moteur ; `SqliteDatabaseEngine` ouvre/configure une connexion ; `MySqlDatabaseEngine.openConnection()` échoue proprement en pointant #41 ; aucun secret dans `describe()` / message d'erreur |
| `DatabaseManagerEngineTest` | constructeur `Path` historique inchangé ; init via moteur SQLite explicite applique les migrations ; `healthCheck()` faux avant init, vrai après, ne lève jamais ; moteur MySQL → `initialize()` échoue avec un message actionnable |
| `SchemaMigratorTest` (existant, 21) | migrations SQLite bout-en-bout — **inchangé** |
| `ConfigValidatorTest` (+8) | défaut SQLite ; nouvelle sous-section `sqlite:` ; alias `database.file` ; sélection `mysql` sans mot de passe en clair ; type inconnu rejeté ; mot de passe en clair rejeté ; port hors plage rejeté ; `mariadb` alias |
| `*RepositoryTest` (existants) | tous les repositories métier — **inchangés**, prouvent la non-régression SQLite |

`./gradlew test` et `./gradlew build` : verts.

---

## 10. Travail restant pour #41 (backend MySQL réel)

1. **Dépendance driver** : ajouter `com.mysql:mysql-connector-j` (ou `org.mariadb.jdbc:mariadb-java-client`)
   à `plugin.yml` `libraries:` (résolu par le `LibraryLoader` de Paper, comme `sqlite-jdbc`) et à
   `build.gradle.kts` en `testImplementation`.
2. **Pool** : implémenter `MySqlDatabaseEngine.openConnection()` avec un `DataSource` de pool
   (HikariCP léger, ou pool JDK). `DatabaseManager` devra emprunter/rendre une connexion par
   `execute` au lieu de garder une connexion unique — le contrat public ne change pas.
3. **Dialecte dans les repositories** : faire passer le SQL SQLite-spécifique par `SqlDialect`.
   Sites recensés (audit #40) :
   - `ON CONFLICT … DO UPDATE` : `PlayerVariableRepository`, `QuestProgressRepository` (×2),
     `StoryProgressRepository`, `ItemTravelCooldownRepository`, `PortalCooldownRepository`,
     `ResourceNodeRepository`, `EntitlementRepository`, `NpcBindingRepository`, `BackpackRepository` ;
   - `INSERT OR IGNORE` : `WalletRepository`, `ProgressionRepository` (×2), `StoreDeliveryRepository`,
     `ClaimRepository`, `PlacedBlockRepository`, `WaystoneRepository` (×2) ;
   - `Statement.RETURN_GENERATED_KEYS` : `MarketRepository`, `NpcIdRepository` (OK sur les deux
     moteurs, à re-vérifier).
4. **Types de colonnes des migrations** : traduire `TEXT` → `VARCHAR(n)` pour les colonnes de clé
   primaire / indexées, `BLOB` → `LONGBLOB`, `INTEGER PRIMARY KEY AUTOINCREMENT` →
   `dialect.autoIncrementPrimaryKey(...)`. Migration par migration ; les tables actuelles sont
   toutes à clé primaire composite, donc les colonnes de PK doivent devenir `VARCHAR`.
5. **Historique** : `MySqlDatabaseEngine` renvoie déjà `MigrationTableHistory` — rien à faire.
6. **Session** : `MySqlDatabaseEngine.configureSession` (fuseau horaire, `sql_mode`…).
7. **Reconnexion / indisponibilité** : politique de retry/backoff au niveau du pool ; le
   `healthCheck()` est déjà là.
8. **Validation VeryGames** : #42 (création de la base, migration des données `data.db` → MySQL).
