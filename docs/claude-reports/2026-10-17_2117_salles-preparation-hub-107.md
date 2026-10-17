# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 21:17
* Sujet : Issue #107 — formaliser les salles de préparation du Hub (coffres personnels et craft)
* Statut : DONE (livré, déployé sur DEV ; TC-355 PENDING MANUAL VALIDATION, ticket laissé ouvert)
* Branche Git : `feature/107-salles-preparation-hub` (PR #524, fusionnée dans `chore/257-ci-github-actions`, merge `a95cf2f`) ; rapport : `docs/107-rapport-salles-preparation`
* Commit actuel si disponible : `bde1e61` (feat) + `9f47a0f` (docs), merge `a95cf2f`
* Début de la tâche : 2026-10-17 20:48:28
* Fin de la tâche : 2026-10-17 21:17:29
* Durée totale : 00:29:01

## Demande
Session autonome lqorch, ticket #107 : définir la salle des coffres et la salle de craft comme services
standards d'un Hub (multi-Hub, sans bâtiment imposé), établis vanilla utilisables par un joueur normal mais
protégés contre la casse/le déplacement, salle des coffres appuyée sur #104/#105 sans second stockage,
craft vanilla distinct de l'artisan RPG (#18), tests si du code change, documentation.

## Analyse
* Audit : aucun commentaire, branche ni PR #107 ; #104/#105/#106 fusionnés et déployés.
* Établis : aucun listener n'annule le clic droit sur un `CRAFTING_TABLE` dans le Hub ; le drapeau de zone
  `public-containers` ne vise que les `Container` (un établi n'en est pas un). Casse/pose déjà refusées par
  `HubWorldProtectionListener`, explosions déjà neutralisées.
* Écart trouvé : dans le monde Hub (hors zone cuboïde), rien n'empêchait un joueur standard de **vider un
  seau de lave** ou d'**allumer un feu**, ni le feu de **brûler** un établi ou le décor en bois. Corrigé.
* Pistons : non bloqués à l'échelle du monde (un joueur standard ne peut pas en poser ; bloquer tous les
  pistons casserait les mécanismes des constructeurs). Documenté comme limite.
* Aucun arbitrage métier bloquant : pas d'économie, pas de donnée, pas de configuration.

## Travail effectué
* `HubWorldProtectionListener` : `PlayerBucketEmptyEvent` et `PlayerBucketFillEvent` refusés sans droit de
  construction du Hub (traire une vache — résultat `MILK_BUCKET` — reste permis) ; `BlockIgniteEvent`
  refusé sauf s'il est allumé par un constructeur du Hub ; `BlockBurnEvent` toujours annulé dans le Hub.
  Même droit que la casse/pose (`RpgPermissions.canBuildInHub`), jamais le mode bypass.
* Nouveau `docs/HUB_SERVICES.md` : services logiques d'un Hub, salle des coffres (#104/#105/#106,
  signalétique), salle de craft vanilla, tableau des règles réellement appliquées, craft vanilla ≠ artisan
  (#18), checklist de création d'un nouveau Hub.
* Docs : bible (section « Salles de préparation du Hub »), `docs-site/hub-safe-zone.html` (lignes seaux,
  feu, établis ; ligne casse/pose mise à jour avec les droits de constructeur), `current_state.md`,
  `INDEX.md`, TC-355 dans `MANUAL_TEST_PLAN.md`, `SERVER_CHANGELOG.md` (entrée + déploiement effectué).
* Registre `docs/SUIVI_VALIDATIONS_DEV.md` : `main` ne contenait rien de plus récent (dernière édition
  `45af0ab` déjà reportée) ; ligne 25 (#107 / TC-355), entrée « derniers correctifs », dépendance n° 12
  mise à jour (décision : emplacement et signalétique de la vraie salle).

## Fichiers créés
* `docs/HUB_SERVICES.md`
* ce rapport

## Fichiers modifiés
* `src/main/java/com/lodygames/rpgquest/hub/HubWorldProtectionListener.java`
* `src/test/java/com/lodygames/rpgquest/hub/HubWorldProtectionListenerTest.java`
* `docs/RPGQUEST_BIBLE.md`, `docs-site/hub-safe-zone.html`, `docs/current_state.md`, `docs/INDEX.md`,
  `docs/MANUAL_TEST_PLAN.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`,
  `docs/claude-reports/README.md`

## Base de données / migrations
Aucune migration (schéma toujours V44). Aucune donnée joueur lue ni modifiée.

## Configuration / données
Aucune configuration. Aucun bloc posé, aucun coffre lié, aucune salle construite sur DEV par Claude.

## Tests automatiques
* Local (`RPGQUEST_TEST_MAX_HEAP=768m`) : `HubWorldProtectionListenerTest` — 38 tests, 0 échec (10 nouveaux).
* Nouveaux cas : établi utilisable sous une zone aux drapeaux par défaut (témoin : un coffre public y reste
  fermé) ; établi incassable ; seau vidé refusé / autorisé au constructeur ; seau rempli sur l'eau refusé,
  traite permise ; seaux d'un autre monde intacts ; combustion d'un établi annulée ; propagation et briquet
  refusés ; feu décoratif d'un constructeur permis ; feu d'un autre monde intact.
* CI GitHub PR #524 : Tests plugin, control-panel, web-api, Scripts, Artefacts, CI OK — verts ; CI du
  merge `a95cf2f` verte (vérifiée par `deploy-verygames.sh --wait-ci`).

## Tests manuels à effectuer
TC-355 (PENDING MANUAL VALIDATION) — voir `docs/MANUAL_TEST_PLAN.md`. Aucun test Minecraft n'a été fait
par Claude.

## Résultat attendu
Dans le Hub, un joueur ouvre son coffre personnel, utilise un établi vanilla, ne peut ni casser l'établi
ni y mettre le feu, ni vider/remplir un seau sur le décor, puis part par le portail Wild.

## Reset / retour à l'état initial
Rien à réinitialiser côté données. Coffres/établis posés pour TC-355 : `/rpgadmin vault unbind` puis casser
avec un compte constructeur.

## Déploiement VeryGames
Effectué le 2026-10-17 vers 21:12–21:16 (heure locale), DEV uniquement :
* `RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --wait-ci` depuis le worktree propre
  `/srv/rpgquest/wt-orch` au commit `a95cf2f` : `DEPLOY_EXIT 0`, JAR 3 626 532 o, SHA-256 `b6d97b7b…` ;
  JAR distant retéléchargé : SHA identique, `HubWorldProtectionListener` présent.
* Sauvegarde du JAR précédent : `rpgquest-20261017T191542Z-predeploy.jar` (3 626 062 o, `e6d50d65…` =
  `475b8c3`, #106).
* Pas de migration → pas de copie de `data.db`.
* Redémarrage `scripts/verygames-restart.sh` (0 joueur connecté), retour ONLINE.
* PlugAdmin non redéployé (aucun changement dans `control-panel/`).
* À ne pas altérer : `data.db`, `config.yml`, mondes, contenus.
* Rollback : `scripts/rollback-verygames.sh --latest` puis redémarrage ; seules les nouvelles protections
  seaux/feu disparaissent.
* Journal : `/srv/rpgquest/build-logs/deploy-107-*.log`.

## Problèmes connus / limites
* Pistons non bloqués dans le monde Hub (ne pas placer d'établi devant un piston).
* Aucun « service de Hub » n'est enregistré dans le plugin : c'est une convention documentée (blocs
  ordinaires + règles du monde Hub + points de coffre #105), volontairement sans configuration.
* La salle réelle (emplacement, bâtiment, panneaux) reste à poser par le propriétaire / un constructeur.

## Suite
Propriétaire : TC-353 → TC-354 → TC-355 ; décider l'emplacement et la signalétique de la salle des coffres
et de craft. Ticket #107 laissé ouvert.
