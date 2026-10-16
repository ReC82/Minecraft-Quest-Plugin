# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-16
* Heure : 13:20
* Sujet : Issue #177 — tables de récompenses aléatoires partagées quêtes / boss (EPIC #169)
* Statut : WAITING_OWNER (tranche 1 livrée et déployée sur DEV ; validation en jeu TC-331 et tranche « édition PlugAdmin » à décider)
* Branche Git : `feature/177-reward-tables` (PR #460, fusionnée dans `chore/257-ci-github-actions`) ; rapport : `docs/177-rapport`
* Commit actuel si disponible : `adecf3d` (merge PR #460)
* Début de la tâche : 2026-10-16 12:01:56
* Fin de la tâche : 2026-10-16 13:20:03
* Durée totale : 01:18:07

## Demande
Session autonome `lqorch` sur #177 : tables de récompenses aléatoires réutilisables par les quêtes et
les boss, deux modes de tirage explicites, chances validées et expliquées, tirage figé et persisté
avant livraison (jamais relancé ni doublé), inventaire plein sans perte, traçabilité table/version,
administration graphique dans PlugAdmin. Consignes : réutiliser l'existant, pas de migration,
signaler les décisions métier, ne pas clôturer.

## Analyse
- #422 (droits à récompense) fournit déjà la remise exigée (idempotence par clé, prise conditionnelle,
  remise tout ou rien #318, attente si inventaire plein, remise à la connexion) et un catalogue de
  récompenses (`entitlements.yml`). #176 l'utilise pour les boss.
- Choix structurant (signalé) : une table **ne redéfinit pas** d'objets ; chaque entrée **désigne une
  récompense du catalogue**. Les tables vivent dans la section `tables:` du même fichier (validation des
  références au chargement, même rechargement à chaud, même interrupteur). Chaque gain = un droit à
  récompense ; `source` est un texte libre → nouvelle source `QUEST` sans migration.
- « Figer avant livraison » : tirage **déterministe** (SHA-256 d'une graine stable contenant un UUID
  aléatoire : jeton de complétion de quête, ou entité du boss + joueur) + clé d'idempotence par gain.
  Pour les quêtes, gains écrits **dans la transaction de complétion** existante (comme les dettes
  d'argent et les cœurs).
- Boss : plutôt qu'un nouveau champ de profil (qui aurait traversé 6 signatures agent), une entrée
  `table:<id>` dans `participation-rewards` (#176) — combinaison garanties + aléatoires naturelle.

## Travail effectué
1. **Modèle / chargeur** : `RewardTable` (mode `INDEPENDENT`/`SINGLE`, entrées `id`/`reward`/chance en
   centièmes de %, `version()` = empreinte), `RewardCatalogLoader` lit `tables:` (mode obligatoire, somme
   ≤ 100 % en `single`, chance > 0, ≤ 100, 2 décimales sans arrondi, ids stables uniques, récompense
   existante, 100 tables × 20 entrées) ; fichier entier refusé à la moindre erreur.
2. **Tirage** : `RewardTableDraw` (pur, déterministe, explication des chances réelles), `wins()` → déclarations.
3. **Objets du catalogue** : `enchantments` (livre enchanté = **stocké**, jamais appliqué à l'équipement)
   et `potion` ; construction déplacée dans `RewardItems` (bootstrap allégé).
4. **Quête** : récompense `TABLE` (`table: <id>`) — parseur, modèle scellé, moteur (`drawTables`, écrits par
   `QuestProgressRepository#completeQuest` surcharge 6 paramètres → `RewardEntitlementRepository.insertDeclaration`
   dans la même transaction), annonce `quest.reward-line-table-win/-none/-unavailable`, `deliverSoon`,
   journal « Tirage au sort », agent `RewardSummary` `TABLE`, content pack (`table:`).
5. **Boss** : `table:<id>` accepté (modèle, parseur, agent, panel) ; `CommunityBossRewards` tire une fois par
   participant éligible, messages individuels (gains ou « pas de gain cette fois »), `Defeat.draws`.
6. **Agent / PlugAdmin** : `boss.community.status` renvoie `rewardTables` (mode, chances, version, total,
   explication, récompense inconnue) et `draws` ; section « Tables de récompenses aléatoires (quêtes et
   boss) » en lecture et ligne « Tirages » des victoires ; descripteur de quête `TABLE` « Tirage au sort »,
   validation, texte de récompense ; aide du champ boss.
7. Docs, tests, CI, fusion, déploiement JAR + PlugAdmin sur DEV.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/entitlement/reward/{RewardTable,RewardTableDraw,RewardTables,RewardItems}.java`
- `src/main/java/com/lodygames/rpgquest/quest/model/TableReward.java`
- Tests : `RewardTableDrawTest`, `RewardItemsTest`, `QuestTableRewardIntegrationTest`
- ce rapport

## Fichiers modifiés
- Plugin : `RewardCatalog`, `RewardCatalogLoader`, `RewardDefinition`, `RewardEntitlementService`, `RewardSource`,
  `RewardEntitlementRepository`, `QuestProgressRepository`, `QuestProgressEngine`, `QuestDefinitionParser`,
  `QuestReward`, `RewardType`, `QuestJournalService`, `SpecialMobDefinition`, `SpecialMobDefinitionParser`,
  `CommunityBossRewards`, `AgentActions`, `AgentActionExecutor`, `BukkitAgentActions`, `ContentPackMapper`,
  `QuestPackEntry`, `ContentPackSerializer`, `RPGQuestBootstrap`, `messages.yml`
- Tests plugin : `RewardCatalogLoaderTest`, `CommunityBossRewardsTest`, `SpecialMobDefinitionParserTest`, `QuestDefinitionParserTest`
- PlugAdmin : `AgentActionCatalog`, `AgentPages`, `Descriptors`, `QuestValidator`, `RewardText` ; tests
  `CommunityBossPageTest`, `AgentActionCatalogTest`, `EditorDescriptorsTest`
- Docs : `docs/RPGQUEST_BIBLE.md` (§1, quêtes, §11), `docs-site/quests.html`, `docs/REWARD_ENTITLEMENTS.md` (§4, §6.4, §10),
  `SPECIAL_MOB_FORMAT.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-331),
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune migration. `reward_entitlements` (V41) reçoit la nouvelle valeur `source = QUEST` ; les gains de quête
sont insérés dans la transaction de complétion.

## Configuration / données
- `entitlements.yml` : section facultative `tables:` ; `enchantments` / `potion` sur les lignes `items`.
- Quête : `- type: TABLE` + `table: <id>`. Profil boss : `participation-rewards: [..., "table:<id>"]`.
- `messages.yml` : 3 clés ajoutées automatiquement (fusion des clés manquantes).
- **`entitlements.yml` absent sur DEV** : aucune table chargée tant que le propriétaire ne le crée pas (exemple TC-331).

## Tests automatiques
- Ciblés en local (`RPGQUEST_TEST_MAX_HEAP=768m`) : `entitlement.*` (RewardTableDrawTest 7, RewardItemsTest 4,
  RewardCatalogLoaderTest 7, RewardEntitlementServiceTest…), `mob.*` (CommunityBossRewardsTest 12),
  QuestTableRewardIntegrationTest 4, QuestDefinitionParserTest, QuestHeartRewardIntegrationTest,
  AgentActionExecutorTest, BukkitAgentActionsMobTest, `content.*` ; panel CommunityBossPageTest,
  AgentActionCatalogTest, `panel.content.*` — tous verts. `./gradlew build -x test` vert.
- CI GitHub PR #460 (suite complète) : Tests plugin, Tests control-panel, Scripts, Artefacts — verts.
- `deploy-verygames.sh` a relancé `test` + `build` avant transfert : verts.
- Cas couverts : déterminisme, `single` ≤ 1 gain avec chances réelles (25/25/50 sur 20 000 tirages, jamais
  renormalisé), `independent` plusieurs/aucun, 100 % / table désactivée, clés ≤ 160, version, explication,
  validation (mode absent, somme > 100 %, récompense inconnue, 12.345, 0, doublon, id invalide), livre
  enchanté stocké, potion, quête : tirage écrit avec la complétion (source/clé/libellé), répétable = tirage
  par cycle, deux gains indépendants, table inconnue annoncée sans droit ; boss : un tirage par participant,
  clés personnelles, garanties + tirage, nouvel essai identique, table inconnue/désactivée tracée.
- Limites : MockBukkit, pas de vraie remise de bout en bout dans ces tests (couverte par #422).

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — `docs/MANUAL_TEST_PLAN.md` **TC-331** (nécessite `entitlements.yml` avec
`enabled: true` et `tables:` sur DEV).

## Résultat attendu
Une quête « Tirage au sort » ou un boss avec `table:<id>` tire la table une fois par complétion / par
participant éligible ; le résultat est annoncé, figé, remis une seule fois (attend si l'inventaire est plein)
et tracé dans la fiche joueur avec table, version et chance.

## Reset / retour à l'état initial
Retirer la récompense `TABLE` ou l'entrée `table:<id>` ; mettre la table `enabled: false` ; un gain en attente
se révoque depuis la fiche joueur → Monnaie → Droits à récompense.

## Déploiement VeryGames
Effectué le 2026-10-16 ~13:17 : JAR `adecf3d` (3 277 817 o, SHA-256
`214e79d6ddb773ec5fead0431a3137437b27802e900a8ba49eece00b02a171b9`) par `scripts/deploy-verygames.sh -y`
(« JAR en ligne : 3277817 octets (== local) », « Déploiement terminé ») ; sauvegarde du JAR #176
`rpgquest-20261016T111740Z-predeploy.jar` (3 250 590 o, SHA-256 `a56c4c77…ba5c0e7`) ; aucun joueur connecté →
`scripts/verygames-restart.sh` → ONLINE ; PlugAdmin `scripts/plugadmin/deploy.sh` (son `/health` immédiat a
dit KO — course connue — revérifié : `{"panel":"ONLINE"}`, service actif, JAR servi contient
« Tirage au sort » et `rewardTables`).
Vérifié : action `boss.community.status` sur DEV → SUCCESS avec le nouveau champ `rewardTables` (vide :
pas d'`entitlements.yml`).
### À transférer
Le JAR seul (fait). Aucun fichier de données.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml` (complété automatiquement), `mobs/*.yml`, `state/community-bosses.yml`.
Ne pas créer `entitlements.yml` sans décision du propriétaire.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` puis redémarrage ; PlugAdmin `scripts/plugadmin/rollback.sh`.
Attention : l'ancien JAR refuse une quête de type `TABLE` et un profil boss portant `table:<id>` (retirer
avant rollback) ; il ignore `tables:` / `enchantments` / `potion`. Les droits `QUEST` déjà écrits restent remis.

## Logs / diagnostic
- `Quête <id> : tirage « <table> » v<version> pour <uuid> → [entrées].`
- `Quête <id> : table de récompenses « <table> » inconnue ou désactivée — aucun tirage pour <uuid>.`
- Boss : `  tirage : <joueur> : table « <id> » v<version> → <gains | aucun gain>.`
- Chargement refusé : `Récompenses : entitlements.yml — tables « … » : …`.

## Documentation mise à jour
Bible (droits à récompense §1, récompense `TABLE`, boss §11), docs-site `quests.html`, `REWARD_ENTITLEMENTS.md` §10,
`SPECIAL_MOB_FORMAT.md`, `current_state.md`, TC-331, `SERVER_CHANGELOG.md`, index des rapports.

## Limitations / travail restant
- **Non livré (tranche suivante, à confirmer)** : édition graphique des tables dans PlugAdmin
  (créer/nommer/modifier/dupliquer/activer) — aujourd'hui `entitlements.yml` est un fichier serveur que
  PlugAdmin ne sait pas écrire ; sélecteur **avec recherche** dans les éditeurs de quête et de boss (saisie
  libre validée pour l'instant) ; XP / argent comme entrée de table ; boss de quête (#171).
- **Décisions signalées au propriétaire** : (1) une entrée de table pointe une récompense du catalogue
  (pas d'objet défini dans la table) ; (2) les tables partagent `enabled` et le fichier `entitlements.yml` ;
  (3) gains de quête enregistrés même si `enabled: false` (remis dès l'activation), alors qu'un boss ne
  déclare rien si désactivé (comportement #176) ; (4) enchantement appliqué à l'équipement porté : non
  proposé (aucune cible implicite), seul le livre ou un objet enchanté remis.
- Une table modifiée entre deux essais d'une même déclaration de boss (≤ 10 s) pourrait changer un gain.
- Panel : « Tables » visibles seulement dans la section Boss communautaires (pas de page dédiée).

## Prochaine étape suggérée
Le propriétaire crée `entitlements.yml` sur DEV (exemple TC-331), valide TC-331, tranche les décisions
ci-dessus et confirme la tranche « éditeur de tables PlugAdmin + sélecteur avec recherche ».
