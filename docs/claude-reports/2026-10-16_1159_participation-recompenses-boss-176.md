# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-16
* Heure : 11:59
* Sujet : Issue #176 — participation et récompenses des boss communautaires (éligibilité, attribution fiable)
* Statut : DONE (livré et déployé sur DEV ; validation en jeu et deux interprétations métier à confirmer)
* Branche Git : `feature/176-boss-participation-rewards` (PR #458, fusionnée dans `chore/257-ci-github-actions`) ; rapport : `docs/176-rapport`
* Commit actuel si disponible : `bc12e70` (merge PR #458)
* Début de la tâche : 2026-10-16 10:49:14
* Fin de la tâche : 2026-10-16 11:59:55
* Durée totale : 01:10:41

## Demande
Session autonome lancée par le superviseur `lqorch` sur le ticket #176 (suite de l'EPIC #169, après #175) :
participation aux boss et récompenses — éligibilité figée à la mort (coup effectif, vivant, même monde et
même biome que le boss), attribution exactement une fois malgré retry/reconnexion/redémarrage, inventaire
plein sans perte, message clair, traçabilité admin, séparation récompenses communautaires / quêtes.
Consignes : réutiliser #422 si pertinent, pas de migration, signaler les décisions métier, ne pas clôturer.

## Analyse
- #175 (déployé à 10:44) avait laissé un point d'extension `CommunityBossHooks` vide et une PDC
  `community-boss-instance` sur chaque boss communautaire.
- #422 (droits à récompense) fournit exactement la remise exigée : déclaration idempotente par clé,
  prise conditionnelle `PENDING → DELIVERED`, remise tout ou rien via `ItemDelivery` (#318), attente si
  inventaire plein, remise à la connexion, table `reward_entitlements` (V41) — `source` est un texte libre,
  donc une nouvelle source `BOSS` ne demande **aucune migration**.
- Le ticket demande de clarifier deux points avec le porteur de projet. Aucune réponse sur #176/#169.
  Choix : appliquer la lecture **littérale** du texte validé, la documenter et la signaler (pas
  d'invention silencieuse), plutôt que bloquer le lot.
- Boss de quête (#171) non implémentés : seuls les boss communautaires sont concernés.

## Travail effectué
1. **Modèle** : `participation-rewards` (≤ 5 clés `[a-z0-9_.-]{1,64}` du catalogue `entitlements.yml`)
   sur `SpecialMobDefinition`, BOSS seulement, dédoublonné ; parseur + rendu YAML ; constructeur de
   compatibilité #175.
2. **`BossParticipation`** (règles pures) : attaquant = joueur ou tireur d'un projectile ; verdict
   `ELIGIBLE / OFFLINE / DEAD / OTHER_WORLD / OTHER_BIOME` ; déclarations `source BOSS`, clé
   `boss:<uuid entité>:<récompense>:<uuid joueur>`, référence `<profil>@<uuid entité>` ; codec PDC.
3. **`CommunityBossRewards`** (hook + listener) : `EntityDamageByEntityEvent` MONITOR `ignoreCancelled`,
   dégâts finaux > 0, boss marqué → UUID inscrit en PDC `community-boss-participants` (≤ 100). À la mort
   (`onDefeated`, désormais avec l'entité) : position et biome réels du boss, verdict par participant,
   message d'explication aux exclus connectés, déclaration via `RewardEntitlementService.declareAll`
   (nouvelle méthode, refusée si désactivé), remise immédiate tentée (`deliverSoon`), message
   « Participation reconnue ». Échec base : 3 essais à 5 s (idempotents) puis erreur avec la liste.
   Historique mémoire des 10 dernières victoires.
4. **Agent** : `mob.definition.create/update` acceptent `participation_rewards` (absent = conservé,
   comme la table de drops ; vide = retiré ; revalidé) ; `mob.definition` et `boss.community.status`
   renvoient `participationRewards` ; `boss.community.status` renvoie `recentDefeats`.
5. **PlugAdmin** : champ « Récompenses de participation » dans l'éditeur Boss ; section « Victoires
   récentes et participants » (verdicts, récompenses, statut de déclaration) ; origine « Boss
   communautaire » dans les droits à récompense de la fiche joueur ; texte d'intro mis à jour.
6. **Docs** et tests (ci-dessous). CI verte, fusion, déploiement JAR + PlugAdmin sur DEV.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/mob/community/BossParticipation.java`
- `src/main/java/com/lodygames/rpgquest/mob/community/CommunityBossRewards.java`
- `src/test/java/com/lodygames/rpgquest/mob/community/BossParticipationTest.java`
- `src/test/java/com/lodygames/rpgquest/mob/community/CommunityBossRewardsTest.java`
- ce rapport

## Fichiers modifiés
- Plugin : `mob/model/SpecialMobDefinition.java`, `mob/SpecialMobDefinitionParser.java`,
  `mob/SpecialMobDefinitionYaml.java`, `mob/community/CommunityBossHooks.java`,
  `mob/community/CommunityBossService.java`, `entitlement/reward/RewardSource.java`,
  `entitlement/reward/RewardEntitlementService.java`, `bootstrap/RPGQuestBootstrap.java`,
  `web/agent/AgentActions.java`, `web/agent/AgentActionExecutor.java`, `web/agent/BukkitAgentActions.java`
- Tests plugin : `SpecialMobDefinitionParserTest`, `SpecialMobDefinitionYamlTest`,
  `BukkitAgentActionsMobTest`, `AgentActionExecutorTest`, `StubAgentActions`, `RewardEntitlementServiceTest`
- PlugAdmin : `panel/agent/AgentActionCatalog.java`, `panel/web/AgentPages.java`,
  `AgentActionCatalogTest`, `CommunityBossPageTest`
- Docs : `docs/RPGQUEST_BIBLE.md` (§11), `SPECIAL_MOB_FORMAT.md`, `docs/REWARD_ENTITLEMENTS.md` (§4, §6.3),
  `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-330), `docs/deployment/SERVER_CHANGELOG.md`,
  `docs/claude-reports/README.md`

## Base de données / migrations
Aucune migration. Réutilise `reward_entitlements` (V41) avec la nouvelle valeur `source = BOSS`.

## Configuration / données
- Profil mob : clé facultative `participation-rewards: [clé, …]` (BOSS). Absente = comportement #175.
- Prérequis fonctionnel : `plugins/RPGQuest/entitlements.yml` avec `enabled: true` et les clés utilisées.
  **Absent sur DEV** : rien ne sera déclaré tant que le propriétaire ne le crée pas (exemple dans TC-330).
- Nouvelle PDC d'entité `rpgquest:community-boss-participants` (gérée par le plugin).

## Tests automatiques
- Ciblés en local (`RPGQUEST_TEST_MAX_HEAP=768m`) : `com.lodygames.rpgquest.mob.*` (dont
  `BossParticipationTest` 5, `CommunityBossRewardsTest` 9, `CommunityBossServiceTest` 15, parser 16,
  YAML 7), `BukkitAgentActionsMobTest` 5, `AgentActionExecutorTest` 130, `entitlement.*`
  (`RewardEntitlementServiceTest` 9) ; panel `CommunityBossPageTest` 5, `AgentActionCatalogTest` 51 —
  tous verts. `./gradlew build -x test` vert.
- CI GitHub (suite complète) sur PR #458 : Tests plugin, Tests control-panel, Tests web-api, Scripts — verts.
- `deploy-verygames.sh` a relancé `test` + `build` localement avant transfert : verts.
- Cas couverts : un coup suffit, dernier coup non requis, projectile crédité au tireur, coup annulé et
  0 dégât ignorés, mob ordinaire ignoré, mort / hors ligne / autre monde / autre biome exclus, biome de
  la mort et non d'apparition, plusieurs participants, isolement entre deux boss, retry idempotent,
  clé inconnue du catalogue déclarée quand même, fonction désactivée, profil sans récompense,
  conservation du champ quand un ancien panel l'omet, déclaration BOSS idempotente en base.
- Limites MockBukkit : pas de vrai combat (événements simulés), pas de remise réelle de bout en bout dans
  ces tests (couverte par les tests #422).

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — `docs/MANUAL_TEST_PLAN.md` **TC-330** (nécessite `entitlements.yml` activé sur
DEV) : édition du champ, deux joueurs (un coup / coup final à l'arc), exclusions biome et mort, inventaire
plein, déconnexion avant la mort, origine dans la fiche joueur, redémarrage avec droit en attente.

## Résultat attendu
À la mort d'un boss communautaire dont le profil a des récompenses de participation, chaque joueur qui
l'a touché et qui est vivant dans le même monde et le même biome reçoit chaque récompense une fois ; les
autres participants connectés savent pourquoi ils n'ont rien ; l'admin voit les verdicts dans PlugAdmin.

## Reset / retour à l'état initial
Vider « Récompenses de participation » sur le profil (ou retirer la clé YAML) ; un droit en attente se
révoque depuis la fiche joueur → Monnaie → Droits à récompense.

## Déploiement VeryGames
Effectué le 2026-10-16 ~11:58 : JAR `bc12e70` (3 250 590 o, SHA-256
`a56c4c77968cd0b51e60a6d2f94f8dae7c9dc1218b6a359ca1a7fce84ba5c0e7`) par `scripts/deploy-verygames.sh -y`
(« JAR en ligne : 3250590 octets (== local) », « Déploiement terminé ») ; sauvegarde du JAR #175
`rpgquest-20261016T095803Z-predeploy.jar` (3 225 009 o) ; redémarrage `scripts/verygames-restart.sh`
(seul LoDyMcFly connecté) → ONLINE ; PlugAdmin `scripts/plugadmin/deploy.sh` → `panel ONLINE`.
Vérifié : `boss.community.status` sur DEV → SUCCESS avec `recentDefeats` et `participationRewards` ;
classe `AgentPages` servie contient « Victoires récentes et participants ».
### À transférer
Le JAR seul (fait). Aucun fichier de données.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `mobs/*.yml` du serveur, `state/community-bosses.yml`. Ne pas créer
`entitlements.yml` sans décision du propriétaire sur les récompenses.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` puis redémarrage ; PlugAdmin : `scripts/plugadmin/rollback.sh`
(release précédente sous `/opt/plugadmin/releases/`). L'ancien JAR ignore `participation-rewards` et la
PDC des participants ; les droits `BOSS` déjà déclarés restent remis normalement.

## Logs / diagnostic
- `Boss communautaire « <profil> » (<uuid>) vaincu dans <monde> / <biome> : N participant(s), M éligible(s) [noms] — <statut>.`
- `  participant <nom> (<uuid>) exclu : <raison>.`
- `… M participant(s) éligible(s) mais entitlements.yml est désactivé — aucune récompense déclarée.`
- `… récompenses NON déclarées après 3 essais. Droits à déclarer à la main : [...]`

## Documentation mise à jour
Bible §11 (« Participation et récompenses des boss communautaires »), `SPECIAL_MOB_FORMAT.md`,
`REWARD_ENTITLEMENTS.md`, `current_state.md`, TC-330, `SERVER_CHANGELOG.md`, index des rapports.

## Limitations / travail restant
- **Décisions à confirmer par le propriétaire** (documentées Bible §11) :
  1. « dans le biome » = même clé de biome que le bloc du boss à sa mort, **sans rayon** (un joueur à
     3 blocs mais de l'autre côté d'une frontière de biome est exclu ; un joueur parti dans une autre
     zone du même biome reste éligible) ;
  2. « encore vivant » = vivant **à l'instant** de la mort (mort puis revenu = éligible, coups conservés).
- XP et argent comme récompenses directes : non (le catalogue #422 remet objets + avantages) ; tables
  aléatoires : #177.
- Les apparitions de **test** donnent aussi les récompenses (nécessaire pour tester en jeu).
- Victoires récentes en mémoire seulement (10, depuis le démarrage) ; la trace durable est le droit.
- Crash serveur entre la mort et l'écriture en base : participation perdue pour cette victoire (fenêtre
  d'un aller-retour base ; même arbitrage que #422).
- Pas de liste déroulante des clés du catalogue dans l'éditeur (saisie libre validée).

## Prochaine étape suggérée
Le propriétaire crée `entitlements.yml` sur DEV (exemple TC-330), valide TC-330, et tranche les deux
interprétations ci-dessus ; ensuite #177 (tables de récompenses aléatoires) ou #171 (boss de quête).
