# Control Panel — architecture

> Ce document décrit l'**architecture réelle** livrée par #37 (socle exécutable). Les modules
> métier (Joueurs, PNJ, Diagnostics, actions) sont décrits comme cibles, non encore implémentés.

## 1. Frontière générale

```
Navigateur (desktop + Android)
    │  HTTPS, session authentifiée
    ▼
┌─────────────────────────────────────────────┐
│ RPGQuest Control Panel  (process JVM séparé) │
│  - auth / sessions / CSRF / audit log        │
│  - modules : Dashboard, Joueurs, PNJ,        │
│    Quêtes, Stories, Diagnostics, Admin, Dev  │
│  - client "RPGQuest bridge" (par cible)      │
│  - client GitHub / Claude (futur, #29)       │
└───────────────┬─────────────────────────────┘
                │  HTTP interne, token, /admin/v1/*
                ▼
┌─────────────────────────────────────────────┐
│ Plugin RPGQuest  (Paper)  — SOURCE DE VÉRITÉ │
│  web.admin  (nouvel endpoint HTTP admin)     │
│    ├── health/status/version                 │
│    ├── lecture : joueurs, PNJ bindings,      │
│    │   quêtes/stories chargées, variables,   │
│    │   claims, diagnostics de contenu        │
│    └── actions whitelistées ──► services :   │
│         QuestProgressEngine / StoryService / │
│         PlayerResetService / ClaimService /  │
│         NpcIdentityService / ...             │
└─────────────────────────────────────────────┘
```

Le frontend ne connaît **jamais** le schéma SQLite. Le backend Control Panel ne réimplémente
**jamais** une règle métier du plugin.

## 2. Modules et responsabilités (réels)

| Module | Responsabilité | Dépendances |
|---|---|---|
| **`control-panel/`** (module Gradle, `application`) | app d'admin autonome : routing (`com.sun.net.httpserver`), **sessions signées HMAC**, **CSRF**, pages HTML server-rendered (pas de SPA), `AuthService`, `PermissionService`, `AuditLog` (SQLite `control-panel.db`), `TargetRegistry`, `BridgeClient` (`java.net.http`), `panel.json.Json` maison | JDK + `org.xerial:sqlite-jdbc` (audit log). **Aucune** dépendance vers Paper ni vers `:web-api`. |
| plugin — package **`com.lodygames.rpgquest.web.admin`** | `WebAdminServer` (endpoint `/admin/v1/*`, auth Bearer, fail-closed, bind interne) + `HealthSource` / `BukkitHealthSource` (état réel via l'API publique Paper) | services/API du plugin uniquement — **jamais** `data.db` exposé, **jamais** `Runtime.exec` |
| plugin — package **`com.lodygames.rpgquest.web.agent`** (#51) | `PlugAdminAgent` (service Paper) + `AgentLoop` (heartbeat + file d'actions, backoff, idempotence, sans Bukkit) + `PlugAdminClient` (HTTPS sortant `java.net.http`) + `AgentConfigLoader` (fichier local hors Git) + `AgentActionExecutor` (whitelist → services métier) | `HealthSource` (#37, réutilisé), `PlayerVariableRepository` ; threads asynchrones uniquement ; aucun `trustAll` |
| control-panel — package **`com.lodygames.rpgquest.panel.agent`** (#51) | `AgentEndpoints` (`/agent/v1/*`, auth par agent), `AgentStore` (`control-panel.db` : `agent_heartbeat`, `agent_action`), `AgentRegistry`, `AgentLiveness` (ONLINE/STALE/OFFLINE) | JDK + SQLite ; jamais `data.db` |
| `web-api/` (existant) | **inchangé** — portail public read-only + boutique, lit `snapshot.json` | — |

`web-common/` **n'a pas été extrait** en V1 (voir [DECISIONS.md](DECISIONS.md) ADR-009) : le
Control Panel est autonome, `web-api` n'est pas touché.

> Le Control Panel **n'est pas** un package de `web-api/` : posture de sécurité différente
> (anonyme vs authentifié + pouvoirs admin) — [ADR-001].

## 3. Frontière live / dépôt / runtime / fichiers

Distinction **structurelle** (essentielle pour les diagnostics futurs) :

| Catégorie | Source | Accès Control Panel |
|---|---|---|
| **Contenu versionné** (YAML dépôt, version JAR attendue) | Git (checkout local ou API GitHub, #29) | module « Développement » |
| **Contenu chargé par le serveur** (quêtes/stories/dialogues réellement en mémoire, version plugin) | bridge `/admin/v1/content/*` | module « Diagnostics » |
| **Données runtime joueur** (progression, variables, claims, PNJ bindings) | bridge `/admin/v1/players/*`, `/npc/*`, `/claims/*` | modules « Joueurs », « PNJ », « Claims » |
| **Fichiers persistants** (`data.db`, mondes, `Citizens/saves.yml`) | **jamais lus directement par le panel** ; le plugin les expose via le bridge s'il y a un besoin | — |

Un module « Diagnostics » compare *contenu versionné* ↔ *contenu chargé* (ex. « le dépôt
définit un PNJ `guard`, le serveur n'a aucun binding `guard` » — exactement le blocant observé
en session #21).

## 4. Stack

- **Langage** : Java 21 (cohérence avec le reste du monorepo).
- **Build** : Gradle, nouveau module `control-panel` dans `settings.gradle.kts`.
- **HTTP serveur** : réutiliser `com.sun.net.httpserver` (déjà en place dans `web-api`, zéro
  dépendance) **ou** adopter un micro-framework léger si le rendu HTML + sessions + CSRF devient
  pénible à la main — **décision en ADR-002**, à trancher au début du dev, pas maintenant.
- **Rendu** : HTML server-rendered (templates simples), pas de SPA en V1. Progressive
  enhancement. Responsive (desktop riche + Android pour les opérations courantes).
- **Persistance Control Panel** : une petite base **`control-panel.db`** (SQLite, comme
  `store.db`) pour l'audit log et, plus tard, les utilisateurs/rôles. **Séparée de `data.db`.**
- **Client bridge** : `java.net.http.HttpClient`.

## 5. Structure de code (réelle — #37)

```
control-panel/
  build.gradle.kts
  control-panel.properties.example
  src/main/java/com/lodygames/rpgquest/panel/
    PanelMain.java                        # entrée : run | hash-password
    config/    PanelConfig, PanelConfigLoader, PanelConfigException, Target
    json/      Json, JsonParseException   # codec maison (parser + writer)
    security/  PasswordHasher (PBKDF2), AuthService, Session, SessionStore (cookie signé HMAC)
    authz/     Permission, Role, PermissionService     # RBAC minimal extensible
    audit/     AuditLog (interface), AuditEntry, SqliteAuditLog, InMemoryAuditLog
    bridge/    BridgeClient, BridgeException, BridgeHealth
    http/      Http                       # cookies, form, réponses, en-têtes de sécurité, escape
    web/       PanelApp, Layout           # assemblage + handlers + gabarit HTML/CSS
  src/test/java/com/lodygames/rpgquest/panel/
    web/PanelAppTest              (11 : liveness, protection, login ok/ko, logout+CSRF, secrets, bridge up/down, kill-switch, audit)
    bridge/BridgeClientTest       (4)
    security/PasswordHasherTest   (4)
    json/JsonTest                 (3)
    support/  TestConfig, StubBridge

src/main/java/com/lodygames/rpgquest/web/admin/          # DANS le plugin
  WebAdminServer.java            # /admin/v1/health, auth Bearer, fail-closed
  HealthSource.java              # interface (payload JSON)
  BukkitHealthSource.java        # impl réelle (API publique Paper)
src/test/java/com/lodygames/rpgquest/web/admin/WebAdminServerTest.java   (4 : fail-closed, 401×3, health réel, 405)
```

Handlers regroupés dans `PanelApp` en V1 (peu de routes) ; ils seront éclatés en modules dédiés
dès que les modules métier arrivent (#38…). Un module non implémenté affiche « à venir »,
**jamais** un faux écran fonctionnel.

## 6. Démarrage local

Voir [README.md](README.md) § « Démarrage local (5 min) » et [CONFIGURATION.md](CONFIGURATION.md).
En résumé : `.../bin/control-panel hash-password` (après `:control-panel:installDist`) pour l'owner ; activer le bridge côté
plugin par `RPGQUEST_WEB_ADMIN_ENABLED=true` + `RPGQUEST_WEB_ADMIN_TOKEN` ; lancer
`.../bin/control-panel` (distribution `installDist`) ou `./gradlew :control-panel:run` → `http://127.0.0.1:8090`.

## 7. Déploiement AWS

- Le Control Panel tourne **sur AWS** (là où se fait déjà le build), à côté de `web-api`.
- Le **bridge du plugin** est exposé **uniquement sur le réseau interne** (bind `127.0.0.1` en
  local ; sur VeryGames, pas d'accès entrant — le bridge n'est joignable que si le serveur MC est
  co-localisé ou via un tunnel sortant/relais, à décider en ADR-004). **Tant que le plugin tourne
  sur VeryGames sans port entrant exploitable, le bridge live n'est pas joignable** : la V1 peut
  fonctionner en mode « snapshot enrichi » (le plugin pousse un `admin-snapshot.json` étendu que
  le panel lit sur AWS via le même mécanisme d'export que `web-api`) — voir
  [RPGQUEST_BRIDGE.md](RPGQUEST_BRIDGE.md) §« Mode dégradé ».
- HTTPS : terminaison TLS devant le Control Panel (reverse proxy). Jamais d'HTTP en clair sur
  Internet.
- Secrets : variables d'environnement du service systemd / gestionnaire de process, **jamais**
  dans le dépôt.

## 8. Observabilité

- Logs structurés (clé=valeur), niveau configurable.
- Une erreur du plugin (bridge) remonte comme **erreur exploitable** dans le panel (message +
  code + corrélation), pas un HTTP 500 opaque.
- Audit log : voir [SECURITY.md](SECURITY.md).
