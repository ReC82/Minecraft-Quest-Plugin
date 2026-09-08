# Control Panel — roadmap modulaire

Chaque étape doit laisser `./gradlew build` **vert** et être testable. Aucune ne fabrique de faux
écran fonctionnel : un module non implémenté affiche « à venir ».

## Étape 0 — socle (issue #37, V1) — **LIVRÉ**

- [x] module Gradle `control-panel` autonome (pas de `web-common` en V1 — [ADR-009]).
- [x] `PanelMain` (+ `hash-password`), config par environnement (`Target` multi-cibles), fail-closed
      sur secrets manquants.
- [x] auth mono-utilisateur : `/login`, `/logout`, session signée HMAC, CSRF (double-submit login +
      synchroniseur authentifié), cookies `HttpOnly`/`SameSite`/`Secure` configurable.
- [x] `PermissionService` + énum `Permission`/`Role` (seul `owner` actif).
- [x] `AuditLog` append-only (`control-panel.db`) — utilisé par login/logout.
- [x] **Dashboard = health réel** : statut panel + statut bridge + version plugin + joueurs +
      uptime + mondes essentiels + dernier check. Indisponibilité du bridge → bannière claire,
      jamais un 500.
- [x] plugin : `WebAdminServer` — `GET /admin/v1/health` authentifié, fail-closed, bind interne.
- [x] navigation prête (Dashboard actif ; Joueurs/PNJ/Quêtes/Stories/Diagnostics/Admin/Dev = « à venir »).
- [x] tests : `PanelAppTest` (11), `BridgeClientTest` (4), `PasswordHasherTest` (4), `JsonTest` (3),
      `WebAdminServerTest` (4). `./gradlew test build` vert.
- [x] `docs/control-panel/*` alignés sur l'implémentation.
- [ ] `admin-snapshot.json` (mode dégradé) — reporté ([ADR-004]).

## Étape 0b — déploiement AWS (issue #44) — **LIVRÉ** (validation login owner par navigateur en attente)

- [x] **https://plugadmin.lodylands.com** en ligne : vhost nginx dédié + TLS Let's Encrypt ECDSA +
      redirection 80→443.
- [x] service systemd **`plugadmin`** (utilisateur système dédié, durci), app sous `/opt/plugadmin/app`
      (`installDist`), secrets `/etc/plugadmin/plugadmin.env` hors dépôt.
- [x] backend `127.0.0.1:8090` non exposé ; `/health` OK local **et** public.
- [x] scripts reproductibles `scripts/plugadmin/` (`install.sh` / `deploy.sh` / `rollback.sh`) +
      runbook [DEPLOYMENT_AWS.md](DEPLOYMENT_AWS.md).
- [x] **non-régression** `dig.lodygames.com` / `lodylands.com` (+ `www` / `beta`) : inchangés
      avant/après.
- [x] dashboard « RPGQuest DEV indisponible » rendu proprement (200, pas de 500) — état normal
      jusqu'à #51.
- [ ] login owner **réel** validé depuis un navigateur externe (l'owner ; tout le reste de la
      chaîne d'auth est vérifié : GET login, login invalide → 401, CSRF, `/dashboard` anonyme → 303,
      cookies `Secure`/`HttpOnly`/`SameSite`).

## Étape 0c — agent sortant VeryGames → PlugAdmin (issue #51) — **SOCLE LIVRÉ**

- [x] plugin `com.lodygames.rpgquest.web.agent` : `PlugAdminAgent` (service Paper) + `AgentLoop`
      (heartbeat + file d'actions, backoff, idempotence) + `PlugAdminClient` (HTTPS sortant) +
      `AgentConfigLoader` (fichier local `plugadmin-agent.properties` hors Git, surcharge env,
      fail-closed).
- [x] heartbeat via `HealthSource` (#37 réutilisé, aucune logique de health dupliquée).
- [x] contrat versionné `/agent/v1/heartbeat` · `/agent/v1/actions` · `/agent/v1/actions/{id}/result`
      côté PlugAdmin (`AgentEndpoints`), auth **par agent** (jeton dédié, temps constant), payload
      borné, kill-switch.
- [x] persistance `control-panel.db` : `agent_heartbeat` (dernier par agent) + `agent_action`
      (file + résultats), migrations idempotentes. Pas de MySQL #43.
- [x] ONLINE / STALE / OFFLINE (`AgentLiveness`), seuils configurables, indépendant du navigateur.
- [x] 1re action **non destructive** : `player.variable.get` via `PlayerVariableRepository`
      (jamais `/rpgadmin` texte). Type inconnu → `REJECTED`.
- [x] idempotence des deux côtés (action livrée jusqu'au résultat ; `ProcessedActionCache` agent).
- [x] dashboard : section **AGENT DISTANT** prioritaire quand configurée ; bridge local #37
      conservé en affichage secondaire (dev / co-localisé). Page **Agents** (diagnostic owner :
      statut, dernier heartbeat, envoi de l'action de preuve, historique).
- [x] tests : plugin `web.agent.*` (30) + control-panel `panel.agent.*` (17). `./gradlew build` vert.
- [x] docs : [AGENT.md](AGENT.md), `CONFIGURATION.md`, `SECURITY.md`, `ARCHITECTURE.md`,
      `RPGQUEST_BRIDGE.md`.
- [ ] validation **live VeryGames** (déploiement JAR + fichier agent + redémarrage owner) — voir
      rapport de session #51.

## Étape 1 — lectures — **PARTIELLEMENT LIVRÉ** (`feat/control-panel-admin-tools`)

- [x] module **Joueurs** : roster live + détail (variables get/set, aperçu reset, GIVE) via
      actions agent whitelistées.
- [x] module **Quêtes** : catalogue (titre lisible d'abord, étapes/objectifs/récompenses) + état
      par joueur.
- [x] module **Stories** : catalogue ordonné + état par joueur.
- [ ] module **PNJ** : bindings + « PNJ attendus mais non liés » (aurait signalé le `guard`
      manquant de la session #21). Comparaison contenu chargé ↔ bindings. **(P1, non livré)**
- [ ] module **Diagnostics** : contenu dépôt ↔ contenu chargé ↔ runtime (dépendances cassées,
      quête référencée inconnue, story sans PNJ de rendu…). **(P1, non livré)**

## Étape 2 — actions sûres (#36 via le panel) — **LIVRÉ** (via l'agent sortant #51, pas le bridge)

- [x] `quest start|complete|reset`, `story advance|complete`, `player.variable get|set`,
      `player.item.give`, `player.resetnew preview|confirm` — **actions agent whitelistées**
      (`AgentActionType` ↔ `AgentActionCatalog`), logique dans les services métier du plugin,
      audit log, `confirm` obligatoire sur les mutations, permission `ACTION_*`, validation 3 couches.
- [x] rafraîchissement auto du résultat des actions (issue #65) — `/assets/panel.js`
      démarre sur le compteur serveur **ou** sur un statut non terminal encore visible dans
      le tableau, fait un premier relevé immédiat, et s'arrête dès `pending == 0`.
- [ ] « préparer un état de test » = macro d'actions (ex. « claims: débloqué sans claim »). **(non livré)**

## Étape 3 — contenu (lecture structurée)

- [~] **Page `/npcs` V1** (lecture) — catalogue PNJ via `npc.list` : croise liaison Citizens ↔
      dialogue `rpgquest:<id>` ↔ `giver:` ↔ `TALK_TO_NPC`, anomalies de configuration, ids
      canoniques. Sans lecture du monde.
- [~] **Système PNJ V2 déclarative** — vraie **définition logique** (`npcs/*.yml`, `YamlNpcEngine`,
      `NpcDefinition`), indépendante de Citizens/monde. `npc.list` distingue `logicalDefinitionPresent`
      vs `citizensBindingPresent` + `state` ; `definedIds` = source canonique. Écritures whitelistées :
      `npc.definition.create` / `npc.definition.update` (`NpcDefinitionStore`) et `quest.giver.set`
      (`QuestGiverEditor`, édition minimale du YAML). Page `/npcs` : deux blocs, création + édition
      limitée + attribution de quête.
- [~] **Lier une définition à un PNJ Citizens existant** (#81, phase 1) — `npc.citizens.list`
      (registre Citizens, thread principal) + `npc.citizens.link` (`NpcIdentityService.bindCitizens`
      + `CitizensBindPlanner` : collisions refusées, no-op idempotent, jamais de rebind ni de spawn).
      Page `/npcs` : « Rafraîchir les PNJ Citizens » + formulaire de liaison sur les cartes
      `NOT_LINKED` (Citizens libres seulement).
- [~] **Spawn d'un PNJ Citizens depuis une définition** (#81, phase 2) — `npc.citizens.create`
      (permission dédiée `NPC_SPAWN_WRITE`). `CitizensSpawnPlanner` (pur : Citizens actif,
      définition présente + `enabled`, pas déjà lié, monde de la liste blanche RPGQuest, position
      finie et bornée) → `CitizensSpawnCoordinator` (pur : `create → bind → success`, sinon
      rollback du seul PNJ créé → `BIND_FAILED_ROLLED_BACK`). Nom = `displayName`. Thread principal
      pour Citizens + monde, persistance async. Page `/npcs` : bloc « Créer le PNJ Citizens » sur
      les cartes définies `NOT_LINKED` — preview + monde en liste (heartbeat) + coordonnées à
      saisir + confirmation. **Reste** : premier spawn réel `PENDING MANUAL VALIDATION` ;
      suppression générale (`npc.citizens.delete`), rebind/déplacement, choix de position depuis
      une carte, téléportation admin, câblage `/rpgadmin npc tag` (#66),
      enrichissement live (position/monde, PNJ Citizens non tagués).
- [~] **Page `/dialogues` V1** — lecture structurée + bases d'un futur éditeur. `dialogue.list`
      (lecture, permission dédiée `DIALOGUE_READ`) : `DialogueCatalog` (pur) dérive de
      `YamlDialogueEngine` + `YamlNpcEngine` + `YamlQuestEngine` : par dialogue, nœuds ordonnés
      (départ d'abord) avec `reachable`, choix avec **actions et conditions typées**
      `{kind, target, value, raw}`, relations PNJ (convention `rpgquest:<id>` + `NpcDefinition.dialogue`),
      quêtes référencées/démarrées, warnings (`NODE_UNREACHABLE`, `QUEST_REF_UNKNOWN`,
      `DIALOGUE_NO_NPC`, `DEFINITION_DIALOGUE_DIVERGES`…), `loadIssues[]` à part pour les fichiers
      rejetés. `dialogue.definition.create` (permission dédiée `DIALOGUE_WRITE`) : squelette
      `dialogues/<key>.yml` (`DialogueDraft` → `DialogueDefinitionYaml` déterministe →
      `DialogueDefinitionStore` atomique, refus d'écrasement, re-parsé). Page `/dialogues` :
      graphe lisible par carte, nœud de départ mis en avant, nœuds inaccessibles marqués,
      MiniMessage rendu, formulaire de création de squelette. **Reste (futur éditeur)** :
      `dialogue.node.create/update`, `dialogue.choice.add/update/delete`, édition texte préservant
      commentaires/structure (couche AST), réordonnancement, builder graphique.
- [~] `quest.list` transporte des **objectifs et récompenses structurés**
      (`objectiveDetails` / `rewardDetails` = `{kind, target, amount, value, command, raw}`,
      commande non tronquée) + le **PNJ donneur** (`giverId`, champ YAML `giver:` optionnel) —
      issues #78 / #75. Le panel consomme la structure (`ObjectiveText` / `RewardText.fromSummary`)
      et ne reparse plus de chaîne métier ; repli legacy documenté et déprécié.
- [ ] consultation des YAML (quêtes/dialogues/stories/marchands/items), détection de
      dépendances cassées, comparaison dépôt/serveur, **sans édition** encore.

## Étape 4 — développement (#29)

- [ ] module « Développement » : GitHub (issues/branches/commits), rapports Claude, jobs Claude,
      tests/build, déploiements avec backup, notifications — **même socle auth/backend**, pas une
      2e app.

## Étape 5 — édition guidée & multi-serveur

- [ ] édition guidée de contenu + validation avant déploiement + historique.
- [ ] plusieurs cibles RPGQuest simultanées, staging.
- [ ] RBAC complet (tester / content-editor / builder / read-only).

## Hors périmètre (durablement)

- terminal shell dans le navigateur ;
- lecture/écriture directe de `data.db` comme API live ;
- SPA lourde ;
- dépendance FTP dans le cœur.
