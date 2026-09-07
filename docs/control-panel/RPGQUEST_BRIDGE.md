# Contrat de communication — Control Panel ↔ plugin RPGQuest

Il existe **deux canaux**, complémentaires, qui partagent la même source d'état
(`HealthSource` / `BukkitHealthSource`) :

| Canal | Sens | Doc | Usage |
|---|---|---|---|
| **Bridge local** `/admin/v1/*` (ce document) | PlugAdmin → plugin | ici | dev local, RPGQuest **co-localisé**, diagnostic |
| **Agent sortant** `/agent/v1/*` (#51) | plugin → PlugAdmin | [AGENT.md](AGENT.md) | **VeryGames** / tout serveur derrière NAT |

Le **bridge local** vit **dans le plugin** (`com.lodygames.rpgquest.web.admin`, classes
`WebAdminServer` + `HealthSource` / `BukkitHealthSource`) et écoute `127.0.0.1`. L'**agent
sortant** vit aussi dans le plugin (`com.lodygames.rpgquest.web.agent`) mais n'écoute rien : il
initie des requêtes HTTPS vers PlugAdmin. Dans les deux cas, le Control Panel n'accède jamais à
`data.db`, aux mondes ou à `Citizens/saves.yml` directement.

## Principes (tous appliqués en #37)

- **Authentifié** : `Authorization: Bearer <token>`, comparaison en temps constant, **fail-closed**
  (jeton attendu absent → route fermée ; requête sans/mauvais jeton → `401`).
- **Bind interne** : `127.0.0.1` par défaut (`RPGQUEST_WEB_ADMIN_BIND`). Jamais exposé sur
  Internet en direct.
- **Configuration hors dépôt** : activation + jeton par variables d'environnement, jamais
  `config.yml` (voir [CONFIGURATION.md](CONFIGURATION.md) et [DECISIONS.md](DECISIONS.md) ADR-008).
- **Versionné** : toutes les routes sous `/admin/v1/…`. Évolution incompatible → `/admin/v2/…`.
- **Données réelles** : `HealthSource` lit l'état vivant du serveur (API publique Paper), jamais
  `data.db`.
- **Erreurs propres** : `{ "error": { "code": "...", "message": "..." } }` + code HTTP adapté,
  jamais de stacktrace ni de secret.

## V1 — `GET /admin/v1/health`

```http
GET /admin/v1/health HTTP/1.1
Authorization: Bearer <RPGQUEST_WEB_ADMIN_TOKEN>
```

```json
{
  "status": "ONLINE",
  "plugin": { "name": "RPGQuest", "version": "0.1.0-SNAPSHOT" },
  "bridge_api_version": "v1",
  "target": { "env": "DEV", "mode": "bridge" },
  "server": { "players_online": 1, "max_players": 20, "uptime_seconds": 3720 },
  "worlds": {
    "hub":    { "name": "world_hub", "loaded": true },
    "claims": { "name": "claims",    "loaded": true },
    "wild":   { "name": "wild",      "loaded": false }
  },
  "generated_at": "2026-09-07T20:15:00Z"
}
```

| Champ | Source |
|---|---|
| `plugin.version` | `PluginMeta#getVersion()` |
| `bridge_api_version` | constante `WebAdminServer.API_VERSION` |
| `target.env` | `RPGQUEST_WEB_ADMIN_ENV` |
| `server.players_online` / `max_players` | `Server#getOnlinePlayers().size()` / `getMaxPlayers()` |
| `server.uptime_seconds` | depuis le démarrage de `BukkitHealthSource` (≈ enable du plugin) |
| `worlds.*` | `hub.world` / `claims.world` / `travel.wild-world` de la config + `WorldService#find` |

Réponses d'erreur : `401` (jeton), `405` (méthode ≠ GET), `500` (interne).

## Roadmap des routes (non implémentées en #37)

### Lectures (#38 / #39 / #45)

```
GET /admin/v1/players?online=true|false
GET /admin/v1/players/{uuid}
GET /admin/v1/npc/bindings
GET /admin/v1/npc/expected            # ids PNJ requis par le contenu chargé, avec/sans binding
GET /admin/v1/content/quests
GET /admin/v1/content/stories
GET /admin/v1/content/issues          # diagnostics (quête inconnue, PNJ requis absent, monde manquant…)
GET /admin/v1/claims
```

### Actions whitelistées (#45 — réutilisent la logique #36, jamais dupliquée)

```
POST /admin/v1/actions/quest-complete   { player, quest_id }
POST /admin/v1/actions/quest-start       { player, quest_id, force }
POST /admin/v1/actions/quest-reset       { player, quest_id }
POST /admin/v1/actions/story-advance     { player, story_id }
POST /admin/v1/actions/story-complete    { player, story_id }
POST /admin/v1/actions/variable-get      { player, key }
POST /admin/v1/actions/variable-set      { player, key, value }        # permission stricte
POST /admin/v1/actions/player-resetnew   { player, confirm: true }
POST /admin/v1/actions/content-reload    { }
```

> **Interdit à tout jamais** : `POST /admin/v1/exec { command: "..." }`. Le bridge ne doit jamais
> exposer un `dispatchCommand` arbitraire. Chaque action = route nommée + payload validé + audit
> log des deux côtés.

Pour ces actions, `RpgAdminCommand` (#36) devra être refactoré : la logique métier passe dans des
services appelables sans commande texte.

## VeryGames sans port entrant — **résolu par l'agent sortant #51**

L'hypothèse « mode dégradé `admin-snapshot.json` » (ADR-004) est **abandonnée** : #51 livre un
canal live temps quasi réel dans le bon sens (plugin → PlugAdmin, HTTPS sortant). Voir
[AGENT.md](AGENT.md). Le bridge local `/admin/v1/health` reste utile pour un serveur co-localisé
ou du dev local, et n'est pas supprimé.
