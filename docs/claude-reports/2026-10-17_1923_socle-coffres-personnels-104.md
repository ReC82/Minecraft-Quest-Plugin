# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 19:23
* Sujet : Issue #104 — socle de coffres personnels persistants partagés entre les Hubs (phase 1)
* Statut : DONE (socle livré et déployé ; rien d'ouvrable en jeu avant #105)
* Branche Git : `feature/104-coffres-personnels` (PR #518, fusionnée dans `chore/257-ci-github-actions`, merge `4f02612`) ; rapport : `docs/104-rapport-coffres-personnels`
* Commit actuel si disponible : `d8696e5` (fonctionnel), merge `4f02612`
* Début de la tâche : 2026-10-17 18:50:49
* Fin de la tâche : 2026-10-17 19:24:32
* Durée totale : 00:33:43

## Demande
Session autonome lqorch, ticket #104 : créer le socle métier et persistant des coffres personnels
(`UUID joueur + vault_id → inventaire`), identique depuis tous les Hubs, sans coordonnée, sans accès
depuis le Wild, sans toucher à l'Ender Chest vanilla, compatible MariaDB ; tests d'isolation et de
persistance. Hors périmètre : salle physique et binding des blocs (#105), progression (#106),
PlugAdmin.

## Analyse
* Audit : aucune trace de coffres personnels dans le code, les rapports, les PR ni les commentaires
  du ticket (aucun) — rien n'était livré.
* Existant réutilisable : `backpack.ItemArraySerializer` (format `ItemStack[]` versionné, méta + PDC
  conservées) et le modèle de `BackpackService` (session unique, sauvegarde à la fermeture /
  déconnexion / arrêt), `DatabaseManager` (exécuteur JDBC unique, FIFO), `SchemaMigrator`,
  `MySqlDialect` (`BLOB` → `LONGBLOB`, clés en `VARCHAR(191)`), outil de migration #42.
* Aucune décision métier n'était nécessaire : capacité 27 cases (comme un coffre simple) notée comme
  valeur du MVP, la progression relevant de #106.

## Travail effectué
* Migration **V43** `personal_vaults (player_uuid, vault_id, schema_version, contents BLOB,
  updated_at, PK (player_uuid, vault_id))`, sans clé étrangère vers `player_profiles`.
* `PersonalVaultRepository` : `find`, `save` (upsert, un statement), `vaultIds`.
* Package `storage` : `VaultId` (`[a-z0-9_]{1,32}`, `vault_01` par défaut), `PersonalVaultService`
  (`open(Player, VaultId)`, `vaultIds(UUID)`), `PersonalVaultHolder`, `PersonalVaultListener`.
  Garanties : un seul inventaire vivant par (joueur, coffre) — y compris deux ouvertures concurrentes
  ou la réouverture d'un coffre déjà affiché (qui, sans garde, aurait mis fin à la session et perdu
  la sauvegarde suivante) ; sauvegarde à la fermeture, à la déconnexion (sans double écriture) et à
  l'arrêt ; passage direct d'un coffre à l'autre = sauvegarde du premier ; instantané gardé en
  mémoire jusqu'à confirmation de la base et présenté à la réouverture ; contenu illisible →
  `LOAD_FAILED`, ligne jamais écrasée ; jamais de troncature d'un contenu plus grand.
* Câblage dans `RPGQuestBootstrap` (`personalVaultService()`), **aucune commande**.
* Outil de migration MariaDB : table classée « Coffres personnels ».
* Docs : `docs/PERSONAL_VAULTS.md` (nouveau), `INDEX.md`, `current_state.md`, bible §13,
  `PERSISTENCE.md`, `SERVER_CHANGELOG.md`, TC-352 dans `MANUAL_TEST_PLAN.md`.
* Registre `docs/SUIVI_VALIDATIONS_DEV.md` : report des modifications du propriétaire faites sur
  `main` (`76a6461`, #15 : ligne 21 et décision « Quêtes périodiques ») fusionnées avec la ligne
  existante sans rien perdre, puis ajout de la ligne #104.

## Fichiers créés
* `src/main/java/com/lodygames/rpgquest/database/PersonalVaultRepository.java`
* `src/main/java/com/lodygames/rpgquest/storage/{VaultId,PersonalVaultService,PersonalVaultHolder,PersonalVaultListener}.java`
* `src/test/java/com/lodygames/rpgquest/database/PersonalVaultRepositoryTest.java`
* `src/test/java/com/lodygames/rpgquest/storage/PersonalVaultServiceTest.java`
* `docs/PERSONAL_VAULTS.md`, ce rapport

## Fichiers modifiés
* `SchemaMigrator.java` (V43), `MigrationReport.java`, `RPGQuestBootstrap.java`,
  `MySqlDialectTranslationTest.java` (`personal_vaults.updated_at` classé texte court)
* `docs/INDEX.md`, `docs/current_state.md`, `docs/RPGQUEST_BIBLE.md`, `docs/PERSISTENCE.md`,
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md`, `docs/SUIVI_VALIDATIONS_DEV.md`,
  `docs/claude-reports/README.md`

## Base de données / migrations
V42 → **V43** : création de `personal_vaults` (ajout de table seulement, idempotent). Vérifié sur DEV
après redémarrage (copie téléchargée en lecture seule) : `user_version = 43`, table présente, 0 ligne.
Aucune donnée existante modifiée, aucune migration SQLite → MariaDB effectuée.

## Configuration / données
Aucune clé de configuration, aucun contenu, aucune donnée joueur.

## Tests automatiques
* Local (`RPGQUEST_TEST_MAX_HEAP=768m`) : `storage.*` (12), `PersonalVaultRepositoryTest` (3),
  `SchemaMigratorTest` (43), `MySqlDialectTranslationTest` (15), `database.migration.*` (26) — 0 échec.
  `./gradlew build -x test` vert.
* CI GitHub PR #518 : Tests plugin, control-panel, web-api, Scripts, Artefacts — verts ; CI du merge
  `4f02612` verte (vérifiée par le script de déploiement).
* Couverture : deux UUID sur `vault_01` indépendants ; fermeture/réouverture ; objets complexes (nom,
  lore, enchantement, PDC) case par case ; plusieurs `vault_id` ; redémarrage (nouveau service) ;
  arrêt avec coffre ouvert ; déconnexion ; réouverture d'un coffre affiché ; ouvertures concurrentes ;
  contenu illisible non écrasé ; validation des identifiants ; survie à la réouverture de la base.

## Tests manuels à effectuer
TC-352 (PENDING MANUAL VALIDATION) : démarrage DEV sans erreur en V43, Ender Chest vanilla et
`/backpack` inchangés. Aucun test Minecraft n'a été fait par Claude. L'ouverture d'un coffre
personnel en jeu ne sera testable qu'avec #105.

## Résultat attendu
Aucun changement visible en jeu ; le socle est prêt pour les coffres physiques des Hubs (#105).

## Reset / retour à l'état initial
Rien à réinitialiser (table vide). Retour arrière : JAR précédent (voir rollback).

## Déploiement VeryGames
Effectué le 2026-10-17 vers 19:14–19:25 (heure locale), DEV uniquement :
* `RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --wait-ci` depuis le worktree propre
  `/srv/rpgquest/wt-orch` au commit `4f02612` : CI verte, `assemble`, JAR 3 589 698 o, SHA-256
  `1778709b…` ; JAR distant retéléchargé : SHA identique. `DEPLOY_EXIT=0`.
* Sauvegarde du JAR précédent : `rpgquest-20261017T172156Z-predeploy.jar` (3 574 572 o, `b23b262d…`
  = `a9d6934`, #516).
* Sauvegarde de `data.db` avant redémarrage (le script ne la fait pas) :
  `~/.local/share/rpgquest/verygames-backups/data-20261017T1721Z-pre-v43/data.db` (1 802 240 o,
  `user_version` 42, `integrity_check` ok).
* Redémarrage `scripts/verygames-restart.sh` (0 joueur connecté), retour ONLINE ; schéma V43 vérifié.
* PlugAdmin non redéployé (aucun changement dans `control-panel/`).
* À ne pas altérer : `data.db`, `config.yml`, contenus. Pas de configuration à transférer.
* Rollback : `scripts/rollback-verygames.sh --latest` puis redémarrage ; l'ancien JAR ignore la table
  V43 (base toujours lisible).
* Journal : `/srv/rpgquest/build-logs/deploy-104-20261017T191331.log`.

## Problèmes connus / limites
* Comme pour les backpacks : un crash brutal du serveur pendant qu'un coffre est ouvert perd les
  mouvements faits depuis l'ouverture (pas de sauvegarde continue). À reconsidérer avec #105 si besoin.
* Capacité fixe 27 cases (valeur du MVP) ; progression = #106.

## Suite
#105 (coffres physiques des Hubs → `personalVaultService().open(player, VaultId.DEFAULT)`), puis #106,
#107. Ticket #104 laissé ouvert (TC-352).
