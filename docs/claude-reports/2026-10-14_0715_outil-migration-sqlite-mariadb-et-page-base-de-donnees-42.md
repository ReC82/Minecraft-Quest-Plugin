# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 07:15
* Sujet : Issue #42 — outil de migration contrôlée SQLite → MariaDB (validation, rollback) + page PlugAdmin « Base de données » — **préparation uniquement**
* Statut : DONE (préparation livrée ; bascule réelle volontairement non faite, consigne propriétaire)
* Branche Git : `feature/42-migration-sqlite-mariadb` (PR #355, fusionnée `31f3890`) puis `feature/42-plugadmin-db-target` (PR #356, fusionnée `747ae18`) → `chore/257-ci-github-actions`
* Commit actuel si disponible : `747ae18`
* Début de la tâche : 2026-10-14 05:47:46
* Fin de la tâche : 2026-10-14 07:13:28
* Durée totale : 01:25:42

## Demande
Ticket #42, traité par une session autonome `lqorch`. La consigne propriétaire du 13/10/2026 prime sur l'ancien runbook :
- **préparer uniquement** : auditer le schéma, développer et tester un outil de migration avec dry-run sur copies isolées, rédiger la procédure (contrôles, checksums, estimation d'interruption, rollback, go/no-go) ;
- dans PlugAdmin, une interface administrateur pour les paramètres SQL :
  - mot de passe masqué, jamais réaffiché, conservé chiffré ;
  - test de connexion sans mutation ;
  - backend actif affiché en permanence ;
  - assistant en étapes dont le bouton de bascule est désactivé.

**Interdits** : toute bascule ou migration réelle, la modification de la SQLite active ou de `database.type`, et l'exécution de #58.

## Analyse
- **Existant.** Le backend MariaDB (#40/#41) fonctionne : HikariCP, `MySqlDialect`, catalogue `SchemaMigrator.ALL` en V34. Aucun outil de migration de données n'existait.
- **Audit sur une copie** de la sauvegarde DEV `20261013-predeploy-325` (schéma V33), portée en V34 sur une seconde copie. La sauvegarde elle-même n'a jamais été ouverte en écriture. Résultats :
  - 39 tables et 3 702 lignes ;
  - intégrité OK, aucun orphelin ;
  - types réels conformes ;
  - 8 tables `AUTOINCREMENT` ;
  - horodatages en ISO texte, UUID en texte.
- **Constat bloquant découvert.** `MySqlDialect` traduit **tout** `TEXT NOT NULL` d'un `CREATE TABLE` en `VARCHAR(191)`, y compris des textes libres. `building_sites.description` fait 490 caractères en DEV : MariaDB en mode strict refuserait la ligne, et le plugin lui-même aurait le même défaut s'il tournait sur MariaDB. Ce n'est **pas corrigé ici**, car c'est une décision de schéma MariaDB : le ticket **#354** a été ouvert avec la liste des colonnes à risque. L'outil le détecte avant toute écriture.
- **Deux autres pièges trouvés en essai réel :**
  - le pilote MariaDB renvoie un `java.sql.Blob` pour `LONGBLOB`, ce qui faussait l'empreinte → corrigé ;
  - dans un JAR unique, les fichiers `META-INF/services/java.sql.Driver` des deux pilotes s'écrasent → corrigé par une fusion générée.
- **Décisions d'architecture :**
  - outil **hors serveur** (pas une commande Minecraft), qui réutilise le même catalogue, le même dialecte et le même moteur que le plugin ;
  - pour le panel, une exception documentée : pilote MariaDB ajouté **uniquement** pour le test de connexion ;
  - mot de passe chiffré avec une clé dérivée de `RPGQUEST_PANEL_SECRET`, qui est déjà obligatoire et n'est jamais stockée avec la base.

## Travail effectué
### Partie A — outil (`com.lodygames.rpgquest.database.migration`, PR #355)
- Classes : `SqliteSource` (lecture seule : `open_mode=1` + `query_only`), `SqliteMigrationTool`, `LogicalChecksum`, `MigrationReport` (texte + JSON), `MigrationFinding`, `MigrationMode`, `MigrationCli`.
- **Modes** : `audit` (lecture seule partout), `dry-run` (mode par défaut, transaction toujours annulée), `migrate --commit`.
- **Contrôles sur la source** :
  - SHA-256 avant et après ;
  - journal `-journal`/`-wal` résiduel ;
  - version de schéma égale à celle de l'outil ;
  - `integrity_check` et `foreign_key_check` ;
  - types réels (`typeof`) par colonne ;
  - UUID (simple avertissement).
- **Contrôles sur la cible**, tous avant la moindre écriture : la cible est refusée si elle contient une ligne, si elle contient une table étrangère à RPGQuest, ou si son schéma est plus récent. L'outil ne fait jamais `DROP`, `DELETE` ni `TRUNCATE`.
- **Déroulé** :
  1. schéma créé par `SchemaMigrator.ALL`, avec contrôle des longueurs `VARCHAR(n)` et des collisions PAD SPACE ;
  2. copie dans l'ordre topologique des clés étrangères, en une seule transaction, par lots de 500 ;
  3. validation par comptage et empreinte logique de chaque table (somme modulo 2²⁵⁶ de SHA-256 de lignes canonisées, indépendante du moteur et de l'ordre), plus recherche d'orphelins ;
  4. commit seulement si aucun constat n'est critique, puis alignement des séquences `AUTOINCREMENT` et recomptage.
- **Dialecte** : nouvelles méthodes `SqlDialect#nextAutoIncrement` et `#raiseAutoIncrement` (SQLite : `sqlite_sequence` ; MariaDB : `AUTO_INCREMENT`). Un identifiant supprimé n'est jamais réattribué.
- **Rapport** : ne contient ni hôte, ni compte, ni secret ; les messages d'erreur du pilote sont expurgés.
- **Fichier cible** : un `.properties` sans mot de passe (`password-env`) ; une clé `password` est refusée.
- **Gradle** : `./gradlew dbMigrationToolJar` produit `build/tools/rpgquest-db-migration.jar`. Il embarque ses pilotes, les services JDBC fusionnés et `WaypointNameCatalog` avec sa ressource. Le JAR du plugin n'embarque toujours aucun pilote.
- **Runbook** `docs/deployment/MIGRATION_SQLITE_MARIADB.md` : audit, outil, procédure de répétition, runbook futur avec go/no-go et estimation d'interruption, rollback (avec sa limite : les écritures faites dans MariaDB après bascule sont perdues), checklist manuelle VeryGames.

### Partie B — PlugAdmin et heartbeat (PR #356)
- **Plugin** : le heartbeat porte `database: {engine, schema_version}`, via `HealthSource` → `BukkitHealthSource` (`DatabaseManager`).
- **Panel, paquet `panel.dbtarget`** :
  - `SecretBox` : AES-256-GCM, IV aléatoire, AAD = usage, clé HMAC-SHA256(secret maître, usage) ;
  - `DbTargetStore` : table `db_migration_target` dans `control-panel.db` ;
  - `DbTargetSettings` : validation qui empêche toute injection dans l'URL JDBC ;
  - `DbConnectionTester`, `MariaDbConnectionTester` : session en lecture seule, délais de 5 s / 8 s, lecture des seules métadonnées ;
  - `DbTargetPages`.
- **Page `/database`**, permission `DATABASE_CONFIGURE` (propriétaire), liens dans la navigation et sur l'accueil :
  - backend actif réel lu dans le heartbeat ;
  - formulaire de la cible : mot de passe jamais réaffiché, champ vide = conserver, effacement séparé avec case de confirmation ;
  - « Enregistrer et tester » : tout changement de cible efface le dernier résultat ; la page affiche « Connexion testée ≠ serveur migré » ;
  - assistant en 7 étapes, dont la bascule est **désactivée**. Toute sous-route `/database/*` répond 404.
- **Audit** des actions enregistrer / tester / effacer, sans hôte ni secret.
- **Fiche d'aide** `docs/base-de-donnees.md`, listée dans le centre de documentation.
- **Contrôle visuel local** (Playwright, 1280 et 390 px) : une instance locale jetable du panel s'est connectée à une vraie MariaDB 10.11 jetable. Le test réussit et le mot de passe est absent du HTML. Une numérotation en double a été repérée puis corrigée.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/database/migration/`
  - `MigrationCli.java`, `MigrationFinding.java`, `MigrationMode.java`, `MigrationReport.java`
  - `LogicalChecksum.java`, `SourceTable.java`, `SqliteSource.java`, `SqliteMigrationTool.java`
- `src/test/java/com/lodygames/rpgquest/database/migration/`
  - `MigrationFixtures.java`, `SqliteMigrationToolTest.java`, `LogicalChecksumTest.java`, `MigrationCliTest.java`
- `src/test/java/com/lodygames/rpgquest/database/MariaDbMigrationIntegrationTest.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/HeartbeatPayloadTest.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/dbtarget/`
  - `SecretBox.java`, `DbTargetSettings.java`, `DbTargetStore.java`
  - `DbConnectionTester.java`, `MariaDbConnectionTester.java`, `DbTargetPages.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/dbtarget/DbTargetStoreTest.java`, `MariaDbConnectionTesterTest.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/DatabaseTargetPageTest.java`
- `control-panel/src/main/resources/docs/base-de-donnees.md`
- `docs/deployment/MIGRATION_SQLITE_MARIADB.md`

## Fichiers modifiés
- **Persistance** : `SqlDialect.java`, `SqliteDialect.java`, `MySqlDialect.java` (méthodes de séquence)
- **Build plugin** : `build.gradle.kts` (configuration `dbMigrationTool`, tâches `dbMigrationToolServices` / `dbMigrationToolJar`)
- **Plugin** : `HealthSource.java`, `BukkitHealthSource.java`, `HeartbeatPayload.java`, `RPGQuestBootstrap.java`
- **Build panel** : `control-panel/build.gradle.kts` (pilote MariaDB)
- **Panel** :
  - `Permission.java` (`DATABASE_CONFIGURE`), `Layout.java`, `HomePages.java`
  - `PanelApp.java` (route `/database`, `activeBackend()`)
  - `docs/_index.txt`
- **Documentation** : `docs/PERSISTENCE.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/deployment/VERYGAMES.md`, `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations
- **Aucune migration de schéma du plugin** : on reste en V34, et `data.db` n'est pas touché.
- **Panel** : nouvelle table `db_migration_target`, créée au démarrage avec `CREATE TABLE IF NOT EXISTS`.
- **Aucune migration de données réelle.** Les essais ont porté uniquement sur des copies et sur des MariaDB **jetables locales** (10.11.14, en espace utilisateur sur l'hôte AWS, port 33306 en loopback, détruites après usage). Ni la MariaDB VeryGames ni la production n'ont été contactées.

## Configuration / données
- Aucune nouvelle clé dans `config.yml`, et `database.type` est inchangé (sqlite).
- Le panel chiffre avec `RPGQUEST_PANEL_SECRET`, déjà présent dans `/etc/plugadmin/plugadmin.env`.
- Aucun secret dans Git, dans ce rapport ou dans les rapports de l'outil.

## Tests automatiques
- **Plugin** :
  - `SqliteMigrationToolTest` 16/16, `LogicalChecksumTest` 4/4, `MigrationCliTest` 6/6, `SqlDialectTest` 6/6 ;
  - `MariaDbMigrationIntegrationTest` 3/3 **contre une vraie MariaDB 10.11 locale jetable** : données relues par les repositories du plugin, séquence respectée, seconde exécution refusée, texte trop long détecté avant écriture ;
  - `HeartbeatPayloadTest` 2/2, `AgentLoopTest` 7/7.
- **Panel** :
  - `DatabaseTargetPageTest` 9/9, `DbTargetStoreTest` 6/6 ;
  - `MariaDbConnectionTesterTest` 2/2, dont le test réel contre la MariaDB jetable ;
  - `RolePermissionMatrixTest` 16/16, `EffectivePermissionsTest` 9/9, `DocsPagesTest`, `DiagnosticHelpTest` : verts.
- **CI GitHub (suite complète, plugin + panel + web-api)** : verte sur #355 et sur #356.
- **Essais de l'outil sur une copie DEV** :

  | Essai | Résultat |
  |---|---|
  | `audit` | SUCCESS |
  | `dry-run` → SQLite | SUCCESS (39/39 tables identiques) |
  | `dry-run` → MariaDB | **FAILED, attendu** (#354) |
  | Copie jetable tronquée : `dry-run` | SUCCESS |
  | Copie jetable tronquée : `migrate --commit` | SUCCESS |
  | Seconde exécution | refusée (cible non vide) |

  Les compteurs `AUTO_INCREMENT` étaient identiques aux séquences source.

## Tests manuels à effectuer
- `PENDING MANUAL VALIDATION` — **PlugAdmin DEV > Base de données** :
  1. le backend affiche « SQLite (data.db) », schéma V34 ;
  2. enregistrer une cible avec les vrais identifiants MariaDB VeryGames (propriétaire uniquement) puis tester ;
  3. vérifier que le mot de passe n'est jamais réaffiché, et qu'un champ vide le conserve ;
  4. effacer le mot de passe : refusé sans la case, accepté avec ;
  5. vérifier que la bascule est grisée.
- Aucun test Minecraft n'a été effectué ni n'est requis : aucun changement de gameplay.

## Résultat attendu
L'outil de migration est disponible, testé et documenté, et la page PlugAdmin prépare et teste une cible. La bascule reste impossible depuis le panel tant que le propriétaire ne l'autorise pas et que #354 n'est pas corrigé.

## Reset / retour à l'état initial
Effacer le mot de passe depuis `/database` ; la table `db_migration_target` peut rester, elle est inerte. Aucune donnée du jeu n'a été modifiée.

## Déploiement VeryGames
**Effectué sur DEV le 14/10/2026 vers 07:13** (aucune production touchée).

- **Plugin** :
  - `scripts/deploy-verygames.sh -y` depuis `747ae18` (sur-ensemble vérifié du JAR déployé `de12017`) ;
  - le script a refait le test et le build, puis sauvegardé l'ancien JAR (`rpgquest-20261014T051147Z-predeploy.jar`, `e058368c…`) ;
  - JAR en ligne : 2 805 604 octets. Le JAR distant a été retéléchargé : SHA-256 `732d31f3…`, **identique** au local ;
  - redémarrage par `verygames-restart.sh` à 0 joueur, serveur de nouveau ONLINE ;
  - le heartbeat reçu ensuite contient `"database":{"engine":"sqlite","schema_version":34}`.
- **Panel** :
  - `scripts/plugadmin/deploy.sh --wait-ci` (CI verte) ; l'ancienne version est sauvegardée sous `/opt/plugadmin/releases/20261014-071309` ;
  - `/health` OK ; `/database` répond 303 → `/login`, donc la route est enregistrée ;
  - le JAR contient `dbtarget/*` et la fiche `base-de-donnees.md` ; `mariadb-java-client` est présent ;
  - table `db_migration_target` créée, vide.
  - Le pilote MariaDB tire des dépendances transitives inutilisées : waffle-jna, jna, caffeine, slf4j.

### À transférer
Rien de plus. Les déploiements sont faits ; aucun `--also`.

### Ne PAS transférer/altérer
`config.yml` (en particulier `database.type`), `data.db`, la MariaDB VeryGames. **Aucune migration ni bascule.**

### Redémarrage requis
Oui pour le JAR : fait.

### Migration automatique
Aucune : schéma plugin V34 inchangé. Le panel crée sa table au démarrage.

## Rollback
- **Plugin** : remettre le JAR sauvegardé par le script de déploiement (`~/.local/share/rpgquest/verygames-backups/rpgquest-20261014T051147Z-predeploy.jar`, SHA-256 `e058368c…`, 2 747 128 octets = la version #352), puis redémarrer.
- **Panel** : `/opt/plugadmin/releases/<horodatage>/`, puis redémarrer le service.
- **Bascule MariaDB future** : voir le runbook, §5.

## Logs / diagnostic
- Outil : rapports `migration-reports/*.txt|json`.
- Panel : journal d'audit (`database.target.*`).
- Plugin : log de démarrage `Base de données RPGQuest : moteur …`.

## Documentation mise à jour
- `docs/deployment/MIGRATION_SQLITE_MARIADB.md` (nouveau)
- `docs/PERSISTENCE.md` §13, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`
- `docs/deployment/VERYGAMES.md`, `docs/deployment/SERVER_CHANGELOG.md`
- Fiche panel `base-de-donnees.md`

## Limitations / travail restant
- **#354 (bloquant, NO-GO)** : schéma MariaDB des textes libres.
- La durée réelle de migration depuis AWS vers VeryGames n'a pas été mesurée, puisqu'aucune connexion à VeryGames n'est autorisée. En local : 2 à 4 s pour 3 702 lignes.
- Les étapes « Simulation » et « Comparaisons » de l'assistant restent hors panel : le panel n'exécute pas l'outil et n'importe pas son rapport.
- Changer `RPGQUEST_PANEL_SECRET` rend le mot de passe enregistré illisible : il faut le ressaisir (le panel le signale).
- La bascule réelle et #58 restent en attente d'une consigne explicite du propriétaire.

## Prochaine étape suggérée
1. Le propriétaire teste la page `/database` avec la base VeryGames.
2. Traiter #354, puis rejouer `dry-run` sur une copie DEV fraîche (SUCCESS attendu sans troncature).
3. Ne décider de la bascule qu'ensuite, selon le runbook.
