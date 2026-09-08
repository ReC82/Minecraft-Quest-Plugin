# Agent sortant RPGQuest → PlugAdmin (issue #51)

> Canal par lequel un serveur RPGQuest publie son état live à PlugAdmin et exécute des actions
> whitelistées, **sans que PlugAdmin ait à joindre le serveur**. Livré par #51. Complète — sans le
> remplacer — le **bridge local** `/admin/v1/*` de [#37](RPGQUEST_BRIDGE.md).

---

## 1. Pourquoi un flux *sortant*

RPGQuest tourne chez **VeryGames** (hébergeur mutualisé, derrière NAT/pare-feu). VeryGames expose
un port **RCON** (commandes texte — utilisé pour le redémarrage, voir
[VERYGAMES.md](../deployment/VERYGAMES.md)), mais **aucun moyen d'exposer en entrée le bridge HTTP
du plugin** (`/admin/v1/*`) : pas de port HTTP arbitraire ouvrable, RCON ne sert pas du JSON.
PlugAdmin tourne sur **AWS** (`https://plugadmin.lodylands.com`) et ne peut donc pas raisonnablement
ouvrir une connexion *vers* le bridge local du plugin sur VeryGames.

La solution est d'**inverser le sens** :

```text
 RPGQuest / VeryGames                           PlugAdmin / AWS
 ───────────────────                            ───────────────
  PlugAdminAgent  ──────  HTTPS SORTANT  ─────▶  nginx :443
    heartbeat      POST /agent/v1/heartbeat      └─▶ 127.0.0.1:8090
    file d'actions GET  /agent/v1/actions            AgentEndpoints
    résultats      POST /agent/v1/actions/{id}/result   └─▶ AgentStore (control-panel.db)
```

Le plugin **initie** toutes les connexions. PlugAdmin ne lit jamais directement VeryGames, ni
SQLite (`data.db`), ni un port Minecraft.

### Bridge local vs agent distant

| | **Bridge local** (#37) | **Agent distant** (#51) |
|---|---|---|
| Sens | PlugAdmin → plugin (`GET /admin/v1/health`) | plugin → PlugAdmin (`POST /agent/v1/*`) |
| Qui écoute | le plugin, sur `127.0.0.1:8100` | PlugAdmin, sur `:443` public |
| Cas d'usage | dev local, RPGQuest **co-localisé**, diagnostic | **VeryGames** (prod DEV), tout serveur derrière NAT |
| Activation | `RPGQUEST_WEB_ADMIN_ENABLED` + `_TOKEN` (env) | `plugins/RPGQuest/plugadmin-agent.properties` |
| Dashboard | section « Bridge local » | section « AGENT DISTANT » (prioritaire si configuré) |

Les deux réutilisent la **même** source d'état (`HealthSource` / `BukkitHealthSource`) : aucune
logique de health dupliquée. Le dashboard affiche les deux quand ils sont disponibles ; l'agent
prime.

---

## 2. Configuration côté serveur RPGQuest (VeryGames)

### Fichier local (mécanisme de référence)

`plugins/RPGQuest/plugadmin-agent.properties` — **jamais versionné**, déployable séparément.
Modèle : [`scripts/plugadmin-agent.properties.example`](../../scripts/plugadmin-agent.properties.example).

```properties
enabled=true
base-url=https://plugadmin.lodylands.com
agent-id=rpgquest-dev
environment=dev
token=<jeton fort, identique côté PlugAdmin>
heartbeat-seconds=20
poll-seconds=15
actions-enabled=true
```

Clés facultatives : `connect-timeout-ms` (5000), `request-timeout-ms` (10000),
`max-backoff-seconds` (300), `max-response-kib` (256).

### Surcharge par variables d'environnement (facultatif)

Si VeryGames permet de fournir des variables d'environnement au process Paper, elles **surchargent**
le fichier (précédence : env > fichier > défaut) :

| Variable | Clé équivalente |
|---|---|
| `RPGQUEST_PLUGADMIN_ENABLED` | `enabled` |
| `RPGQUEST_PLUGADMIN_BASE_URL` | `base-url` |
| `RPGQUEST_PLUGADMIN_AGENT_ID` | `agent-id` |
| `RPGQUEST_PLUGADMIN_ENVIRONMENT` | `environment` |
| `RPGQUEST_PLUGADMIN_TOKEN` | `token` |
| `RPGQUEST_PLUGADMIN_HEARTBEAT_SECONDS` | `heartbeat-seconds` |
| `RPGQUEST_PLUGADMIN_POLL_SECONDS` | `poll-seconds` |
| `RPGQUEST_PLUGADMIN_ACTIONS_ENABLED` | `actions-enabled` |

Les variables d'environnement ne sont **pas** le seul mécanisme : le fichier local suffit.

### Fail-closed

L'agent reste **inerte** (aucune connexion) si :

- `enabled` ≠ `true` ; **ou**
- `base-url`, `agent-id` ou `token` est absent ; **ou**
- `base-url` n'est pas en HTTPS (sauf `http://127.0.0.1` / `localhost` pour un test local).

Le fichier secret : lecture réservée au compte du process Paper si l'hébergeur le permet ; sinon,
au minimum, jamais exposé publiquement.

---

## 3. Configuration côté PlugAdmin (AWS)

`control-panel.properties` (non secret) :

```properties
agents=rpgquest-dev
agent.rpgquest-dev.environment=dev
agent.rpgquest-dev.token-env=RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV
agent.stale-seconds=45
agent.offline-seconds=150
agent.action-expiry-seconds=300
target.dev.agent=rpgquest-dev
```

`/etc/plugadmin/plugadmin.env` (secret, hors dépôt) :

```
RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV=<le même jeton que côté agent>
```

Le nom de variable par défaut est dérivé de l'id : `RPGQUEST_AGENT_TOKEN_<ID>` avec `<ID>` en
majuscules et `-` → `_`. Un agent sans jeton est **déclaré mais refusé** à l'authentification.

Multi-serveur : ajouter `rpgquest-staging`, `rpgquest-prod`… à `agents=` avec leurs propres
`environment` et `token-env`. Aucune autre modification de code.

---

## 4. Contrat `/agent/v1/*`

Toutes les requêtes portent :

```
Authorization: Bearer <token de l'agent>
X-Agent-Id: rpgquest-dev
X-Agent-Env: dev
```

Authentification : agent déclaré + jeton exact (comparaison en temps constant). Sinon `401`.
Corps borné à 64 Kio → `413`. Kill-switch panel actif → `503`.

### `POST /agent/v1/heartbeat`

```json
{
  "protocol": "agent/v1",
  "agent_id": "rpgquest-dev",
  "environment": "dev",
  "target_env": "DEV",
  "plugin": { "name": "RPGQuest", "version": "0.1.0-SNAPSHOT" },
  "server": { "state": "ONLINE", "players_online": 1, "max_players": 20, "uptime_seconds": 3720 },
  "worlds": {
    "hub":    { "name": "world_hub", "loaded": true },
    "claims": { "name": "claims",    "loaded": true },
    "wild":   { "name": "wild",      "loaded": true }
  },
  "generated_at": "2026-09-07T20:15:00Z"
}
```

`agent_id` / `environment` du corps, s'ils sont présents, doivent correspondre à l'agent
authentifié (`400` sinon). Réponse : `200 {"ok":true,"received_at":"…"}`. PlugAdmin conserve le
**dernier** heartbeat par agent (`agent_heartbeat`).

### `GET /agent/v1/actions`

Réponse : `{"actions":[{"id":"…","type":"player.variable.get","params":{"player":"…","key":"…"}}]}`.
Ne renvoie que les actions de **cet** agent, en statut `PENDING`/`DELIVERED` non terminé, non
expiré. Chaque relevé marque `PENDING → DELIVERED` (+ compteur `deliver_count`). Une action
`DELIVERED` **reste renvoyée** tant qu'aucun résultat n'est arrivé (pendant panel de
l'idempotence).

### `POST /agent/v1/actions/{id}/result`

```json
{ "action_id": "…", "status": "SUCCESS", "value": "false", "message": "Rondoudou9000 : CLAIM_TIER_1 = false", "details": { … } }
```

`status` ∈ `SUCCESS` / `FAILED` / `REJECTED`. `404` si l'action est inconnue ou vise un autre
agent. Rejeu du même résultat : `200`, sans re-traitement (idempotent).

---

## 5. Heartbeat, ONLINE / STALE / OFFLINE

- l'agent envoie un heartbeat toutes les `heartbeat-seconds` (défaut 20 s) ;
- PlugAdmin calcule la fraîcheur à partir de `received_at` **uniquement** (jamais du fait qu'un
  navigateur soit ouvert) :

| État | Condition (âge du dernier heartbeat) |
|---|---|
| `UNKNOWN` | aucun heartbeat jamais reçu |
| `ONLINE` | âge ≤ `agent.stale-seconds` (défaut 45 s) |
| `STALE` | `stale` < âge ≤ `agent.offline-seconds` (défaut 150 s) |
| `OFFLINE` | âge > `agent.offline-seconds` |

Le dashboard et la page **Agents** affichent l'état, l'âge (« il y a 8 s ») et l'horodatage absolu.

---

## 6. File d'actions et idempotence

États : `PENDING → DELIVERED → SUCCESS | FAILED | REJECTED`, ou `EXPIRED` (créée il y a plus de
`agent.action-expiry-seconds` sans résultat). Chaque action a un UUID.

**Idempotence (phase 10)** — deux gardes complémentaires :

1. **Côté PlugAdmin** : une action `DELIVERED` reste renvoyée à l'agent jusqu'à réception d'un
   résultat terminal ; un `POST result` sur une action déjà terminale renvoie `200` sans rien
   changer.
2. **Côté agent** : `ProcessedActionCache` mémorise `action_id → résultat` pendant 30 min (borné
   en taille). Si PlugAdmin re-livre une action déjà traitée (réponse de résultat perdue,
   redémarrage réseau), l'agent **ne ré-exécute pas** — il renvoie le résultat mémorisé.

Sur redémarrage du plugin, le cache agent est vide ; le risque résiduel (résultat perdu *pendant*
un redémarrage) est documenté et acceptable pour le MVP — la seule action ouverte,
`player.variable.get`, est une lecture pure sans effet de bord.

---

## 7. Actions whitelistées

`AgentActionType` (plugin) et `AgentActionCatalog` (panel) sont deux listes blanches **miroir**.
Tout type absent → `REJECTED` sans exécution. Le payload n'est **jamais** transformé en commande
texte `/rpgadmin …` : l'agent appelle une opération **structurée** d'un service métier existant
(`BukkitAgentActions` → `QuestProgressEngine` / `StoryService` / `YamlCustomItemRegistry` /
`PlayerResetService` / `PlayerVariableRepository`). Toute mutation est replacée sur le **thread
principal** par `BukkitAgentActions` (l'agent poll depuis un thread async).

### Lectures (aucun effet de bord)

| Type | Paramètres | Service | Résultat (`details`) |
|---|---|---|---|
| `player.variable.get` | `player` \| `player_uuid` ; `key` | `PlayerVariableRepository#get` | `present`, `value` |
| `player.list` | — | `Server#getOnlinePlayers` | `players[]` (uuid, name, world, x/y/z) |
| `quest.list` | — | `YamlQuestEngine#quests` | `quests[]` — voir **Payload `quest.list`** ci-dessous |
| `quest.player.status` | `player` | `QuestProgressEngine#allStates` / `activeStepView` | `quests[]` (state, étape, objectifs current/required) |
| `story.list` | — | `StoryService#stories` | `stories[]` (id, titre, quêtes ordonnées) |
| `story.player.status` | `player` | `StoryService#info` | `stories[]` (state, étape courante/total, quête courante) |
| `item.list` | — | `YamlCustomItemRegistry#items` | `items[]` (id, displayName, type) |
| `npc.list` | — | `NpcCatalog` (`YamlNpcEngine` + dialogues + quêtes + `NpcBindingRepository`) | `npcs[]` + `definedIds` + `canonicalIds` — voir **Payload `npc.list`** ci-dessous |
| `npc.citizens.list` | — | `NpcIdentityService#citizensRoster` (registre Citizens, thread principal) + `NpcBindingRepository` | `citizens[]` `{numericId, uuid, name, linkedNpcId?, availableForBinding, spawned}` + `total`/`available`/`linked`. **Catalogue physique**, séparé du catalogue logique `npc.list`. Aucune position/monde. Vide si Citizens inactif. |
| `dialogue.list` | — | `DialogueCatalog` (pur) sur `YamlDialogueEngine` + `YamlNpcEngine` + `YamlQuestEngine` | `dialogues[]` (id, key, startNodeId, linkedNpcIds, nodeCount, choiceCount, referencedQuestIds, startsQuestIds, `nodes[]` `{id, speaker, text, start, reachable, choices[]}` avec **actions et conditions typées** `{kind, target, value, raw}`, warnings) + `loadIssues[]` (fichiers rejetés) + `declaredButMissing[]` — voir **Payload `dialogue.list`** ci-dessous |
| `player.resetnew.preview` | `player` | `PlayerResetService#previewReset` | `lines[]` (label, count, detail) — **dry-run** |

### Mutations (confirmation exigée côté panel)

| Type | Paramètres | Service | Effet |
|---|---|---|---|
| `player.item.give` | `player` ; `item_id` ; `amount` (1–64) | `YamlCustomItemRegistry#create` + `Inventory#addItem` | joueur **en ligne** requis |
| `quest.start` | `player` ; `quest_id` ; `force`? | `QuestProgressEngine#accept(…, ignorePrerequisites)` | en ligne requis |
| `quest.complete` | `player` ; `quest_id` | `QuestProgressEngine#forceComplete` | récompenses appliquées **une fois** |
| `quest.reset` | `player` ; `quest_id` | `QuestProgressEngine#resetQuest` | hors ligne OK ; **n'annule pas** les récompenses déjà données |
| `story.advance` | `player` ; `story_id` | `StoryService#adminAdvance` | en ligne requis |
| `story.complete` | `player` ; `story_id` | `StoryService#adminComplete` | en ligne requis ; VARIABLE (CLAIM_TIER_1…) appliquées une fois |
| `player.variable.set` | `player` ; `key` ; `value` (≤256) | `PlayerVariableRepository#set` | **outil debug** ; ne rejoue pas une progression |
| `player.resetnew.confirm` | `player` ; `confirm=true` (garde-fou agent) | `PlayerResetService#resetToNewPlayer` | remet l'état RPGQuest « jamais joué » (jamais `data.db` entier, jamais un autre joueur) |
| `npc.definition.create` | `npc_id` ; `display_name` ; `dialogue_id`? ; `role`? ; `enabled` | `NpcDefinitionStore#create` | crée `npcs/<id>.yml` — voir **Écritures de contenu PNJ** |
| `npc.definition.update` | idem (id inchangé) | `NpcDefinitionStore#update` | réécrit une définition existante |
| `quest.giver.set` | `quest_id` ; `npc_id` | `QuestGiverEditor` + `QuestProgressEngine#reloadQuestDefinitions` | pose `giver:` sur le YAML d'une quête |
| `npc.citizens.link` | `npc_id` ; `citizens_id` (entier > 0) | `NpcIdentityService#bindCitizens` (`CitizensBindPlanner` + `NpcBindingRepository#insertIfAbsent`) | lie une **définition existante** à un **PNJ Citizens existant** — issue #81, phase 1 ; jamais de spawn/rebind |
| `npc.citizens.create` | `npc_id` ; `world` ; `x` ; `y` ; `z` ; `yaw`? ; `pitch`? | `CitizensSpawnPlanner` (pur) → `CitizensSpawnCoordinator` (`NpcIdentityService#createCitizensNpc` + `#bindCitizens`, rollback `#destroyCitizensNpc`) | **crée physiquement** un PNJ Citizens depuis la définition (nom = `displayName`) puis le lie ; rollback du PNJ créé si la liaison échoue — issue #81, phase 2 ; permission dédiée `NPC_SPAWN_WRITE` |
| `dialogue.definition.create` | `key` (clé minuscule) ; `speaker` ; `text` | `DialogueDefinitionStore#create` (`DialogueDraft.skeleton` → `DialogueDefinitionYaml`) | crée un **squelette** `dialogues/<key>.yml` (un nœud `start`, un choix « fermer ») — jamais de YAML brut, refus d'écrasement, re-parsé après écriture ; permission dédiée `DIALOGUE_WRITE` — V1 `/dialogues` |
| `dialogue.node.update` | `dialogue_id` ; `node_id` ; `speaker` ; `text` | `DialogueDefinitionEditor#updateNode` | change le locuteur + le texte d'un nœud existant (les choix, conditions et actions sont conservés) — éditeur guidé #82 phase 1 |
| `dialogue.node.create` | `dialogue_id` ; `node_id` (minuscule) ; `speaker` ; `text` | `DialogueDefinitionEditor#createNode` | ajoute un nœud simple + un choix « fermer » ; **nœud orphelin** (à relier via `dialogue.choice.add`) ; refuse un id déjà pris |
| `dialogue.choice.add` | `dialogue_id` ; `node_id` ; `choice_text` ; `next_node_id` **ou** `close=true` | `DialogueDefinitionEditor#addChoice` | ajoute un **choix simple** (aucune condition, aucune action hors « fermer ») : soit une redirection `next` vers un nœud existant, soit une fermeture |
| `dialogue.choice.update` | `dialogue_id` ; `node_id` ; `choice_index` ; `choice_text` ; `next_node_id` **ou** `close=true` | `DialogueDefinitionEditor#updateChoice` | réécrit le texte + la cible d'un **choix simple** ; **refuse** un choix portant condition ou action de quête (`UNSAFE_CHOICE`) |
| `dialogue.choice.delete` | `dialogue_id` ; `node_id` ; `choice_index` | `DialogueDefinitionEditor#deleteChoice` | retire un **choix simple** ; **refuse** le dernier choix d'un nœud (`LAST_CHOICE`) ou un choix non simple |

Validation à **trois couches** : `AgentActionCatalog` (panel, avant création) → `AgentActionExecutor`
(agent, patterns bornés) → service métier. Quantité GIVE plafonnée à 64. `player.resetnew.confirm`
et toutes les mutations exigent une confirmation explicite dans le formulaire du panel ; l'agent
exige en plus `confirm=true` pour le reset.

### Payload `quest.list` (structuré — issues #78 / #75)

Chaque entrée de `quests[]` :

| Champ | Type | Détail |
|---|---|---|
| `id`, `title`, `category`, `repeatable`, `prerequisites` | — | inchangés |
| `giverId` | `string` | **présent seulement si** la quête déclare `giver:` (YAML). `giverName` réservé, non rempli aujourd'hui. |
| `steps[].id` | `string` | id d'étape |
| `steps[].objectives` | `string[]` | **legacy** — chaînes déjà formatées (`"Tuer SPIDER (x5)"`). Conservé pour l'agent déjà déployé. |
| `steps[].objectiveDetails` | `object[]` | **structuré** : `{kind, target, amount, raw}`. `kind` = `ObjectiveType` (`KILL_ENTITY`, `COLLECT_ITEM`, `CRAFT_ITEM`, `BREAK_BLOCK`, `PLACE_BLOCK`, `TALK_TO_NPC`, `REACH_LOCATION`) ; `target` = jeton technique (entité, matériau, id de PNJ, nom de monde). |
| `rewards` | `string[]` | **legacy** — chaînes déjà formatées ; commande **tronquée à 60**. |
| `rewardDetails` | `object[]` | **structuré** : `{kind, amount, target, value, command, raw}`. `kind` = `RewardType` (`EXPERIENCE`, `ITEM`, `VARIABLE`, `COMMAND`). `command` **jamais tronquée**. |

Le panel privilégie les champs structurés (`ObjectiveText` / `RewardText.fromSummary`) et retombe
sur les chaînes legacy (`MinecraftNames.humanizeTokens` / `RewardText.parse`) tant que l'agent
d'une cible n'a pas été redéployé. Les champs legacy sont **dépréciés** : à retirer une fois tous
les agents à jour.

### Payload `npc.list` (catalogue PNJ — V2 déclarative, issues #66 / #75)

**Lecture seule.** V2 : le modèle distingue la **définition logique** RPGQuest
(`plugins/RPGQuest/npcs/*.yml`, `YamlNpcEngine` — indépendante du monde et de Citizens) du
**binding physique Citizens** (`npc_citizens_bindings`). Dérivation croisée dans
`com.lodygames.rpgquest.npc.NpcCatalog` (pure, testable) : définition ↔ binding ↔ dialogue
`rpgquest:<id>` ↔ `giver:` d'une quête (#75) ↔ objectif `TALK_TO_NPC`. **Ne lit jamais le monde**
(position, monde, PNJ Citizens *non tagués* = hors périmètre).

`details` :

| Champ | Type | Détail |
|---|---|---|
| `npcs[]` | `object[]` | `{id, displayName?, logicalDefinitionPresent, citizensBindingPresent, citizensNumericId?, bindingCount, enabled, description?, role?, definedDialogueId?, hasDialogue, dialogueId?, dialogueNodes, dialogueChoices, dialogueStartsQuests[], questsGiven[], questsReferenced[], sources[], state, warnings[]}`. `displayName` = celui de la définition, sinon le `speaker` du dialogue, sinon `null`. Trié : erreurs, avertissements, puis PNJ sains. |
| `npcs[].state` | `string` | `LINKED` / `NOT_LINKED` / `DISABLED` / `CITIZENS_ORPHAN` / `UNDEFINED_REFERENCE` / `BROKEN`. |
| `npcs[].warnings[]` | `object[]` | `{code, severity, message}`. Codes : `DUPLICATE_DEFINITION` / `DUPLICATE_BINDING` / `DIALOGUE_MISSING` (err) ; `NO_DEFINITION` (err — id référencé sans définition, à migrer) ; `BINDING_NO_DEFINITION` (err — binding sans définition, + suggestion d'id défini proche `garde`→`guard`) ; `NOT_LINKED` / `DISABLED` / `GIVER_NO_DIALOGUE` (info). |
| `definedIds` | `string[]` | Ids ayant une **définition logique** — la source de vérité (préparation #66). |
| `canonicalIds` | `string[]` | Union `definedIds` + ids encore seulement référencés (transition). |
| `citizensAvailable` | `bool` | Citizens actif sur le serveur cible. |
| `total`, `withDefinition`, `withoutDefinition`, `bound`, `withWarnings` | `int` | Compteurs. |

### Écritures de contenu PNJ (V2 — `npc.definition.*` / `quest.giver.set`)

Mutations whitelistées, confirmation panel obligatoire, auditées. **Jamais** de YAML brut ni de
chemin arbitraire : le navigateur n'envoie que des champs métier validés, l'agent reconstruit le
fichier (`NpcDefinitionYaml`) ou applique une édition de texte minimale (`QuestGiverEditor`).

| Type | Paramètres | Effet |
|---|---|---|
| `npc.definition.create` | `npc_id`, `display_name`, `dialogue_id`?, `role`?, `enabled` | Crée `npcs/<id>.yml` via `NpcDefinitionStore` — **échoue si l'id existe** (pas d'écrasement). Recharge `YamlNpcEngine`. Aucun PNJ Citizens créé. |
| `npc.definition.update` | idem (id inchangé) | Réécrit la définition existante (échoue si absente). |
| `quest.giver.set` | `quest_id`, `npc_id` | Pose `giver: <npc_id>` sur le fichier de la quête (`QuestGiverEditor` — commentaires préservés, ligne racine `giver:` remplacée ou insérée après `category:`). Exige que la quête **et** la définition PNJ existent. Recharge le moteur de quêtes (thread principal). |

`AgentActionExecutor` re-valide les patterns (`npc_id` `[a-z0-9._-]{1,64}`, `display_name` ≤ 128
et mono-ligne, `dialogue_id` `namespace:clé` ou clé simple, `role` `[a-z0-9_-]{1,32}`).

### Liaison définition ↔ Citizens existant (`npc.citizens.link` — issue #81, phase 1)

`npc.citizens.list` (lecture) : parcourt le **registre Citizens** (thread principal, jamais un
scan d'entités/chunks) et croise avec `npc_citizens_bindings` pour marquer chaque PNJ
`availableForBinding` (aucune liaison) ou non.

`npc.citizens.link` (mutation, permission dédiée `NPC_BIND_WRITE`, `confirm` obligatoire, audit) :

1. `npc_id` doit avoir une **définition logique** (`YamlNpcEngine.find`) et être `enabled` ;
2. `citizens_id` doit **exister** dans le registre Citizens (résolu `onMain`) ;
3. `CitizensBindPlanner` décide sur la photo `npc_citizens_bindings` : liaison identique → `NOOP`
   (succès) ; ce PNJ Citizens déjà lié à un autre `npc_id` → `CITIZENS_TAKEN` (refus, message
   « Citizens #N est déjà lié à npc_id=X. ») ; ce `npc_id` déjà lié ailleurs → `NPC_ID_TAKEN`
   (refus) ; sinon `INSERT` ;
4. écriture atomique `NpcBindingRepository.insertIfAbsent` (`INSERT OR IGNORE`), puis rafraîchit le
   cache `NpcIdentityService` pour que l'identification en jeu prenne effet sans redémarrage.

Threading : registre Citizens sur le **thread principal** (`onMain`), base **asynchrone** — jamais
de SQLite bloquant sur le thread principal. **Aucun spawn, aucun rebind, aucune suppression** dans
cette phase.

Une fois une définition créée pour un `npc_id` qui a **déjà** une ligne `npc_citizens_bindings`
(cas `guide`/`libraire`… sur DEV), aucune action n'est nécessaire : le prochain `npc.list`
recroise binding + définition et passe l'état à `LINKED` automatiquement.

### Spawn d'un PNJ Citizens depuis une définition (`npc.citizens.create` — issue #81, phase 2)

`npc.citizens.create` (mutation, permission dédiée **`NPC_SPAWN_WRITE`**, `confirm` obligatoire,
audit) crée **physiquement** un PNJ Citizens à partir d'une `NpcDefinition` puis le lie
immédiatement. Paramètres **métier uniquement** : `npc_id`, `world`, `x`/`y`/`z`, `yaw`?/`pitch`?
(défaut `0`). Le **nom affiché vient de `NpcDefinition.displayName`** — le navigateur n'envoie
jamais de nom Citizens libre, d'UUID, de commande console, de YAML ni de chemin.

Étapes (échec = aucun spawn) :

1. **Préconditions pures** (`CitizensSpawnPlanner`, sans Bukkit) : Citizens actif
   (`CITIZENS_UNAVAILABLE`) ; définition présente (`UNKNOWN_NPC`) et `enabled` (`NPC_DISABLED`) ;
   `npc_id` pas déjà lié (`NPC_ALREADY_LINKED`) ; `world` dans la **liste blanche RPGQuest**
   (`hub`/`claims`/exploration de la config — `UNKNOWN_WORLD`) ; position **finie** et bornée
   (`|x|,|z| ≤ 29 999 984`, `-2048 ≤ y ≤ 2048`, `-90 ≤ pitch ≤ 90` — `INVALID_POSITION`, jamais
   « corrigée »).
2. **Thread principal** : le monde doit être **chargé** (`UNKNOWN_WORLD`) et `y` dans ses limites
   réelles (`INVALID_POSITION`) ; puis `CitizensAPI.getNPCRegistry().createNPC(PLAYER, name)` +
   `npc.spawn(loc, CREATE)` (`CREATE_FAILED` si Citizens refuse).
3. **Async** : `NpcIdentityService.bindCitizens` (réutilise `CitizensBindPlanner` +
   `insertIfAbsent`, anti-collision phase 1).
4. **Rollback** : si la liaison échoue *après* création, `destroyCitizensNpc(numericId, uuid)`
   supprime **le seul PNJ créé par cette action** (double clé — jamais un PNJ préexistant) →
   `BIND_FAILED_ROLLED_BACK` (`rolled_back` indique si la suppression a réussi).

Résultat structuré : `{code, npc_id, citizens_id, world, rolled_back, effects}`. Succès →
`code=CREATED`, `value=<citizens_id>`. Un `npc.list` suivant montre `state: LINKED`.

**Hors périmètre** (chantiers séparés) : suppression générale d'un PNJ Citizens
(`npc.citizens.delete` — seul le rollback interne supprime), rebind/déplacement d'un PNJ existant,
choix de position depuis une carte, téléportation admin vers le PNJ.

### Payload `dialogue.list` + squelette `dialogue.definition.create` (V1 `/dialogues`)

`dialogue.list` (lecture, permission dédiée `DIALOGUE_READ`) : `DialogueCatalog` (pur, sans
Bukkit) dérive depuis `YamlDialogueEngine.dialogues()` + `lastReport().issues()` + `YamlNpcEngine`
+ `YamlQuestEngine`.

- **Structure par dialogue** : `id` (`rpgquest:<key>`), `startNodeId`, `linkedNpcIds`,
  `nodeCount`, `choiceCount`, `referencedQuestIds`, `startsQuestIds`, `nodes[]` (dans l'ordre :
  départ d'abord, puis par id) `{id, speaker, text, start, reachable, choices[]}`. Chaque choix :
  `{text, nextNodeId, actions[], conditions[]}` où **actions et conditions sont typées**
  `{kind, target, value, raw}` (jamais une simple chaîne) — `kind` ∈ `START_QUEST` /
  `ADVANCE_QUEST` / `TURN_IN_QUEST` / `GIVE_ITEM` / `TAKE_ITEM` / `SET_VARIABLE` /
  `RUN_SAFE_COMMAND` / `OPEN_DIALOGUE` / `OPEN_MERCHANT` / `CLOSE` (actions) et `QUEST_STATE` /
  `HAS_ITEM` / `HAS_PERMISSION` / `VARIABLE_EQUALS` / `NO_MAIN_CLAIM` / `HAS_MAIN_CLAIM` /
  `LACKS_CUSTOM_ITEM` (conditions, `negated` déplié depuis `NegatedCondition`).
- **`reachable`** : BFS depuis `startNodeId` en suivant les `next` (les `OPEN_DIALOGUE`
  inter-dialogues ne comptent pas). Un nœud non atteint → warning `NODE_UNREACHABLE` (info).
- **Warnings** : `NODE_UNREACHABLE` (info), `QUEST_REF_UNKNOWN` (warning — `START_QUEST`… ou
  condition `QUEST_STATE` vers une quête non chargée), `DIALOGUE_NO_NPC` (info — dialogue relié à
  aucun PNJ logique), `MULTIPLE_NPCS` (info), `DEFINITION_DIALOGUE_DIVERGES` (warning — une
  définition PNJ déclare un `dialogue:` différent du dialogue de convention `rpgquest:<id>`),
  `NEXT_MISSING` (error, défensif).
- **Erreurs de chargement** (dialogue sans nœud, `start` invalide, `next` inexistant, id
  dupliqué, cycle `OPEN_DIALOGUE`) : elles empêchent le fichier de devenir une
  `DialogueDefinition` → remontées **à part** dans `loadIssues[]` `{file, message}` (jamais dans
  la liste des dialogues). `declaredButMissing[]` `{npcId, dialogueId}` = définitions PNJ
  pointant vers un dialogue absent.
- Ouverture en jeu : **toujours par convention `rpgquest:<npcId>`** (identité stable du PNJ
  Citizens/vanilla, voir `DialogueNpcInteractListener` / `DialogueCitizensNpcInteractListener`),
  jamais via `NpcDefinition.dialogue` (ce champ ne sert qu'aux diagnostics).

`dialogue.definition.create` (mutation, `DIALOGUE_WRITE`, `confirm` obligatoire, audit) : produit
un **squelette minimal valide** `dialogues/<key>.yml` — `id: rpgquest:<key>`, `start: start`, un
nœud `start` avec `speaker` + `text` + un unique choix « Au revoir » (`type: CLOSE`). `DialogueDraft`
+ `DialogueDefinitionYaml.render` (déterministe, re-parsable) + `DialogueDefinitionStore.create`
(écriture atomique tmp + `ATOMIC_MOVE`, **refus d'écrasement** `EXISTS`, rechargement complet du
dossier — fichier supprimé si le nouveau dialogue ne se recharge pas). Jamais de YAML brut, jamais
de chemin.

### Éditeur guidé `dialogue.node.*` / `dialogue.choice.*` (issue #82 phase 1)

Cinq mutations (`DIALOGUE_WRITE`, `confirm` obligatoire côté panel, audit) éditent un dialogue
**déjà chargé**, via `DialogueDefinitionEditor` (pur IO + parsing). Périmètre volontairement
restreint : **locuteur / texte** d'un nœud, **nœud simple**, **choix simple** (aucune condition,
aucune action hors `CLOSE`). Hors périmètre phase 1 : édition des actions/conditions riches,
renommage de nœud, suppression de nœud, réordonnancement.

Discipline d'écriture, à chaque mutation :

1. **localiser** le fichier du dialogue par son `id` (jamais un chemin fourni) ;
2. le **re-parser tel quel** — s'il est *déjà* invalide → `SOURCE_INVALID`, rien n'est écrit ;
3. appliquer la mutation sur le modèle métier `DialogueDefinition` ;
4. **sérialiser le dialogue complet** (`DialogueDefinitionWriter` — les 10 actions et 8 conditions
   + négation, aucune perte), le re-parser **en mémoire** et exiger l'**égalité sémantique**
   (`reparsed.equals(muté)`) — sinon `ROUNDTRIP`, rien n'est écrit ;
5. écrire **atomiquement** (fichier temporaire + `move`) ;
6. **recharger tout le dossier** : si le fichier est rejeté ou le dialogue absent → `RELOAD_FAILED`
   et le contenu d'origine est **restauré à l'octet près** ; sinon `YamlDialogueEngine.reload()`
   (le dialogue édité est immédiatement pris en compte en jeu).

Le fichier édité adopte le **format canonique** du panel : `id` / `start` / `nodes` (départ
d'abord puis par id), `text` toujours entre guillemets doubles, commentaires et mise en forme
d'origine **non conservés**. Choix assumé (voir rapport #82) : l'intégrité des actions/conditions,
elle, est garantie par le garde-fou round-trip.

Codes d'échec : `NOT_FOUND`, `SOURCE_INVALID`, `INVALID`, `NODE_EXISTS`, `UNKNOWN_NODE`,
`UNKNOWN_TARGET`, `UNKNOWN_CHOICE`, `UNSAFE_CHOICE`, `LAST_CHOICE`, `ROUNDTRIP`, `RELOAD_FAILED`,
`ERROR`.

**Reste pour la suite de #82** : actions typées éditables (`START_QUEST`, `CLOSE`…), conditions
éditables, renommage / déplacement / suppression de nœud, réordonnancement des choix, rendu
graphe interactif.

---

## 8. Robustesse (l'agent ne perturbe jamais le gameplay)

- tout le trafic réseau est sur des **threads asynchrones Bukkit** (`runTaskTimerAsynchronously`) —
  jamais le thread principal ;
- `HttpClient` JDK, validation de certificat **normale**, aucun `trustAll` ;
- timeouts courts (connexion 5 s, requête 10 s) ;
- **backoff exponentiel** plafonné (5 s → … → `max-backoff-seconds`) en cas de panne ;
- logs d'avertissement **limités** (au plus 1/min par flux) ;
- si PlugAdmin est down : le serveur Minecraft continue normalement, l'agent se reconnecte plus
  tard ; aucune action n'est jamais considérée comme réussie à tort ;
- arrêt propre : les tâches planifiées sont annulées, une requête en vol se termine dans son
  timeout.

Log de démarrage (preuve de connectivité, phase 1) :

```
event=plugadmin_probe status=ok detail="HTTP 200" target=https://plugadmin.lodylands.com
  — connectivité HTTPS sortante VeryGames → PlugAdmin CONFIRMÉE.
```

ou, si la sortie HTTPS est bloquée :

```
event=plugadmin_probe status=failed detail="…" target=… — premier heartbeat non abouti (backoff…).
```

---

## 9. Sécurité

- HTTPS obligatoire (Let's Encrypt, terminaison nginx) ;
- jeton porteur **par cible**, comparaison en temps constant, hors Git, hors logs, hors réponses ;
- `agent_id` + `environment` validés à chaque requête ;
- payload borné (`413`), `action_id` restreint à `[A-Za-z0-9_-]{1,80}` ;
- types d'action **whitelistés** ; aucun shell, RCON, SQL, chemin de fichier transporté ;
- audit PlugAdmin : `agent.action.create` (création) et `agent.action.result` (résultat) dans
  `control-panel.db` ; les heartbeats ne sont pas audités (la table `agent_heartbeat` en tient
  lieu) ;
- idempotence / anti-rejeu (voir §6) ;
- rate limiting : non implémenté au niveau applicatif ; nginx est devant et le backoff agent
  borne naturellement la fréquence. À durcir si d'autres agents apparaissent.

Pas de PKI complète : HTTPS + jeton fort par cible + idempotence suffisent au MVP.

---

## 10. Persistance

Tout dans **`control-panel.db`** (SQLite, propre à PlugAdmin) — jamais `data.db`.

- `agent_heartbeat` : une ligne par agent (dernier heartbeat, upsert) ;
- `agent_action` : `id`, `agent_id`, `type`, `params_json`, `status`, horodatages, `deliver_count`,
  `result_status` / `result_value` / `result_message` / `result_json`.

Migrations idempotentes (`CREATE TABLE IF NOT EXISTS`). La migration MySQL #43 n'est **pas**
nécessaire pour ce jalon.

---

## 11. Diagnostic

| Symptôme | Piste |
|---|---|
| Dashboard « Aucun heartbeat reçu » | l'agent n'a pas contacté PlugAdmin. Vérifier `plugadmin-agent.properties` (`enabled`, `base-url`, `token`), le log serveur `event=plugadmin_probe`, la sortie HTTPS de l'hébergeur. |
| `event=plugadmin_probe status=failed` | sortie HTTPS bloquée ou mauvaise URL. Tester depuis le serveur : `curl -sS -o /dev/null -w '%{http_code}\n' https://plugadmin.lodylands.com/agent/v1/actions -H 'Authorization: Bearer X' -H 'X-Agent-Id: rpgquest-dev'` (401 attendu). |
| Heartbeat refusé (HTTP 401) | jeton agent ≠ `RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV` côté PlugAdmin, ou `agent-id` non déclaré (`agents=`). |
| État bloqué `STALE`/`OFFLINE` | l'agent n'envoie plus : regarder le log serveur (backoff ? erreur ?), le réseau, `PANEL_DISABLED`. |
| Action reste `PENDING` | l'agent ne relève pas (`actions-enabled=false` ? poll en backoff ? mauvais agent-id ?). |
| Action `EXPIRED` | créée il y a plus de `agent.action-expiry-seconds` sans agent pour la traiter. |
| Journaux | serveur RPGQuest : `Agent PlugAdmin …` ; PlugAdmin : `journalctl -u plugadmin` → `event=agent_heartbeat`, `event=agent_actions_poll`, `event=agent_action_result`. |

---

## 12. Rollback

**Côté serveur RPGQuest** : mettre `enabled=false` dans `plugadmin-agent.properties` (ou supprimer
le fichier) puis redémarrer (`scripts/verygames-restart.sh` via RCON, ou panel VeryGames).
L'agent redevient inerte ; aucune régression gameplay possible
(l'agent n'a aucun point de contact avec le jeu hormis une lecture de variable à la demande).
Le JAR peut rester en place : sans le fichier, le code est dormant.

**Côté PlugAdmin** : `agents=` (vide) dans `control-panel.properties` + `systemctl restart
plugadmin` → les routes `/agent/v1/*` répondent `401` à tout. Le dashboard retombe sur le bridge
local (« indisponible » assumé). Rollback complet du code : `scripts/plugadmin/rollback.sh app`
(release précédente).

Aucune migration à défaire : les tables `agent_*` restent, inertes.

---

## 13. Ajouter un futur serveur / agent

1. Choisir un `agent-id` (ex. `rpgquest-staging`) et un `environment`.
2. Générer un jeton fort dédié.
3. PlugAdmin : ajouter à `agents=`, définir `agent.<id>.environment`, ajouter
   `RPGQUEST_AGENT_TOKEN_<ID>` dans `plugadmin.env`, `systemctl restart plugadmin`.
4. (option) `target.<t>.agent=<id>` pour l'afficher sur le dashboard d'une cible.
5. Serveur : déployer `plugins/RPGQuest/plugadmin-agent.properties` avec le même jeton, redémarrer.

Aucune nouvelle architecture réseau : #38/#39/#45 réutiliseront ce canal.
