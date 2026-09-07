# Control Panel — sécurité

Le Control Panel aura à terme des **pouvoirs administratifs importants** (reset joueur, avance de
progression, reload de contenu, plus tard édition et déploiement). La sécurité est une
**contrainte de socle**, pas un ajout ultérieur.

## État #37 (implémenté)

| Exigence | Statut |
|---|---|
| Auth obligatoire, fail-closed (tout sauf `/login`, `/health`) | ✅ |
| Hash mot de passe owner PBKDF2-HMAC-SHA256 (`PasswordHasher`), hors Git | ✅ |
| Session serveur-side, id 256 bits, **cookie signé HMAC** (`RPGQUEST_PANEL_SECRET`) | ✅ |
| Cookies `HttpOnly` + `SameSite=Lax` + `Secure` configurable | ✅ |
| Expiration absolue (`ttl`) **et** inactivité (`idle`), invalidation au logout | ✅ |
| CSRF : double-submit au `/login`, synchroniseur sur POST authentifié (`/logout`, futures actions) | ✅ |
| En-têtes : `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, CSP restrictive, `Cache-Control: no-store` | ✅ |
| Audit log append-only (`SqliteAuditLog`, `control-panel.db`) — login/logout | ✅ |
| Kill-switch `PANEL_DISABLED` → 503 partout sauf `/health` | ✅ |
| Bridge : Bearer, temps constant, fail-closed, bind interne | ✅ |
| Secrets absents des réponses (test `responsesNeverLeakSecrets`) | ✅ |
| Rate limiting login / backoff | ⏳ à ajouter (délai constant PBKDF2 déjà payé sur échec ; nginx devant) |
| HTTPS + reverse proxy | ✅ #44 — `https://plugadmin.lodylands.com`, TLS Let's Encrypt, 80→443, backend `127.0.0.1:8090` non exposé ([DEPLOYMENT_AWS.md](DEPLOYMENT_AWS.md)) |
| RBAC multi-rôles | ⏳ énum posée, un seul rôle `owner` actif |

## Modèle de menace (V1)

| Menace | Mitigation |
|---|---|
| Accès anonyme à une page admin | **auth obligatoire** sur tout sauf `/login`, `/health` (statut panel nu), assets statiques. Fail-closed. |
| Vol de session | cookie `HttpOnly` + `Secure` + `SameSite=Lax`, ID de session aléatoire ≥ 256 bits, expiration + rotation à la connexion, invalidation au logout. |
| CSRF sur une action admin | jeton CSRF par session, requis sur tout `POST`/`PUT`/`DELETE` (double-submit ou synchronizer token). `SameSite=Lax` en défense de profondeur. |
| Brute force du login | rate limiting par IP + backoff progressif ; délai constant sur l'échec ; verrouillage temporaire après N échecs. |
| Fuite de secret | secrets **hors Git** (env / fichier ignoré `chmod 600`). Jamais dans le HTML, les réponses API, les logs, l'audit log. Revue : `git grep` interdit de trouver un token. |
| Injection / payload malformé | validation stricte de **tous** les inputs (type, longueur, bornes, existence). Rejet fail-closed. Pas de rendu de HTML non échappé. |
| Exécution arbitraire via le bridge | le bridge n'expose **que** des actions nommées whitelistées. Aucune route `exec`. Le plugin refuse `dispatchCommand` générique. |
| Pivot depuis le panel vers le serveur MC | bridge **bind interne uniquement**, token distinct par environnement, rate limité, audité des deux côtés. |
| Compromission du panel → catastrophe | actions destructives derrière `confirm` explicite + audit log immuable ; kill-switch d'accès ; permissions (voir RBAC). |

## Authentification (V1 mono-utilisateur acceptable)

- **1 compte owner** en V1. Identifiants **hors code** : hash du mot de passe (Argon2id ou
  PBKDF2-HMAC-SHA256 fort) dans la config/secret store, jamais en clair, jamais dans le dépôt.
- Pas de `if username == "owner"` dispersé : passer par `AuthService.authenticate()` +
  `PermissionService.can(session, Permission.X)` dès le début (voir RBAC).
- **HTTPS obligatoire** en déploiement réel (terminaison TLS par reverse proxy). HTTP en clair
  toléré uniquement en `127.0.0.1` de dev.

## Sessions

- Stockage serveur (mémoire en V1, table `control-panel.db` si besoin de survie au restart).
- Durée configurable ; inactivité → expiration. `logout` = invalidation immédiate.
- Un seul secret de signature/chiffrement (`RPGQUEST_PANEL_SECRET`), rotation documentée.

## Journal d'audit — **dès le socle**

Table append-only `audit_log` dans `control-panel.db` :

| Colonne | Exemple |
|---|---|
| `id` | ULID |
| `ts` | `2026-09-07T20:15:03Z` |
| `actor` | `owner` |
| `action` | `actions/quest-complete` |
| `target` | `player=Rondoudou9000 quest=rpgquest:crystal_hunt env=DEV` |
| `result` | `COMPLETED` / `DENIED:permission` / `ERROR:bridge_unreachable` |
| `details` | JSON court, **sans secret** (payload validé, code d'erreur, corrélation) |
| `request_id` | corrélation avec les logs HTTP et le log `[web-admin]` du plugin |

- Écrit **avant** (intention) et **après** (résultat) pour les actions.
- Consultable dans le module « Admin » (lecture seule, filtrable).
- Jamais purgé automatiquement en V1.

## Permissions (RBAC minimal, extensible — ne pas tout implémenter)

`PermissionService.can(session, Permission)` — jamais de check ad hoc.

Rôles cibles (V1 : seul `owner` existe, mais l'énum et la table sont posées) :

| Rôle | Peut |
|---|---|
| `owner` | tout |
| `tester` | lectures + actions #36 (quest/story/variable) sur DEV |
| `content-editor` | lectures + reload contenu + (futur) édition YAML guidée |
| `builder` | lectures + (futur) actions build/monde ciblées |
| `read-only` | lectures uniquement |

`Permission` : `PLAYERS_READ`, `NPC_READ`, `CONTENT_READ`, `DIAGNOSTICS_READ`,
`ACTION_QUEST`, `ACTION_STORY`, `ACTION_VARIABLE_GET`, `ACTION_VARIABLE_SET`,
`ACTION_PLAYER_RESET`, `ACTION_CONTENT_RELOAD`, `AUDIT_READ`, `DEV_MODULE`… (extensible).

## Kill-switch

Un moyen de **couper l'accès au panel immédiatement** sans redéploiement :

- variable d'env / clé de config `PANEL_DISABLED=true` → toute requête sauf `/health` renvoie
  `503` + page « panel désactivé » ;
- documenté dans le runbook de déploiement.

## Multi-environnement & secrets

- Un secret **par environnement** : `RPGQUEST_BRIDGE_TOKEN_DEV`, `..._STAGING`, `..._PROD`.
- Le token du bridge n'est **jamais** envoyé au navigateur : seul le backend Control Panel le
  détient et parle au bridge.
- Changer de cible dans l'UI ne change **jamais** de secret côté client — c'est le backend qui
  sélectionne le bon token selon `env`.
