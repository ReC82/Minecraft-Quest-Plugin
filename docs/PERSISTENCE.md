# Persistance RPGQuest — architecture, moteur configurable, migrations

> **#40** a posé le socle : le moteur SQL est un détail d'infrastructure choisi par configuration.
> **#41** ajoute le **backend MySQL/MariaDB réel** — driver *MariaDB Connector/J*, pool *HikariCP*,
> dialecte SQL/DDL MariaDB dans les repositories et les migrations, tests d'intégration contre un
> serveur MariaDB. SQLite reste le défaut et fonctionne **exactement comme avant**. La **migration
> des données** `data.db` → MariaDB et la **bascule de production** sont l'**issue #42**.

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
    username: rpgquest    # compte dédié, jamais admin global (§11)
    password-env: RPGQUEST_DB_PASSWORD   # NOM d'une variable d'environnement, jamais le mot de passe
    ssl-mode: disable     # disable | trust | verify-ca | verify-full
    pool:
      minimum-idle: 2
      maximum-pool-size: 10
      connection-timeout-ms: 10000
      max-lifetime-ms: 1800000
      keepalive-ms: 0     # 0 = désactivé ; sinon < max-lifetime-ms
```

- **Défaut** : `sqlite`, fichier `data.db` — comportement identique à avant #40/#41. Un
  `config.yml` existant sans section `database:` complète, ou avec seulement l'ancienne clé
  `database.file`, démarre sans changement (voir §7).
- **`type: mysql`** (alias `mariadb`) : backend réel — driver *MariaDB Connector/J* + pool
  *HikariCP*. Au démarrage, `MySqlDatabaseEngine.start()` construit le pool, vérifie la connexion
  et applique les migrations. Si la base est injoignable ou mal configurée : **échec propre**
  (`SQLException` claire), une seule tentative, pas de boucle de reconnexion — le plugin ne sert
  pas de gameplay sur un schéma incomplet.
- Validation stricte de la section (`ConfigValidator#validateDatabase`) : type / `ssl-mode`
  inconnus, port hors plage, `minimum-idle` > `maximum-pool-size`, `connection-timeout-ms` trop
  court, mot de passe en clair (`mysql.password` / `mysql.pass`) → refus de démarrage avec un
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
| **`DatabaseSettings`** (record) | configuration validée : `type`, `sqlite.file`, `mysql.{host,port,database,username,password-env,ssl-mode,pool}` |
| **`DatabaseType`** (enum) | `SQLITE` / `MYSQL` (`mariadb` = alias) |
| **`DatabaseEngine`** (interface) | `start()` (ouverture / pool + contrôle de connectivité), `borrow()` / `release()` d'une connexion par unité de travail, `dialect()`, `schemaHistory()`, `close()`, `describe()` sans secret |
| **`SqliteDatabaseEngine`** | fichier `data.db`, `PRAGMA foreign_keys = ON`, `PRAGMA user_version`, **connexion unique réutilisée** — **comportement inchangé** |
| **`MySqlDatabaseEngine`** | driver *MariaDB Connector/J* + pool *HikariCP* ; `start()` construit le pool et vérifie la connexion (`SELECT VERSION()`) ; échec propre si injoignable / mot de passe absent ; `connectionInitSql` = `time_zone='+00:00'`, `sql_mode` strict |
| **`DatabaseEngineFactory`** | **unique** point de choix du moteur (`create(settings, dataFolder)`) |
| **`SqlDialect`** (interface) | `rewrite(sql)` (DML repositories), `ddl(sql)` (DDL migrations), + `upsert(...)`, `insertOrIgnore(...)`, `autoIncrementPrimaryKey(...)`, `columnExists(...)`, `healthQuery()` |
| **`SqliteDialect`** | `rewrite`/`ddl` = **identité** (aucun changement de SQL possible sur SQLite) |
| **`MySqlDialect`** | `INSERT OR IGNORE` → `INSERT IGNORE` ; `ON CONFLICT (…) DO UPDATE SET c = excluded.c` → `ON DUPLICATE KEY UPDATE c = VALUES(c)` ; `TEXT` clé/index/`NOT NULL` → `VARCHAR(191)`, `TEXT` nullable → `TEXT` ; `INTEGER` → `BIGINT` ; `… AUTOINCREMENT` → `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY` ; `BLOB` → `LONGBLOB` ; `… ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin` ; `CREATE INDEX IF NOT EXISTS` → `ALTER TABLE … ADD INDEX IF NOT EXISTS` ; `columnExists` via `information_schema.columns` |
| **`SchemaHistory`** (interface) | version de schéma appliquée : `currentVersion` / `recordApplied` |
| **`PragmaUserVersionHistory`** | SQLite natif (`PRAGMA user_version`) — **inchangé**, aucune table, aucune migration rejouée sur `data.db` existant |
| **`MigrationTableHistory`** | table portable `rpgquest_schema_migrations(version, name, applied_at)` — utilisée par MariaDB |
| **`SchemaMigration`** (record) | une étape numérotée : `version`, `name`, `Step.apply(Connection, SqlDialect)` |
| **`SchemaMigrationRunner`** | applique les étapes en attente **dans l'ordre**, une fois ; rejeu = no-op ; échec → `SchemaMigrationException` identifiant l'étape ; `targetVersion()` |
| **`SchemaMigrator`** | **catalogue** des migrations V1..V17. Chaque DDL est écrit en SQLite canonique et passé par `dialect.ddl(...)`. `migrate(Connection)` historique (SQLite). |
| **`DatabaseManager`** | sérialise tout sur le thread `RPGQuest-Database` ; `execute` emprunte/rend une connexion au moteur ; `healthCheck()`, `dialect()`, `engineType()`, `expectedSchemaVersion()` |
| **`DatabaseService`** (`PluginService`) | construit le moteur depuis la config puis `DatabaseManager` |

---

## 5. Contrat repositories / services

- un repository prend un `DatabaseManager` et n'expose que des méthodes **asynchrones**
  (`CompletableFuture<T>`), en types 100 % JDK (`UUID`, `Instant`, `Optional`, `byte[]`…), sans
  aucun type Bukkit/Paper — testables en JUnit pur ;
- toute opération passe par `database.execute(connection -> …)` : le `connection` fourni est
  emprunté au moteur pour la durée de l'action puis rendu (jamais fermé par l'appelant) ;
- une opération multi-écritures atomique utilise une transaction JDBC explicite
  (`setAutoCommit(false)` / `commit` / `rollback`) **dans un seul `execute`** (voir
  `WalletRepository`, `ClaimRepository`, `MarketRepository`, `ProgressionRepository`,
  `BackpackRepository`) ;
- **SQL canonique + dialecte (#41)** : le SQL des repositories est écrit en **SQLite canonique**
  (`INSERT OR IGNORE`, `ON CONFLICT … DO UPDATE SET c = excluded.c`) et passé par
  `dialect.rewrite(...)` avant `prepareStatement`. Pour SQLite c'est l'identité ; pour MariaDB
  c'est traduit (`INSERT IGNORE`, `ON DUPLICATE KEY UPDATE`). Aucune conditionnelle de moteur dans
  un repository — juste `dialect = database.dialect()` au constructeur. `SELECT`/`UPDATE`/`DELETE`
  passent inchangés dans les deux moteurs ;
- `Statement.RETURN_GENERATED_KEYS` (`MarketRepository`, `NpcIdRepository`) : identique sur les
  deux moteurs, vérifié par les tests d'intégration MariaDB ;
- les services de gameplay appellent les repositories et **remettent sur le thread principal**
  tout callback qui touche l'API Paper.

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
  - **MariaDB utilise la table portable `rpgquest_schema_migrations`** (`MigrationTableHistory`) :
    `PRAGMA` n'existe pas, et une table donne en prime l'historique horodaté (une ligne
    `version, name, applied_at` par migration appliquée).
  - Aucune librairie de migration externe (Flyway/Liquibase) : le besoin est couvert par ~200
    lignes maison, cohérent avec la règle « pas de dépendance externe si une intégration simple
    suffit ».

---

## 7. Compatibilité SQLite (obligation #40 / #41)

- Le SQL/DDL des migrations V1..V17 est **inchangé** : il est écrit en SQLite canonique et passé
  par `SqliteDialect.ddl(...)` = **identité**. Le SQL des repositories passe par
  `SqliteDialect.rewrite(...)` = **identité**. Rien ne change pour SQLite, à la lettre.
- `SqliteDatabaseEngine` garde la **connexion unique réutilisée** (`borrow()` renvoie toujours la
  même, `release()` est un no-op) — comportement d'avant #40.
- `DatabaseManager(Path)` (constructeur historique) fonctionne toujours et sélectionne SQLite —
  utilisé tel quel par ~15 tests de repositories.
- `SchemaMigrator.migrate(Connection)` (API statique) est conservée — `SchemaMigratorTest`
  (21 tests) passe sans modification.
- `config.yml` : `database.file: data.db` reste accepté comme **alias** de `database.sqlite.file`.
  Le fichier historique VeryGames (`src/main/resources/backup-ftp/config.yml`, 4 clés) démarre
  sans changement.
- `PluginConfig#databaseFile()` est conservé (raccourci vers `database().sqlite().file()`).
- `data.db` n'est **jamais** touché par #41 (aucun test d'intégration MariaDB n'écrit dans un
  fichier SQLite ; la table `rpgquest_schema_migrations` n'est créée que par le moteur MariaDB).

---

## 8. Règles async / concurrence (backend distant)

- **Aucune requête SQL bloquante sur le thread principal** : tout passe par
  `DatabaseManager#execute`, exécuté sur le thread `RPGQuest-Database`. #40 et #41 n'ajoutent
  **aucune** opération DB synchrone.
- **Pas d'ouverture de connexion TCP par requête** : SQLite garde sa connexion unique ; MariaDB
  emprunte/rend une connexion au **pool HikariCP** (`RPGQuest-DB`) par unité de travail. Comme
  l'executor est mono-thread, une seule connexion est active à la fois — l'ordre FIFO dont
  dépendent des repositories comme `WalletRepository` est préservé quel que soit le moteur.
- **Transactions** : explicites là où c'est nécessaire (§5), toujours dans un seul `execute`. Sur
  MariaDB, `release()` fait un `rollback` défensif si une transaction a été laissée ouverte, avant
  de rendre la connexion au pool.
- **Timeouts / indisponibilité** : `pool.connection-timeout-ms` borne l'attente d'une connexion.
  Au **démarrage**, si la base est injoignable, `MySqlDatabaseEngine.start()` échoue vite
  (`initializationFailTimeout` = `connection-timeout-ms`, **une** tentative) — pas de boucle de
  reconnexion. Après démarrage, HikariCP remplace de lui-même les connexions cassées ; une
  connexion fermée « sous le pool » est simplement remplacée à l'emprunt suivant (testé).
  `DatabaseManager#healthCheck()` (non bloquant, ne lève jamais) constate une base momentanément
  indisponible.
- `DatabaseManager#shutdown()` ferme le pool (`HikariDataSource.close()`) puis le thread DB
  (≤ 5 s), uniquement depuis `onDisable()`.

---

## 9. Stratégie de tests

Tous en **JUnit pur** (aucun MockBukkit).

### Tests SQLite / unitaires (toujours exécutés, `./gradlew test`)

| Test | Vérifie |
|---|---|
| `DatabaseSettingsTest` | défauts ; `describe()` sans mot de passe ; mot de passe résolu depuis l'environnement uniquement ; `ssl-mode` normalisé / rejeté ; bornage du pool |
| `SqlDialectTest` | SQL généré par `SqliteDialect` / `MySqlDialect` (upsert, insert-ignore, identité) ; `columnExists` réel sur SQLite |
| `MySqlDialectTranslationTest` | `rewrite(...)` (upsert, insert-ignore, clause sans espace, statements inchangés) ; `ddl(...)` (types, `AUTOINCREMENT`, `BLOB`, InnoDB/utf8mb4_bin, `CREATE INDEX` → `ALTER TABLE ADD INDEX`, `ALTER … INTEGER` → `BIGINT`) ; **balayage des 17 migrations réelles** : aucune `TEXT` en clé, aucun `AUTOINCREMENT`, moteur InnoDB présent |
| `SchemaMigrationRunnerTest` | ordre ; reprise depuis la version enregistrée ; rejeu = no-op ; migration fautive → `SchemaMigrationException` + version non avancée ; versions dupliquées rejetées ; catalogue réel V1..V17 → `CURRENT_VERSION` (sur `MigrationTableHistory` + SQLite) |
| `DatabaseEngineTest` | la factory choisit le bon moteur ; SQLite prête **la même** connexion unique ; MariaDB échoue proprement si le mot de passe (variable d'env) est absent, ou si le serveur est injoignable (borné) ; aucun secret dans `describe()` |
| `DatabaseManagerEngineTest` | constructeur `Path` historique inchangé ; `borrow/release` SQLite ne ferme jamais la connexion entre opérations ; `healthCheck()` faux avant init / vrai après / ne lève jamais ; MariaDB sans mot de passe ou serveur injoignable → `initialize()` échoue sans blocage |
| `SchemaMigratorTest` (existant, 21) | migrations SQLite bout-en-bout — **inchangé** |
| `ConfigValidatorTest` (+10) | défaut SQLite ; sous-section `sqlite:` ; alias `database.file` ; `mysql` sans mot de passe en clair ; `ssl-mode` / type inconnus rejetés ; `pool.minimum-idle` / `maximum-pool-size` / `connection-timeout-ms` validés ; `mariadb` alias |
| `*RepositoryTest` (existants) | tous les repositories métier sur SQLite — **inchangés**, non-régression |

### Tests d'intégration MariaDB (optionnels)

Ne s'exécutent que si `RPGQUEST_DB_HOST` / `_PORT` / `_NAME` / `_USER` / `_PASSWORD` sont dans
l'environnement — sinon **ignorés** (`Assumptions`), jamais en échec. `./gradlew test` passe donc
sur une machine sans MariaDB. Chaque classe **vide** les tables RPGQuest de la base de test avant
de commencer (base de **test** dédiée, jamais de prod). Le mot de passe n'est jamais imprimé.

| Test | Vérifie (contre un vrai serveur MariaDB) |
|---|---|
| `MariaDbSchemaIntegrationTest` | init depuis une base **vide** → 25 tables + `rpgquest_schema_migrations` ; historique V1..V17 dans l'ordre ; re-init = no-op (pas de ligne d'historique en double) ; `health` OK ; tables en `InnoDB` + `utf8mb4` ; clé étrangère `claim_members → claims` créée |
| `MariaDbRepositoryIntegrationTest` | `PlayerProfile` CRUD ; `PlayerVariable` upsert (dont sensibilité à la casse via `utf8mb4_bin`) ; `Wallet` transactions atomiques (crédit / débit refusé si solde insuffisant / `pay`) ; `Market` **generated keys** + BLOB restitué à l'identique ; `NpcId` ids générés séquentiels ; `QuestProgress` upsert + objectifs ; **connexion fermée sous le pool → opération suivante OK** ; base inexistante → `initialize()` échoue proprement |

`./gradlew test` (sans env MariaDB) **et** `./gradlew build` : verts.

---

## 10. Compatibilité MariaDB 10.11 (validée)

Serveur cible et **validé** : `10.11.6-MariaDB` (Debian 12). Driver : **MariaDB Connector/J
3.4.x**. Pool : **HikariCP 5.1.0**. Les deux sont déclarés dans `plugin.yml` `libraries:`
(résolution par le `LibraryLoader` de Paper au démarrage, comme `sqlite-jdbc`) et en
`compileOnly` / `testRuntimeOnly` côté Gradle — **jamais empaquetés** dans le JAR.

| Aspect | Traitement MariaDB |
|---|---|
| Types texte de clé / index / `NOT NULL` | `TEXT` → `VARCHAR(191)` (indexable en `utf8mb4`, largement au-dessus des identifiants RPGQuest ≤ 128) |
| Types texte libres nullables | `TEXT` conservé (`variable_value`, `progress_data`, `context`, `detail`, `reason`, timestamps nullables) |
| Entiers | `INTEGER` → `BIGINT` (SQLite stocke déjà en 64 bits, le code fait des `getLong`) |
| Auto-increment | `INTEGER PRIMARY KEY AUTOINCREMENT` → `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY` |
| Booléens | colonne entière (`allow_public_redstone`) + coercition JDBC `setBoolean`/`getBoolean` (identique SQLite) |
| Timestamps | **aucune colonne native** : RPGQuest stocke des chaînes ISO-8601 (`Instant.toString()`), aucun problème de fuseau |
| BLOB | `BLOB` → `LONGBLOB` (backpacks, `market_listings.item_data`) |
| Clés étrangères | `FOREIGN KEY … ON DELETE CASCADE` conservées (InnoDB), types de colonnes alignés via `VARCHAR(191)` uniforme |
| Index | `CREATE [UNIQUE] INDEX IF NOT EXISTS` → `ALTER TABLE … ADD [UNIQUE] INDEX IF NOT EXISTS` (forme 100 % MariaDB) |
| Collation | `utf8mb4_bin` : comparaisons **binaires**, comme le `BINARY` par défaut de SQLite — parité de comportement sur les clés sensibles à la casse (ids de quêtes, compétences…) |
| UPSERT | `ON CONFLICT (…) DO UPDATE SET c = excluded.c` → `ON DUPLICATE KEY UPDATE c = VALUES(c)` (supporté par MariaDB 10.11 ; la forme `VALUES()` y est toujours valide, non dépréciée contrairement à MySQL 8.0.20+) |
| INSERT-OR-IGNORE | `INSERT OR IGNORE` → `INSERT IGNORE` (même sémantique : ignore le conflit de clé) |
| Generated keys | `Statement.RETURN_GENERATED_KEYS` + `getGeneratedKeys()` — identique, testé |
| Transactions | `setAutoCommit(false)` / `commit` / `rollback` — isolation InnoDB `REPEATABLE READ` (≥ le besoin ; la sérialisation vient du thread unique de `DatabaseManager`) |
| `sql_mode` | `STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION,NO_ZERO_DATE` appliqué par connexion (`connectionInitSql`) |

## 11. Compte MySQL / privilèges (VeryGames)

Utiliser un **compte dédié** à RPGQuest, restreint à sa **seule** base — jamais un compte
administrateur global VeryGames.

- **Runtime** (fonctionnement normal) : `SELECT, INSERT, UPDATE, DELETE`.
- **Installation / migrations de schéma** : en plus `CREATE, ALTER, INDEX, REFERENCES`
  (création des tables, `ALTER TABLE ADD COLUMN/INDEX`, clés étrangères). `DROP` n'est **pas**
  requis par RPGQuest (aucune migration ne supprime de table).
- Le mot de passe vit dans une variable d'environnement du process (`RPGQUEST_DB_PASSWORD` par
  défaut), jamais dans `config.yml`, le dépôt, les logs ou un rapport.

## 12. Diagnostics

`DatabaseManager` expose, sans secret : `engineType()` (`SQLITE` / `MYSQL`), `describeEngine()`
(`sqlite (data.db)` ou `mariadb <user>@<host>:<port>/<db> (pool m..M, ssl=…)`),
`expectedSchemaVersion()`, `healthCheck()` (async, ne lève jamais). Au démarrage, la version du
serveur MariaDB est journalisée (`MariaDB/MySQL serveur : 10.11.6-MariaDB-…`). Ces éléments sont
prêts pour un futur affichage par le Control Panel via l'API RPGQuest (hors périmètre #41).

## 13. Reste pour #42 (migration des données + bascule)

1. **Créer la base RPGQuest de production** sur VeryGames + le compte dédié (§11).
2. **Exporter** l'état de `data.db` (25 tables) puis l'**importer** dans MariaDB — outil de
   migration à écrire (lecture SQLite → écriture MariaDB via les repositories ou en bulk), avec
   contrôle d'intégrité (comptes de lignes, FK, `player_variables` critiques comme `CLAIM_TIER_1`).
3. **Bascule** : arrêt serveur → `database.type: mysql` + secrets → redémarrage → vérifications
   (health, version de schéma, parcours joueur) → conservation de `data.db` en sauvegarde.
4. **Rollback** documenté : re-basculer `database.type: sqlite` (les deux bases coexistent
   pendant la fenêtre de migration).
5. Le `SchemaMigrationRunner` gère déjà l'idempotence : si la base MariaDB est pré-remplie par
   l'outil de migration avec `rpgquest_schema_migrations` à jour, un démarrage ne rejoue rien.
