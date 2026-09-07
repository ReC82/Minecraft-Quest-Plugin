# Control Panel — architecture

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

## 2. Modules et responsabilités

| Module | Responsabilité | Dépendances autorisées |
|---|---|---|
| `web-common/` *(à extraire de `web-api/`)* | HTTP (`com.sun.net.httpserver` ou remplacement décidé en ADR-002), codec JSON, `RequestPipeline`, `RateLimiter`, `AccessLogger`, helpers de réponse | JDK seul (+ éventuel micro-framework, voir ADR-002) |
| `control-panel/` (nouveau module Gradle) | app d'admin : routing, **sessions**, **CSRF**, pages HTML server-rendered (pas de SPA en V1), `AuthService`, `PermissionService`, `AuditLog`, `TargetRegistry`, `RpgQuestBridgeClient`, modules fonctionnels | `web-common`, JDK, driver HTTP client |
| plugin `com.lodygames.rpgquest.web.admin` (nouveau package **dans le plugin**) | endpoint HTTP admin authentifié : health + lectures + **actions déclaratives** déléguées aux services métier existants | services métier du plugin uniquement — **jamais** JDBC direct exposé, **jamais** `Runtime.exec` |
| `web-api/` (existant, inchangé) | portail public read-only + boutique. Continue de lire `snapshot.json`. | — |

> Le Control Panel **n'est pas** un package de `web-api/` : posture de sécurité différente
> (anonyme vs authentifié + pouvoirs admin) — voir [DECISIONS.md](DECISIONS.md) ADR-001.

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

## 5. Structure de code cible (`control-panel/`)

```
control-panel/
  build.gradle.kts
  src/main/java/com/lodygames/rpgquest/panel/
    PanelMain.java
    config/        PanelConfig, PanelConfigLoader, TargetRegistry
    http/          Router, SessionFilter, CsrfFilter, StaticAssets   (ou via web-common)
    auth/          AuthService, PasswordHasher, Session, LoginHandler, LogoutHandler
    authz/         Permission, Role, PermissionService               (RBAC minimal extensible)
    audit/         AuditLog, AuditEntry, AuditDao
    bridge/        RpgQuestBridgeClient, BridgeException, dto/*        (miroir du contrat /admin/v1)
    modules/
      dashboard/   DashboardHandler   (health réel : panel + bridge + version plugin + timestamp)
      players/     PlayersHandler     (V1 : liste + détail lecture ; actions plus tard)
      npc/         NpcHandler         (V1 : bindings + "manquants" ; futur)
      quests/      QuestsHandler      (futur)
      stories/     StoriesHandler     (futur)
      diagnostics/ DiagnosticsHandler (futur)
      admin/       AdminActionsHandler (actions #36 whitelistées ; câblage progressif)
      dev/         DevHandler         (#29 ; futur)
  src/test/java/...   (auth, session, audit, bridge client contre un stub, health check)
```

Chaque module = handler + service dédié. **Pas** de contrôleur géant. Un module non implémenté
affiche « module à venir », **jamais** un faux écran fonctionnel.

## 6. Démarrage local

```
# plugin : activer l'endpoint admin (config.yml, section web-admin — voir RPGQUEST_BRIDGE.md)
#   web-admin.enabled: true / port / bind 127.0.0.1 / token via env

export RPGQUEST_PANEL_SECRET=...            # secret de session
export RPGQUEST_BRIDGE_TOKEN_DEV=...        # token partagé avec le plugin (env DEV)
./gradlew :control-panel:run
# -> http://127.0.0.1:8090 , page de login
```

Config par `control-panel.properties` (répertoire de travail) + variables d'env pour les secrets.
Voir [CONFIGURATION.md](CONFIGURATION.md).

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
