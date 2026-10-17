# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 20:47
* Sujet : Issue #106 — déblocage progressif de capacité des coffres personnels (phase 3 de #104/#105)
* Statut : DONE (livré, déployé sur DEV ; TC-354 PENDING MANUAL VALIDATION, ticket laissé ouvert)
* Branche Git : `feature/106-capacite-coffres-personnels` (PR #522, fusionnée dans `chore/257-ci-github-actions`, merge `475b8c3`) ; rapport : `docs/106-rapport-capacite-coffres`
* Commit actuel si disponible : `feat` + `docs` de la PR #522, merge `475b8c3`
* Début de la tâche : 2026-10-17 20:06:25
* Fin de la tâche : 2026-10-17 20:49:00
* Durée totale : 00:42:35

## Demande
Session autonome lqorch, ticket #106 : capacité de départ configurable, coffres verrouillés refusés
proprement, déblocage persistant lié à l'UUID et visible dans tous les Hubs, réutilisation de la
progression existante, aucun contenu supprimé lors d'un verrouillage, architecture ouverte à plusieurs
modes d'obtention et à l'extension en cases. Hors périmètre : VIP/argent réel, guilde, échanges,
équilibrage définitif.

## Analyse
* Audit : aucun commentaire sur le ticket, aucune branche ni PR #106 ; rien de livré. #104 (V43) et #105
  (V44, `VaultAccessPointService`) sont fusionnés et déployés.
* Progression existante : la table `player_variables` porte déjà les déblocages (`CLAIM_TIER_1`), posés
  par la récompense de quête `VARIABLE`, l'action de dialogue `SET_VARIABLE`, `/rpgadmin player
  variable set` et l'outil variable de PlugAdmin, et lus par les conditions de dialogue. Choix retenu :
  **réutiliser ces variables** plutôt qu'un nouveau type de récompense `VAULT_UNLOCK` ou une table
  dédiée (aucune migration, toutes les sources actuelles et futures savent déjà poser une variable).
* Aucun arbitrage métier bloquant : nombre de coffres et taille livrés comme valeurs de démonstration,
  configurables.

## Travail effectué
* `config.PersonalVaultConfig` + section `personal-vaults:` (`starter-vaults: [vault_01]`,
  `default-slots: 27`) validée au chargement (identifiants `[a-z0-9_]{1,32}`, taille multiple de 9 entre
  9 et 54) ; complétée automatiquement sur le serveur par `ConfigFileCompleter`.
* `storage.VaultEntitlements` : accès = coffre de départ **ou** `VAULT_UNLOCK_<ID>` = `true` ; taille =
  max(`default-slots`, `VAULT_SLOTS_<ID>` valide). Relu en base à chaque ouverture (effet immédiat dans
  tous les Hubs, persistant).
* `PersonalVaultService.open` : droits résolus d'abord ; `OpenOutcome.LOCKED` sans lire ni écrire
  `personal_vaults` ; taille selon les droits ; `sizeFor` garde affichées les rangées encore occupées
  au-delà de la capacité (jamais de troncature) et masque les rangées vides.
* `VaultAccessListener` : message générique « Ce coffre est encore verrouillé. Il se débloquera au fil de
  ta progression dans LodyQuests. » (sans révéler de quête).
* `VariableTypes` : `VAULT_UNLOCK_*` booléen, `VAULT_SLOTS_*` ∈ {9, 18, 27, 36, 45, 54} (refus d'une
  valeur mal typée par l'agent PlugAdmin) ; suggestions de complétion `/rpgadmin player variable`.
* Docs : `PERSONAL_VAULTS.md` (section « Déblocage et capacité »), bible (« Capacité progressive des
  coffres personnels »), `current_state.md`, `docs-site/admin-testing.html`, `SERVER_CHANGELOG.md`,
  TC-354 dans `MANUAL_TEST_PLAN.md`.
* Registre `docs/SUIVI_VALIDATIONS_DEV.md` : `main` ne contenait rien de plus récent (dernière édition
  `45af0ab` déjà reportée par #105) ; ligne 24 (#106 / TC-354) ajoutée, entrée « derniers correctifs »,
  dépendance n° 12 mise à jour (décision : nombre/taille des coffres et sources de déblocage).

## Fichiers créés
* `src/main/java/com/lodygames/rpgquest/config/PersonalVaultConfig.java`
* `src/main/java/com/lodygames/rpgquest/storage/VaultEntitlements.java`
* `src/test/java/com/lodygames/rpgquest/config/PersonalVaultConfigTest.java`
* ce rapport

## Fichiers modifiés
* `ConfigValidator.java`, `PluginConfig.java`, `config.yml`, `PersonalVaultService.java`,
  `VaultAccessListener.java`, `RPGQuestBootstrap.java`, `VariableTypes.java`, `RpgAdminCommand.java`
* Tests : `PersonalVaultServiceTest.java`, `VaultAccessPointServiceTest.java`, `VariableTypesTest.java`
* `docs/PERSONAL_VAULTS.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
  `docs-site/admin-testing.html`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md`,
  `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune migration (schéma toujours V44). Les déblocages utilisent `player_variables` (V1). Aucune donnée
joueur modifiée par Claude ; aucune migration SQLite → MariaDB.

## Configuration / données
Nouvelle section `personal-vaults` ajoutée automatiquement au `config.yml` DEV au démarrage (vérifié par
téléchargement en lecture seule : `starter-vaults: [vault_01]`, `default-slots: 27`). `config.yml` n'a pas
été téléversé. Aucune variable `VAULT_*` posée sur DEV, aucun coffre lié par Claude.

## Tests automatiques
* Local (`RPGQUEST_TEST_MAX_HEAP=768m`) : `storage.*`, `config.*`, `VariableTypesTest` (123 tests),
  puis `admin.*` + `storage.*` — 0 échec ; `./gradlew build -x test` vert.
* CI GitHub PR #522 : Tests plugin, control-panel, web-api, Scripts, Artefacts, CI OK — verts ; CI du merge
  `475b8c3` verte (attendue par `deploy-verygames.sh --wait-ci`).
* Nouveaux cas : coffre hors liste de départ verrouillé (aucun inventaire, aucune ligne créée) puis
  ouvert par sa variable ; déblocage propre à un joueur et relu par une nouvelle instance (redémarrage) ;
  reverrouillage sans perte puis contenu restauré ; capacité de départ configurable (9) ; extension à 54,
  réduction à 27 avec rangée occupée conservée (45) puis retour à 27 une fois vidée ; valeur de taille
  invalide ignorée ; point d'accès vers un coffre verrouillé → message, rien d'ouvert ; validation config ;
  types de variables.

## Tests manuels à effectuer
TC-354 (PENDING MANUAL VALIDATION) — voir `docs/MANUAL_TEST_PLAN.md`. Aucun test Minecraft n'a été fait
par Claude.

## Résultat attendu
Aucun changement pour `vault_01` (27 cases). Un coffre lié à `vault_02`+ refuse l'ouverture avec un
message générique jusqu'au déblocage ; `VAULT_SLOTS_<ID>` agrandit un coffre.

## Reset / retour à l'état initial
Remettre `VAULT_UNLOCK_<ID>` à `false` / `VAULT_SLOTS_<ID>` à `27` pour le compte de test (le contenu est
conservé). Retour arrière du code : voir rollback.

## Déploiement VeryGames
Effectué le 2026-10-17 vers 20:26–20:45 (heure locale), DEV uniquement :
* `RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --wait-ci` depuis le worktree propre
  `/srv/rpgquest/wt-orch` au commit `475b8c3` (une première exécution arrêtée pendant l'attente de CI,
  avant tout transfert, pour relancer avec un délai suffisant) : CI verte, JAR 3 626 062 o, SHA-256
  `e6d50d65…` ; JAR distant retéléchargé : SHA identique.
* Sauvegarde du JAR précédent : `rpgquest-20261017T184448Z-predeploy.jar` (3 618 361 o, `2a5b2d0f…` =
  `1885989`, #105).
* Pas de migration → pas de copie de `data.db` nécessaire.
* Redémarrage `scripts/verygames-restart.sh` (0 joueur connecté), retour ONLINE ; section
  `personal-vaults` présente dans le `config.yml` DEV.
* PlugAdmin non redéployé (aucun changement dans `control-panel/`).
* À ne pas altérer : `data.db`, `config.yml` (complété par le plugin), contenus.
* Rollback : `scripts/rollback-verygames.sh --latest` puis redémarrage ; l'ancien JAR ignore la section
  `personal-vaults` et les variables `VAULT_*` (tous les coffres ouverts, 27 cases), aucun contenu touché.
* Journal : `/srv/rpgquest/build-logs/deploy-106-20261017T202650.log`.

## Problèmes connus / limites
* Aucune quête ni récompense ne pose encore `VAULT_UNLOCK_*` : à décider par le propriétaire (contenu).
* PlugAdmin vérifie localement les types de `CLAIM_TIER_*`/`STARTER_KIT_TIER` mais pas encore ceux de
  `VAULT_*` : une valeur invalide est refusée par le plugin à l'exécution (message d'échec de l'action),
  pas dès le formulaire.
* `/rpgadmin player variable set` n'applique pas `VariableTypes` (comportement existant) : une taille
  invalide y est acceptée mais n'accorde rien.
* Un coffre ouvert au moment d'un retrait de droit reste ouvert jusqu'à sa fermeture.
* `/rpgadmin player resetnew|resetfull` efface les déblocages (variables) mais pas le contenu des coffres.
* Pas de visuel « coffre verrouillé » (non requis par le ticket).

## Suite
Propriétaire : TC-353 puis TC-354 ; décider nombre/taille des coffres et sources de déblocage. Ensuite
#107 (salles de préparation). Ticket #106 laissé ouvert.
