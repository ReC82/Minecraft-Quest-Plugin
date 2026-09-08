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
| **Agent sortant #51** : `/agent/v1/*` — jeton porteur **par agent**, temps constant, `agent_id`+`environnement` validés, payload borné (413), `action_id` restreint, types d'action whitelistés, aucun shell/RCON/SQL/chemin, idempotence/anti-rejeu, audit `agent.action.*` | ✅ ([AGENT.md](AGENT.md) §9) |
| Agent : HTTPS validé (aucun `trustAll`), aucun secret dans logs/`toString`/réponses, kill-switch → 503 | ✅ |
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
| Abus du endpoint agent public (`/agent/v1/*` exposé en HTTPS) | jeton fort **par cible** (comparaison temps constant), `agent_id`+env validés, payload borné, types whitelistés (aucune commande/chemin/SQL), idempotence anti-rejeu, audit. Rate limiting applicatif non fait — nginx devant + backoff agent ; à durcir si multi-agents. |
| Rejeu d'une action / double exécution | UUID par action ; côté panel une action reste livrée jusqu'au résultat terminal, un résultat sur action terminale est un no-op ; côté agent `ProcessedActionCache` (TTL 30 min) empêche la ré-exécution. |
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

`Permission` : `PLAYERS_READ`, `NPC_READ`, `CONTENT_READ`, `DOCS_READ`, `DIAGNOSTICS_READ`,
`ACTION_QUEST`, `ACTION_STORY`, `ACTION_VARIABLE_GET`, `ACTION_VARIABLE_SET`,
`ACTION_PLAYER_RESET`, `ACTION_CONTENT_RELOAD`, `AUDIT_READ`, `DEV_MODULE`… (extensible).

## Centre de documentation `/docs` (issue #49)

Wiki d'administration **privé** — jamais public. Garde-fous :

- **Accès** : session authentifiée + `DOCS_READ` (accordée à tous les rôles pour le MVP ;
  restreignable ensuite sans toucher au handler). `/docs` anonyme → `303` vers `/login`.
- **Source fermée** : le contenu vient d'un ensemble **fixe** de fichiers Markdown livrés avec le
  jar (`control-panel/src/main/resources/docs/`), énumérés par le manifeste `_index.txt` qui
  **est** la liste blanche. Un fichier non listé n'est jamais servi. Aucun contenu n'est copié
  en base (Git reste la source de vérité).
- **Pas de chemin du navigateur** : une fiche est adressée par un `slug` interne
  (`[a-z0-9-]{1,64}`), validé puis **résolu par un lookup en mémoire** (`Map<slug, DocPage>`).
  **Aucun accès disque au moment de la requête** → path traversal, lecture de `.env`, de clés,
  de configs sensibles : impossibles par construction. Un slug inconnu ou malformé → `404`
  (jamais `500`, jamais de contenu).
- **Rendu Markdown sûr** (`docs.Markdown`) : tout le texte source est échappé HTML ; aucune
  balise HTML brute, aucun `<script>`, aucun gestionnaire d'événement, aucune URL `javascript:` ;
  les liens ne sont rendus que vers `/docs/…`, une ancre `#…`, ou `https://…`. Conforme à la CSP
  (`default-src 'self'` ; le bouton « Copier » réutilise `/assets/panel.js`, aucun script inline).

## Éditeur guidé de quêtes et de stories (issue #46)

Écrit du contenu **dans le checkout source** (jamais sur le serveur live, jamais par FTP, jamais
de déploiement). Garde-fous :

- **Accès** : session authentifiée + permission dédiée `QUEST_CONTENT_WRITE` /
  `STORY_CONTENT_WRITE` (rôles `CONTENT_EDITOR` + `OWNER`). Aucun `if role == OWNER` dans les
  handlers. Route d'enregistrement en `POST` uniquement, **jeton CSRF synchroniseur** obligatoire.
- **Périmètre d'écriture fermé** (`ContentWorkspace`) : uniquement
  `<content.repo-dir>/quests/*.yml` et `<content.repo-dir>/stories/*.yml`. Le nom de fichier est
  dérivé de l'identifiant saisi et validé (`[a-z0-9][a-z0-9_-]{0,63}`) ; aucun chemin fourni par
  le navigateur n'est résolu ; `normalize()` + `startsWith(dir)` vérifiés → path traversal
  impossible (test : id `../../evil` refusé, aucun fichier hors dossier). Interdits par
  construction : `data.db`, `.env`, secrets, `config.yml`, mondes, Citizens, plugins externes.
- **Écriture sûre** : hash SHA-256 du fichier calculé à l'ouverture ; au `save`, recalcul et
  **refus si le fichier a changé entre-temps** (`CONFLICT`) ou existe déjà en création
  (`EXISTS`) — jamais d'écrasement silencieux. Écriture fichier temporaire + `ATOMIC_MOVE`.
- **Garde-fou round-trip** (B12) : le YAML généré est relu puis ré-émis ; toute divergence
  **bloque l'enregistrement**. L'autorité finale reste le moteur RPGQuest, qui refuse un fichier
  invalide au chargement du serveur.
- **Validation avant écriture** : `QuestValidator` / `StoryValidator` produisent des `ERROR`
  (bloquants), `WARNING` (enregistrement possible après vérification) et `INFO`. Une récompense
  « commande console » est systématiquement signalée `WARNING`.
- **Lecture seule gracieuse** : si `content.repo-dir` est absent, ou si le service n'a pas les
  droits d'écriture, l'éditeur reste consultable (validation / aperçu / diff) mais le bouton
  d'enregistrement est désactivé et une bannière l'explique. **Aucun `chmod` / `chown` / `sudo`
  n'est jamais exécuté.**
- **Audit** : chaque écriture réussie journalise `quests.content.write` /
  `stories.content.write` (acteur = utilisateur de session).
- **Sans JavaScript** : le formulaire dynamique fonctionne par aller-retour serveur ; conforme à
  la CSP (`default-src 'self'`), pas de script inline, listes de valeurs en `<datalist>`.

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
