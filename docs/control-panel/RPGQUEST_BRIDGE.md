# Contrat de communication — Control Panel ↔ plugin RPGQuest

Le **bridge** est le seul canal d'intégration. Il vit **dans le plugin**
(`com.lodygames.rpgquest.web.admin`). Le Control Panel n'accède jamais à `data.db`, aux mondes
ou à `Citizens/saves.yml` directement.

## Principes

- **Authentifié** : `Authorization: Bearer <token>` (même schéma que `web-api`, comparaison temps
  constant, fail-closed). Token par environnement, hors Git.
- **Bind interne** : `127.0.0.1` (ou interface privée). Jamais exposé sur Internet.
- **Versionné** : toutes les routes sous `/admin/v1/…`. Une évolution incompatible → `/admin/v2/…`,
  `v1` maintenu le temps de la migration du frontend.
- **Lectures d'abord** : la V1 expose surtout du `GET`. Les actions (`POST`) sont **déclaratives
  et whitelistées** (une route = une opération métier nommée), jamais « exécute cette commande ».
- **Délégation** : chaque route appelle un **service métier existant** du plugin. Le bridge ne
  contient aucune règle de gameplay.
- **Asynchrone/thread-safe** : les handlers HTTP tournent hors thread principal ; toute
  interaction Bukkit repasse par le scheduler (comme `RpgAdminCommand` aujourd'hui).
- **Erreurs exploitables** : `{ "error": { "code": "...", "message": "...", "details": {...} } }`
  + code HTTP adapté. Jamais de stacktrace ni de secret dans la réponse.

## Endpoints V1 (socle)

### Santé / version — **obligatoire V1**

```
GET /admin/v1/health
200 {
  "plugin": { "name": "RPGQuest", "version": "0.1.0-SNAPSHOT", "jar_sha256": "bcc3a6ec…" },
  "server": { "online": true, "tps": 20.0, "players_online": 1, "java": "21" },
  "worlds": [ { "name": "world_hub", "loaded": true }, { "name": "claims", "loaded": true }, ... ],
  "generated_at": "2026-09-07T20:15:00Z"
}
```

### Lectures (V1 : lecture seule, exposition progressive)

```
GET /admin/v1/players?online=true|false            -> [ { uuid, name, last_seen } ]
GET /admin/v1/players/{uuid}                        -> { quests: {...}, stories: {...}, variables: {...}, claims: [...] }
GET /admin/v1/npc/bindings                          -> [ { citizens_uuid, citizens_id, npc_id } ]
GET /admin/v1/npc/expected                          -> [ { npc_id, required_by: ["main_story","dialogues/guard.yml"] } ]   (dérivé du contenu chargé)
GET /admin/v1/content/quests                        -> [ { id, steps, prerequisites, rewards_kinds } ]
GET /admin/v1/content/stories                       -> [ { id, quest_ids } ]
GET /admin/v1/content/issues                        -> [ { severity, message } ]  (ex. "story main_story référence rpgquest:X inconnue", "aucun binding pour npc_id 'guard'")
GET /admin/v1/claims                                -> [ { id, owner_uuid, world, bounds } ]
```

### Actions whitelistées (câblage **progressif**, une par une)

Chaque action = route nommée + payload validé + **audit log** obligatoire côté panel **et** côté
plugin. Réutilisent la logique de l'issue #36 (déjà dans le plugin) :

```
POST /admin/v1/actions/quest-complete   { player, quest_id }              -> { outcome: "COMPLETED"|"ALREADY_COMPLETED"|"UNKNOWN_QUEST" }
POST /admin/v1/actions/quest-start       { player, quest_id, force }       -> { outcome, missing_prerequisites? }
POST /admin/v1/actions/quest-reset       { player, quest_id }              -> { outcome, limitation_note }
POST /admin/v1/actions/story-advance     { player, story_id }              -> { outcome, completed_quest, next_quest, step, total }
POST /admin/v1/actions/story-complete    { player, story_id }              -> { outcome, completed_quests[] }
POST /admin/v1/actions/variable-get      { player, key }                   -> { present, value? }
POST /admin/v1/actions/variable-set      { player, key, value }            -> { previous?, note }        (permission stricte)
POST /admin/v1/actions/player-resetnew   { player, confirm: true }         -> { summary }
POST /admin/v1/actions/content-reload    { }                               -> { quests_loaded, errors[] }
```

> **Interdit** : toute route générique de type `POST /admin/v1/exec { command: "..." }`. Le
> plugin doit refuser d'exposer `dispatchCommand` arbitraire.

## Mode dégradé (VeryGames sans port entrant)

Tant que le plugin de production n'est joignable par aucun port entrant :

- le plugin écrit un **`admin-snapshot.json`** (extension du `snapshot.json` de `web-api`) :
  health + bindings PNJ + PNJ attendus + `content/issues` — **lecture seule**, poussé par le
  même mécanisme d'export atomique ;
- le Control Panel le lit (sur AWS) et affiche un **dashboard en lecture seule** + les
  diagnostics ; les **actions** sont désactivées avec un message clair « bridge live
  indisponible pour cette cible ».
- Quand un canal live devient possible (serveur co-localisé, tunnel, relais — ADR-004), les
  mêmes DTO servent aux deux modes.

## Sécurité du bridge (résumé — détail dans SECURITY.md)

- token obligatoire, fail-closed ; rotation documentée ;
- bind interne uniquement ;
- rate limiting ;
- validation stricte de tous les payloads (types, bornes, existence joueur/quête/story) ;
- audit log côté plugin (`[web-admin] <action> <cible> <résultat>`), en plus de l'audit panel ;
- aucune action destructive sans `confirm: true` explicite dans le payload.
