# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 20:05
* Sujet : Issue #105 — binder les coffres physiques des Hubs aux coffres personnels (phase 2 de #104)
* Statut : DONE (livré, déployé sur DEV ; TC-353 PENDING MANUAL VALIDATION, ticket laissé ouvert)
* Branche Git : `feature/105-coffres-physiques-hubs` (PR #520, fusionnée dans `chore/257-ci-github-actions`, merge `1885989`) ; rapport : `docs/105-rapport-coffres-physiques`
* Commit actuel si disponible : `80b528e` + `64a0b2c` (fonctionnels), merge `1885989`
* Début de la tâche : 2026-10-17 19:25:44
* Fin de la tâche : 2026-10-17 20:05:21
* Durée totale : 00:39:37

## Demande
Session autonome lqorch, ticket #105 : un coffre déclaré dans un Hub ouvre le coffre personnel
(`vault_id`, socle #104) de celui qui clique ; deux joueurs = deux contenus ; même `vault_id` dans deux
Hubs = même contenu ; plusieurs points par `vault_id` ; déplacer/reconfigurer sans migrer le contenu ;
coffres vanilla inchangés ; protections Hub (casse, pistons, explosions) ; pas d'accès via un coffre
copié/posé par un joueur ; builder ≠ propriétaire du contenu. Hors périmètre : #106, #107, guilde,
PlugAdmin.

## Analyse
* Audit : rien de #105 dans le code, les PR, les branches ni les commentaires du ticket (aucun). Le
  socle #104 (`PersonalVaultService#open(Player, VaultId)`) est livré et déployé (V43).
* Architecture Hub : le Hub est un monde entier (`hub.world`) protégé par `HubWorldProtectionListener`
  (casse/pose réservées aux builders, explosions vidées) ; plusieurs « centres » coexistent dans
  `world_hub` (villages) ; les points d'intérêt administrés (bornes de voyage, centres de village) sont
  des **registres en base** gérés par `/rpgadmin`. Choix retenu, cohérent avec eux : table
  `vault_access_points` (position → `vault_id`) plutôt qu'un YAML (données propres au monde du serveur,
  pas un contenu à déployer) ou qu'un PDC de bloc (copiable par pick-block créatif / WorldEdit, ce qui
  violerait « pas d'accès via un coffre copié »).
* Point découvert en relecture : une zone `public-containers: false` (`ZoneProtectionListener`, priorité
  LOW) aurait bloqué l'accès ; le listener passe en priorité LOWEST (un point d'accès n'est pas un
  conteneur public). Les claims sont exclus d'office (liaison refusée dans `claims.world`).
* Aucune décision métier nécessaire (pas de prix, pas de capacité nouvelle).

## Travail effectué
* Migration **V44** `vault_access_points (world, x, y, z, vault_id, created_by, created_at, PK position)`.
* `VaultAccessPointRepository` (findAll, upsert, delete).
* `storage.VaultAccessPointService` : cache mémoire chargé au démarrage (aucun SQL par clic), `bind`
  (coffre double lié sur ses deux moitiés, refus bloc non pris en charge / conteneur non vide / Wild /
  monde des claims / registre non chargé, `REBOUND` sans toucher au contenu), `unbind`, `all`.
* `VaultAccessListener` : clic droit sur un point lié → ouverture vanilla annulée pour tous (builder et
  OP compris), `open(joueur qui clique, vault_id)` ; main secondaire annulée sans ouvrir ; spectateur
  ignoré ; message si `LOAD_FAILED`.
* `VaultAccessProtectionListener` : casse refusée à tous (message « délie-le d'abord »), explosions,
  pistons, feu, entités, et coffre accolé (coffre double non lié) refusés.
* `storage.VaultAdminCommand`, branche `/rpgadmin vault bind [vault_id] | unbind [x y z] | info | list`
  (ombrelle `rpgquest.admin.world`), complétion ; `plugin.yml` usage.
* Outil de migration MariaDB : table classée « Coffres personnels ».
* Docs : `PERSONAL_VAULTS.md` (section points d'accès), bible §13, `current_state.md`,
  `docs-site/admin-testing.html`, `SERVER_CHANGELOG.md`, TC-353 dans `MANUAL_TEST_PLAN.md`.
* Registre `docs/SUIVI_VALIDATIONS_DEV.md` : report de l'édition propriétaire de `main` (`45af0ab`,
  ligne #475 / TC-351) fusionnée avec la version de la branche d'intégration (preuves conservées),
  ligne #105 ajoutée, entrée « derniers correctifs » et dépendance n° 12.

## Fichiers créés
* `src/main/java/com/lodygames/rpgquest/database/VaultAccessPointRepository.java`
* `src/main/java/com/lodygames/rpgquest/storage/{VaultAccessPointService,VaultAccessListener,VaultAccessProtectionListener,VaultAdminCommand}.java`
* `src/test/java/com/lodygames/rpgquest/database/VaultAccessPointRepositoryTest.java`
* `src/test/java/com/lodygames/rpgquest/storage/VaultAccessPointServiceTest.java`
* ce rapport

## Fichiers modifiés
* `SchemaMigrator.java` (V44), `MigrationReport.java`, `RPGQuestBootstrap.java`, `RpgAdminCommand.java`,
  `plugin.yml`, `MySqlDialectTranslationTest.java` (3 colonnes texte court)
* `docs/PERSONAL_VAULTS.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs-site/admin-testing.html`,
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md`, `docs/SUIVI_VALIDATIONS_DEV.md`,
  `docs/claude-reports/README.md`

## Base de données / migrations
V43 → **V44** : création de `vault_access_points` (ajout de table seulement, idempotent). Vérifié sur
DEV après redémarrage (copie téléchargée en lecture seule) : `user_version = 44`, table vide,
`personal_vaults` vide. Aucune donnée existante modifiée ; aucune migration SQLite → MariaDB.

## Configuration / données
Aucune clé de configuration. Aucun coffre lié sur DEV par Claude.

## Tests automatiques
* Local (`RPGQUEST_TEST_MAX_HEAP=768m`) : `storage.*` 22 (dont 10 nouveaux), `VaultAccessPointRepositoryTest` 2,
  `SchemaMigratorTest` 43, `MySqlDialectTranslationTest` 15, `database.migration.*` 26, `admin.*` 38 —
  0 échec (après classement des 3 colonnes dans `MySqlDialectTranslationTest`). `./gradlew build -x test` vert.
* CI GitHub PR #520 (deux commits) : Tests plugin, control-panel, web-api, Scripts, Artefacts, CI OK — verts ;
  CI du merge `1885989` verte (vérifiée par le script de déploiement).
* Couverture : coffre déclaré → coffre de celui qui clique ; deux joueurs simultanés, inventaires
  distincts ; même `vault_id` dans deux mondes « Hub A / Hub B » ; déplacement (bind nouveau + unbind
  ancien) et reliaison vers `vault_02` puis retour sans perte ; coffre non déclaré non intercepté ; main
  secondaire et refus antérieur ; survie au redémarrage ; refus (bloc, Wild, non vide, déjà lié) ;
  casse par un OP refusée, explosion filtrée, coffre accolé refusé ; moitié d'un coffre double.

## Tests manuels à effectuer
TC-353 (PENDING MANUAL VALIDATION) — rend aussi TC-352 (#104) testable. Aucun test Minecraft n'a été
fait par Claude. Points non couverts automatiquement : rendu réel de l'ouverture, pistons réels,
pick-block créatif, comportement avec un vrai client multi-joueur.

## Résultat attendu
Un administrateur lie des coffres vides du Hub ; chaque joueur y retrouve son propre coffre `vault_01`,
identique depuis tous les points liés ; les coffres liés sont incassables ; le reste est vanilla.

## Reset / retour à l'état initial
`/rpgadmin vault unbind` sur chaque coffre de test (le contenu des comptes reste dans leur `vault_01`).

## Déploiement VeryGames
Effectué le 2026-10-17 entre 19:55 et 20:04 (heure locale), DEV uniquement :
* `RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --wait-ci` depuis le worktree propre
  `/srv/rpgquest/wt-orch` au commit `1885989` : CI verte, `assemble`, JAR 3 618 361 o, SHA-256
  `2a5b2d0f…` ; JAR distant retéléchargé : SHA identique. `DEPLOY_EXIT=0`.
* Sauvegarde du JAR précédent : `rpgquest-20261017T180243Z-predeploy.jar` (3 589 698 o = `4f02612`, #104).
* Sauvegarde de `data.db` avant redémarrage : `~/.local/share/rpgquest/verygames-backups/data-20261017T1803Z-pre-v44/data.db`
  (1 802 240 o, `user_version` 43, `integrity_check` ok).
* Redémarrage `scripts/verygames-restart.sh` (0 joueur connecté), retour ONLINE à 20:04 ; RPGQuest
  activé (vert dans `/plugins`) ; schéma V44 vérifié.
* PlugAdmin non redéployé (aucun changement dans `control-panel/`).
* À ne pas altérer : `data.db`, `config.yml`, contenus. Rien à transférer d'autre.
* Rollback : `scripts/rollback-verygames.sh --latest` puis redémarrage ; l'ancien JAR ignore la table
  V44, les coffres liés redeviennent vanilla, le contenu des joueurs reste dans `personal_vaults`.
* Journal : `/srv/rpgquest/build-logs/deploy-105-20261017T195501.log`.

## Problèmes connus / limites
* Si le chargement du registre échoue au démarrage (erreur base), les points se comportent comme des
  coffres vanilla vides jusqu'au redémarrage suivant (erreur en console).
* Un hopper peut remplir le contenu *physique* d'un bloc lié (jamais montré, sans lien avec les joueurs).
* Hérité de #104 : un crash brutal pendant qu'un coffre est ouvert perd les mouvements depuis l'ouverture.

## Suite
#106 (capacité progressive), #107 (salles de préparation : emplacement de la salle des coffres).
Ticket #105 laissé ouvert (TC-353).
