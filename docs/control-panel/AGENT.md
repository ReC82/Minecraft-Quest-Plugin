# Agent sortant RPGQuest → PlugAdmin (issue #51)

> Canal par lequel un serveur RPGQuest publie son état live à PlugAdmin et exécute des actions
> whitelistées, **sans que PlugAdmin ait à joindre le serveur**. Livré par #51. Complète — sans le
> remplacer — le **bridge local** `/admin/v1/*` de [#37](RPGQUEST_BRIDGE.md).

---

## 1. Pourquoi un flux *sortant*

RPGQuest tourne chez **VeryGames** (hébergeur mutualisé, derrière NAT/pare-feu, **aucun port
entrant** exploitable, ni RCON, ni API). PlugAdmin tourne sur **AWS**
(`https://plugadmin.lodylands.com`). AWS ne peut donc pas raisonnablement ouvrir une connexion
*vers* le bridge HTTP local du plugin sur VeryGames.

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

Le MVP #51 n'ouvre qu'un seul type, **non destructif** :

| Type | Paramètres | Service métier appelé | Effet |
|---|---|---|---|
| `player.variable.get` | `player` (nom ou UUID) **ou** `player_uuid` ; `key` | `PlayerVariableRepository#get` | lecture seule |

Tout autre type → `REJECTED`, sans exécution. Le payload n'est **jamais** transformé en commande
texte `/rpgadmin …` : l'agent appelle une opération structurée. Les mutations #36/#45 viendront
s'ajouter à `AgentActionType`, chacune whitelistée et adossée à un service.

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
le fichier) puis redémarrer. L'agent redevient inerte ; aucune régression gameplay possible
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
