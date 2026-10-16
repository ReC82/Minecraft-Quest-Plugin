# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-16
* Heure : 04:16
* Sujet : Issue #422 — droits à récompense (entitlements) : spécification + tranche 1 plugin et PlugAdmin
* Statut : DONE (validation en jeu en attente — `PENDING MANUAL VALIDATION`, TC-328)
* Branche Git : `feature/422-reward-entitlements` → PR #453 fusionnée dans `chore/257-ci-github-actions` (`bb56a50`)
* Commit actuel si disponible : `bb56a50` (fusion), dernier commit fonctionnel `fb6a70a`
* Début de la tâche : 2026-10-16 02:54:02
* Fin de la tâche : 2026-10-16 04:16:03
* Durée totale : 01:22:01

## Demande
Session autonome lqorch sur #422 : spécifier les droits à récompense (PENDING/DELIVERED/REVOKED, clé
d'idempotence obligatoire, lecture de l'archive figée des événements #57, réutilisation de la remise
sécurisée #318), publier la spécification sur le ticket, puis livrer une tranche 1 côté plugin,
désactivée par défaut : remise unique même avec inventaire plein, retry, crash ou deux sessions,
statut traçable dans PlugAdmin, aucune écriture SQL par le portail, aucun paiement, nouvelle
migration via `SchemaMigrator`.

## Analyse
- `entitlement.EntitlementService` existait déjà, mais pour un **avantage persistant** (palier de sac,
  boutique, table `player_entitlements`) : un état, pas une remise. Le nouveau concept est donc un
  « droit à récompense » distinct (`entitlement.reward`, table `reward_entitlements`), qui peut
  accorder un avantage via `EntitlementService` (`perk`).
- `StoreDeliveryService` est lié à web-api (sondage + accusé) : son **modèle** est repris (registre
  traité, profil par UUID, échec = reste en attente), pas la classe.
- `ItemDelivery` (#318) fournit le tout ou rien ; les événements #57 décrivent des récompenses
  (`entitlement:` + `label`) sans les livrer et figent leurs résultats dans `stat_period_results`.

## Travail effectué
1. Spécification `docs/REWARD_ENTITLEMENTS.md`, publiée sur le ticket (commentaire du 16/10).
2. Migration **V41** `reward_entitlements` (clé d'idempotence en clé primaire, deux index ; colonnes
   courtes classées pour MariaDB, textes libres nullables).
3. `RewardEntitlementRepository` : `INSERT OR IGNORE`, transitions par `UPDATE` conditionnel
   (`claim` PENDING→DELIVERED, `release` DELIVERED→PENDING, `revoke` PENDING→REVOKED), traces
   (`recordFailure`, `note`), listes (`pendingFor`, `list` avec pseudo).
4. Catalogue `entitlements.yml` (`RewardCatalogLoader`, tout ou rien, `enabled: false` par défaut,
   `backpack` réservé).
5. `EventRewardRecipients` (pur) : rang « compétition » ex-aequo inclus (sens de la règle), seuil,
   objectif commun ; exclusions appliquées ; clé `event:<id>:<critère>:<N>:<récompense>:<uuid>`,
   empreinte SHA-256 si > 160 caractères.
6. `RewardEntitlementService` : relit le catalogue chaque minute, déclare depuis les événements
   archivés (non annulés), remet toutes les 30 s et 3 s après connexion : place vérifiée → prise
   conditionnelle → `ItemDelivery.deliver` → avantage → sinon compensation ; garde mémoire par clé ;
   message « inventaire plein » au plus toutes les 5 min.
7. Actions agent `entitlement.list`, `entitlement.declare` (référence obligatoire →
   `admin:<réf>:<uuid>`), `entitlement.revoke`.
8. PlugAdmin : fiche joueur → Monnaie → « Droits à récompense » (relevé, déclaration depuis le
   catalogue réel, révocation d'un droit en attente ; permissions monnaie, confirmation).
9. Documentation, merge, déploiement JAR + PlugAdmin sur DEV, redémarrage.

Arbitrage assumé (spec §5) : la prise précède la remise — **jamais deux fois** plutôt que « jamais
perdu ». Un arrêt brutal exactement entre la prise et la remise laisse un droit « remis » sans objet,
corrigeable par une nouvelle déclaration.

## Fichiers créés
- `docs/REWARD_ENTITLEMENTS.md`
- `src/main/java/com/lodygames/rpgquest/entitlement/reward/` : `RewardEntitlement`, `RewardEntitlementStatus`,
  `RewardSource`, `RewardDefinition`, `RewardCatalog`, `RewardCatalogLoader`, `EventRewardRecipients`,
  `RewardEntitlementService`
- `src/main/java/com/lodygames/rpgquest/database/RewardEntitlementRepository.java`
- Tests : `RewardEntitlementRepositoryTest`, `RewardEntitlementServiceTest`, `RewardCatalogLoaderTest`,
  `EventRewardRecipientsTest`

## Fichiers modifiés
`SchemaMigrator` (V41, `CURRENT_VERSION` 41), `StatisticsRepository` (`archivedValues`),
`MigrationReport`, `RPGQuestBootstrap`, `AgentActionType`, `AgentActions`, `BukkitAgentActions`,
`AgentActionExecutor` ; panel `AgentActionCatalog`, `AgentPages`, `docs/joueurs-admin.md` ; tests
`MySqlDialectTranslationTest`, `AgentActionExecutorTest`, `PlayersPageTest` ; docs `RPGQUEST_BIBLE.md`,
`current_state.md`, `ARCHITECTURE.md`, `WEB_PORTAL.md`, `MANUAL_TEST_PLAN.md` (TC-328),
`deployment/SERVER_CHANGELOG.md`.

## Base de données / migrations
V41, automatique au démarrage : `CREATE TABLE IF NOT EXISTS reward_entitlements` + index
`idx_reward_entitlements_status`, `idx_reward_entitlements_player`. Aucune donnée existante touchée.

## Configuration / données
Nouveau fichier facultatif `plugins/RPGQuest/entitlements.yml` — **non créé sur DEV** (fonction
inactive). Aucune clé de `config.yml`.

## Tests automatiques
- Locaux ciblés, verts : `RewardEntitlementRepositoryTest` 7, `RewardEntitlementServiceTest` 8
  (MockBukkit + SQLite : remise unique, inventaire plein puis libéré, déconnexion après la prise →
  compensation, révoqué/hors ligne/inconnu jamais remis, catalogue désactivé, déclaration admin
  idempotente, événement archivé → un seul droit même après « redémarrage », événement annulé),
  `RewardCatalogLoaderTest` 3, `EventRewardRecipientsTest` 5, `SchemaMigratorTest`,
  `MySqlDialectTranslationTest`, `database.migration.*`, `web.agent.*` (`AgentActionExecutorTest` 129) ;
  panel `AgentActionCatalogTest` 47, `PlayersPageTest` 15, `EconomyAdminTest`, `authz.*`,
  `DocsPagesTest`, `DiagnosticHelpTest`.
- CI GitHub de la PR #453 (commit `fb6a70a`) : plugin, control-panel, web-api, scripts, artefacts verts.
- `scripts/deploy-verygames.sh` : tests du plugin relancés en local, verts.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — TC-328 (`docs/MANUAL_TEST_PLAN.md`) : nécessite de créer
`entitlements.yml` sur DEV (décision du propriétaire), puis déclaration, inventaire plein, joueur
hors ligne, révocation, double connexion, redémarrage. Aucun test en jeu n'a été fait.

## Résultat attendu
Sans `entitlements.yml` : aucun changement visible en jeu ; la fiche PlugAdmin affiche « Fonction
désactivée sur ce serveur » après un relevé. Avec le fichier activé : récompense remise une fois.

## Reset / retour à l'état initial
Supprimer `entitlements.yml` (ou `enabled: false`). La table V41 peut rester.

## Déploiement VeryGames
### À transférer
JAR `bb56a50` (3 172 227 o) — **fait** à 04:14 (sauvegarde `rpgquest-20261016T021437Z-predeploy.jar`,
3 119 418 o = JAR `10c7785` précédent). PlugAdmin `bb56a50` déployé à 04:15 (`/health` ONLINE ; JAR du
panel contrôlé : aide « Droits à récompense » et action `entitlement.declare` présentes).
### Ne PAS transférer/altérer
Ne pas créer `entitlements.yml` sans décision ; ne pas toucher `data.db`, `config.yml`, `statistics.yml`.
### Redémarrage requis
Oui — fait (RCON, 0 joueur en ligne), serveur revenu en ligne, RPGQuest vert dans `/plugins`.
### Migration automatique
V41 au démarrage (non vérifiée dans `latest.log`, inaccessible par FTP).

## Rollback
`scripts/rollback-verygames.sh` avec la sauvegarde ci-dessus puis redémarrage ; la table V41 reste,
ignorée par l'ancien JAR. PlugAdmin : `scripts/plugadmin/rollback.sh` (release précédente conservée).

## Logs / diagnostic
Console : `Récompenses : … chargée(s) depuis entitlements.yml, activé|désactivé.`, `Récompenses : N
droit(s) déclaré(s) pour l'événement « id ».`, `Récompenses : droit … remis à …`. Journaux de
déploiement : `~/.local/state/rpgquest-logs/deploy-422.log`, `plugadmin-422.log`.

## Documentation mise à jour
Voir « Fichiers modifiés » ; spécification `docs/REWARD_ENTITLEMENTS.md`.

## Limitations / travail restant
- Remise uniquement au joueur connecté (avantage `perk` seul compris).
- Pas de vue PlugAdmin globale multi-joueurs (l'action sait lister sans joueur).
- Pas de déclaration par le portail (après #53, via un canal authentifié vers l'agent).
- Saisons, codes promo, VIP : nouvelles sources à ajouter, sans nouvelle table.
- Une modification ultérieure des exclusions ne révoque pas un droit déjà déclaré.

## Prochaine étape suggérée
Propriétaire : créer un `entitlements.yml` de test sur DEV et dérouler TC-328 ; puis décider d'une vue
globale PlugAdmin et du canal de déclaration portail.
